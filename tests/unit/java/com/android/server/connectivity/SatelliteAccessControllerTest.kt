/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.connectivity

import android.Manifest
import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.ArraySet
import com.android.server.connectivity.ConnectivityFlags.CONSTRAINED_DATA_SATELLITE_OPTIN
import com.android.server.connectivity.SatelliteAccessController.PER_USER_RANGE
import com.android.server.connectivity.SatelliteAccessController.PROPERTY_SATELLITE_DATA_OPTIMIZED
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule.FeatureFlag
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

private const val PRIMARY_USER = 0
private const val SECONDARY_USER = 10
private val PRIMARY_USER_HANDLE = UserHandle.of(PRIMARY_USER)
private val SECONDARY_USER_HANDLE = UserHandle.of(SECONDARY_USER)

// sms app names
private const val SMS_APP1 = "sms_app_1"
private const val SMS_APP2 = "sms_app_2"

// sms app ids
private const val SMS_APP_ID1 = 100
private const val SMS_APP_ID2 = 101

// UID for app1 and app2 on primary user
// These app could become default sms app for user1
private val PRIMARY_USER_SMS_APP_UID1 = UserHandle.getUid(PRIMARY_USER, SMS_APP_ID1)
private val PRIMARY_USER_SMS_APP_UID2 = UserHandle.getUid(PRIMARY_USER, SMS_APP_ID2)

// UID for app1 and app2 on secondary user
// These app could become default sms app for user2
private val SECONDARY_USER_SMS_APP_UID1 = UserHandle.getUid(SECONDARY_USER, SMS_APP_ID1)
private val SECONDARY_USER_SMS_APP_UID2 = UserHandle.getUid(SECONDARY_USER, SMS_APP_ID2)

private const val TEST_PACKAGE1 = "com.android.package1"
private const val TEST_PACKAGE2 = "com.android.package2"
private const val TEST_UID1 = 2001
private const val TEST_UID2 = 2002 + SECONDARY_USER * PER_USER_RANGE // Under 2nd user.

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class SatelliteAccessControllerTest {
    private val context = mock(Context::class.java)
    private val primaryUserContext = mock(Context::class.java)
    private val secondaryUserContext = mock(Context::class.java)
    private val packageManager = mock(PackageManager::class.java)
    private val mPackageManagerPrimaryUser = mock(PackageManager::class.java)
    private val mPackageManagerSecondaryUser = mock(PackageManager::class.java)
    private val mDeps = mock(SatelliteAccessController.Dependencies::class.java)
    private val mCallback = mock(Consumer::class.java) as Consumer<Set<Int>>
    private val userManager = mock(UserManager::class.java)
    private val mHandler = Handler(Looper.getMainLooper())
    private lateinit var mSatelliteAccessController: SatelliteAccessController
    private lateinit var mRoleHolderChangedListener: OnRoleHoldersChangedListener

    private val featureFlags = HashSet<String>()

    // This will set feature flags from @FeatureFlag annotations
    // into the map before setUp() runs.
    @get:Rule
    val setFeatureFlagsRule = SetFeatureFlagsRule(
            { name, enabled ->
                if (enabled == true) featureFlags.add(name) else featureFlags.remove(name) },
            { name -> featureFlags.contains(name) }
    )

    private fun <T> mockService(name: String, clazz: Class<T>, service: T) {
        doReturn(name).`when`(context).getSystemServiceName(clazz)
        doReturn(service).`when`(context).getSystemService(name)
        if (context.getSystemService(clazz) == null) {
            // Test is using mockito-extended
            doReturn(service).`when`(context).getSystemService(clazz)
        }
    }

    @Before
    @Throws(PackageManager.NameNotFoundException::class)
    fun setup() {
        doReturn(emptyList<UserHandle>()).`when`(userManager).getUserHandles(true)
        mockService(Context.USER_SERVICE, UserManager::class.java, userManager)
        doReturn(packageManager).`when`(context).packageManager
        doReturn(featureFlags.contains(CONSTRAINED_DATA_SATELLITE_OPTIN))
                .`when`(mDeps).supportConstrainedDataSatelliteOptIn(any())
        mSatelliteAccessController = SatelliteAccessController(context, mDeps, mCallback, mHandler)

        doReturn(primaryUserContext).`when`(context).createContextAsUser(PRIMARY_USER_HANDLE, 0)
        doReturn(mPackageManagerPrimaryUser).`when`(primaryUserContext).packageManager

        doReturn(secondaryUserContext).`when`(context).createContextAsUser(SECONDARY_USER_HANDLE, 0)
        doReturn(mPackageManagerSecondaryUser).`when`(secondaryUserContext).packageManager

        for (app in listOf(SMS_APP1, SMS_APP2)) {
            doReturn(PackageManager.PERMISSION_GRANTED)
                .`when`(mPackageManagerPrimaryUser)
                .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, app)
            doReturn(PackageManager.PERMISSION_GRANTED)
                .`when`(mPackageManagerSecondaryUser)
                .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, app)
        }

        // Initialise message application primary user package1
        val applicationInfo1 = ApplicationInfo()
        applicationInfo1.uid = PRIMARY_USER_SMS_APP_UID1
        doReturn(applicationInfo1)
            .`when`(mPackageManagerPrimaryUser)
            .getApplicationInfo(eq(SMS_APP1), anyInt())

        // Initialise message application primary user package2
        val applicationInfo2 = ApplicationInfo()
        applicationInfo2.uid = PRIMARY_USER_SMS_APP_UID2
        doReturn(applicationInfo2)
            .`when`(mPackageManagerPrimaryUser)
            .getApplicationInfo(eq(SMS_APP2), anyInt())

        // Initialise message application secondary user package1
        val applicationInfo3 = ApplicationInfo()
        applicationInfo3.uid = SECONDARY_USER_SMS_APP_UID1
        doReturn(applicationInfo3)
            .`when`(mPackageManagerSecondaryUser)
            .getApplicationInfo(eq(SMS_APP1), anyInt())

        // Initialise message application secondary user package2
        val applicationInfo4 = ApplicationInfo()
        applicationInfo4.uid = SECONDARY_USER_SMS_APP_UID2
        doReturn(applicationInfo4)
            .`when`(mPackageManagerSecondaryUser)
            .getApplicationInfo(eq(SMS_APP2), anyInt())
    }

    @Test
    fun test_onRoleHoldersChanged_SatelliteFallbackUid_Changed_SingleUser() {
        startSatelliteAccessController()
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is available as satellite network fallback uid
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID1))

        // check SMS_APP2 is available as satellite network Fallback uid
        doReturn(listOf(SMS_APP2)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2))

        // check no uid is available as satellite network fallback uid
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(ArraySet())
    }

    @Test
    fun test_onRoleHoldersChanged_NoSatelliteCommunicationPermission() {
        startSatelliteAccessController()
        doReturn(listOf<Any>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is not available as satellite network fallback uid
        // since satellite communication permission not available.
        doReturn(PackageManager.PERMISSION_DENIED)
            .`when`(mPackageManagerPrimaryUser)
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, SMS_APP1)
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())
    }

    @Test
    fun test_onRoleHoldersChanged_RoleSms_NotAvailable() {
        startSatelliteAccessController()
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(
            RoleManager.ROLE_BROWSER,
            PRIMARY_USER_HANDLE
        )
        verify(mCallback, never()).accept(any())
    }

    @Test
    fun test_onRoleHoldersChanged_SatelliteNetworkFallbackUid_Changed_multiUser() {
        startSatelliteAccessController()
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback, never()).accept(any())

        // check SMS_APP1 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP1))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID1))

        // check SMS_APP2 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP2)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2))

        // check SMS_APP1 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP1)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2, SECONDARY_USER_SMS_APP_UID1))

        // check no uid is available as satellite network fallback uid at primary user
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        verify(mCallback).accept(setOf(SECONDARY_USER_SMS_APP_UID1))

        // check SMS_APP2 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP2))
            .`when`(mDeps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(setOf(SECONDARY_USER_SMS_APP_UID2))

        // check no uid is available as satellite network fallback uid at secondary user
        doReturn(listOf<String>()).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(ArraySet())
    }

    @Test
    fun test_SatelliteFallbackUidCallback_OnUserRemoval() {
        startSatelliteAccessController()
        // check SMS_APP2 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP2)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2))

        // check SMS_APP1 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP1)).`when`(mDeps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        mRoleHolderChangedListener.onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        verify(mCallback).accept(setOf(PRIMARY_USER_SMS_APP_UID2, SECONDARY_USER_SMS_APP_UID1))
        processOnHandlerThread {
            mSatelliteAccessController.onUserRemoved(SECONDARY_USER_HANDLE)
        }
        verify(mCallback, times(2)).accept(setOf(PRIMARY_USER_SMS_APP_UID2))
    }

    private fun <T : Any> processOnHandlerThread(function: () -> T): T {
        val future = CompletableFuture<T>()
        mHandler.post { future.complete(function()) }
        return future.get()
    }

    private fun startSatelliteAccessController() {
        mSatelliteAccessController.start()
        // Get registered listener using captor
        val listenerCaptor = ArgumentCaptor.forClass(OnRoleHoldersChangedListener::class.java)
        verify(mDeps).addOnRoleHoldersChangedListenerAsUser(
            any(Executor::class.java),
            listenerCaptor.capture(),
            any(UserHandle::class.java)
        )
        mRoleHolderChangedListener = listenerCaptor.value
    }

    private fun makePackageInfo(packageName: String, uid: Int) = PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = ApplicationInfo().apply { this.uid = uid }
    }

    private fun mockGetPackagesForUid(uid: Int, pkgs: Array<String>?) {
        `when`(packageManager.getPackagesForUid(uid)).thenReturn(pkgs)
    }

    private fun mockIsSatelliteDataOptimizedApp(packageName: String, isOptimized: Boolean) {
        val property = mock(PackageManager.Property::class.java)
        `when`(property.isString).thenReturn(isOptimized)
        if (isOptimized) {
            `when`(property.string).thenReturn(packageName)
        }
        `when`(packageManager.getProperty(
                eq(PROPERTY_SATELLITE_DATA_OPTIMIZED),
                eq(packageName)
        )).thenReturn(property)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptimizedUids_onPackageAdded() {
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE1, true)
        mSatelliteAccessController.onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        assertTrue(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 1)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptimizedUids_onPackageAdded_ignoresIfNotSatelliteOptimized() {
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE1, false)
        mSatelliteAccessController.onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        assertFalse(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptimizedUids_onPackageRemoved_noOtherShareUid() {
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE1, true)
        mockGetPackagesForUid(TEST_UID1, arrayOf(TEST_PACKAGE1))

        mSatelliteAccessController.onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        assertTrue(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 1)
        mSatelliteAccessController.onPackageRemoved(TEST_PACKAGE1, TEST_UID1)
        assertFalse(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptimizedUids_onPackageRemoved_otherShareUid() {
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE1, true)
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE2, true)
        mockGetPackagesForUid(TEST_UID1, arrayOf(TEST_PACKAGE1, TEST_PACKAGE2))

        // Verify uid is not removed if there is still another package shares the same uid.
        mSatelliteAccessController.onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        assertTrue(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 1)
        mSatelliteAccessController.onPackageRemoved(TEST_PACKAGE1, TEST_UID1)
        assertTrue(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 1)

        // Verify uid is removed if there is no other package with shared uid.
        mockGetPackagesForUid(TEST_UID1, null)
        mSatelliteAccessController.onPackageRemoved(TEST_PACKAGE2, TEST_UID1)
        assertFalse(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptimizedUids_onUserAddedRemoved() {
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE1, true)
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE2, true)
        val packageInfo1 = makePackageInfo(TEST_PACKAGE1, TEST_UID1)
        val packageInfo2 = makePackageInfo(TEST_PACKAGE2, TEST_UID2)

        mSatelliteAccessController
                .onUserAddedWithInstalledPackageList(PRIMARY_USER_HANDLE, listOf(packageInfo1))
        mSatelliteAccessController
                .onUserAddedWithInstalledPackageList(SECONDARY_USER_HANDLE, listOf(packageInfo2))
        assertTrue(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        assertTrue(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID2))

        mSatelliteAccessController.onUserRemoved(SECONDARY_USER_HANDLE)
        // Verify that the app associated with the non-removed user is not removed.
        assertTrue(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID1))
        // Verify that the app associated with the removed user is removed.
        assertFalse(mSatelliteAccessController.isSatelliteDataOptimizedUid(TEST_UID2))

        mSatelliteAccessController.onUserRemoved(PRIMARY_USER_HANDLE)
        // Verify everything is removed.
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN, enabled = false)
    @Test
    fun testSatelliteOptimizedUids_featureDisabled() {
        mockIsSatelliteDataOptimizedApp(TEST_PACKAGE1, true)
        val packageInfo1 = makePackageInfo(TEST_PACKAGE1, TEST_UID1)

        // Verify nothing changes and nothing crashes.
        mSatelliteAccessController
                .onUserAddedWithInstalledPackageList(PRIMARY_USER_HANDLE, listOf(packageInfo1))
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
        mSatelliteAccessController.onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
        mSatelliteAccessController.onPackageRemoved(TEST_PACKAGE1, TEST_UID1)
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
        mSatelliteAccessController.onUserRemoved(PRIMARY_USER_HANDLE)
        assertEquals(mSatelliteAccessController.satelliteDataOptimizedUidCount, 0)
    }
}
