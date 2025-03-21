/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.testutils.com.android.testutils

import android.Manifest.permission.MODIFY_PHONE_STATE
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ConditionVariable
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.telephony.CarrierConfigManager
import android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.CarrierPrivilegesCallback
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

private val TAG = CarrierConfigRule::class.simpleName
private const val CARRIER_CONFIG_CHANGE_TIMEOUT_MS = 10_000L

/**
 * A [TestRule] that helps set [CarrierConfigManager] overrides for tests and clean up the test
 * configuration automatically on teardown.
 */
class CarrierConfigRule : TestRule {
    private val HEX_CHARS: CharArray = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    )

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val uiAutomation by lazy { InstrumentationRegistry.getInstrumentation().uiAutomation }
    private val ccm by lazy { context.getSystemService(CarrierConfigManager::class.java) }

    // Map of (subId) -> (original values of overridden settings)
    private val originalConfigs = mutableMapOf<Int, PersistableBundle>()

    // Map of (subId) -> (original values of carrier service package)
    private val originalCarrierServicePackages = mutableMapOf<Int, String?>()

    override fun apply(base: Statement, description: Description): Statement {
        return CarrierConfigStatement(base, description)
    }

    private inner class CarrierConfigStatement(
        private val base: Statement,
        private val description: Description
    ) : Statement() {
        override fun evaluate() {
            tryTest {
                base.evaluate()
            } cleanup {
                cleanUpNow()
            }
        }
    }

    private class ConfigChangeReceiver(private val subId: Int) : BroadcastReceiver() {
        val cv = ConditionVariable()
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CARRIER_CONFIG_CHANGED ||
                intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, -1) != subId) {
                return
            }
            // This may race with other config changes for the same subId, but there is no way to
            // know which update is being reported, and querying the override would return the
            // latest values even before the config is applied. Config changes should be rare, so it
            // is unlikely they would happen exactly after the override applied here and cause
            // flakes.
            cv.open()
        }
    }

    private fun overrideConfigAndWait(subId: Int, config: PersistableBundle) {
        val changeReceiver = ConfigChangeReceiver(subId)
        context.registerReceiver(changeReceiver, IntentFilter(ACTION_CARRIER_CONFIG_CHANGED))
        ccm.overrideConfig(subId, config)
        assertTrue(
            changeReceiver.cv.block(CARRIER_CONFIG_CHANGE_TIMEOUT_MS),
            "Timed out waiting for config change for subId $subId"
        )
        context.unregisterReceiver(changeReceiver)
    }

    /**
     * Add carrier config overrides with the specified configuration.
     *
     * The overrides will automatically be cleaned up when the test case finishes.
     */
    fun addConfigOverrides(subId: Int, config: PersistableBundle) {
        val originalConfig = originalConfigs.computeIfAbsent(subId) { PersistableBundle() }
        val overrideKeys = config.keySet()
        val previousValues = runAsShell(READ_PHONE_STATE) {
            ccm.getConfigForSubIdCompat(subId, overrideKeys)
        }
        // If a key is already in the originalConfig, keep the oldest original overrides
        originalConfig.keySet().forEach {
            previousValues.remove(it)
        }
        originalConfig.putAll(previousValues)

        runAsShell(MODIFY_PHONE_STATE) {
            overrideConfigAndWait(subId, config)
        }
    }

    private fun runShellCommand(cmd: String) {
        val fd: ParcelFileDescriptor = uiAutomation.executeShellCommand(cmd)
        fd.close() // Don't care about the output.
    }

    /**
     * Converts a byte array into a String of hexadecimal characters.
     *
     * @param bytes an array of bytes
     * @return hex string representation of bytes array
     */
    private fun bytesToHexString(bytes: ByteArray?): String? {
        if (bytes == null) return null

        val ret = StringBuilder(2 * bytes.size)

        for (i in bytes.indices) {
            var b: Int
            b = 0x0f and (bytes[i].toInt() shr 4)
            ret.append(HEX_CHARS[b])
            b = 0x0f and bytes[i].toInt()
            ret.append(HEX_CHARS[b])
        }

        return ret.toString()
    }

    private fun setHoldCarrierPrivilege(hold: Boolean, subId: Int) {
        if (!SdkLevel.isAtLeastT()) {
            throw UnsupportedOperationException(
                "Acquiring carrier privilege requires at least T SDK"
            )
        }

        fun getCertHash(): String {
            val pkgInfo = context.packageManager.getPackageInfo(
                context.opPackageName,
                PackageManager.GET_SIGNATURES
            )
            val digest = MessageDigest.getInstance("SHA-256")
            val certHash = digest.digest(pkgInfo.signatures!![0]!!.toByteArray())
            return bytesToHexString(certHash)!!
        }

        val tm = context.getSystemService(TelephonyManager::class.java)!!

        val cv = ConditionVariable()
        val cpb = PrivilegeWaiterCallback(cv)
        // The lambda below is capturing |cpb|, whose type inherits from a class that appeared in
        // T. This means the lambda will compile as a private method of this class taking a
        // PrivilegeWaiterCallback argument. As JUnit uses reflection to enumerate all methods
        // including private methods, this would fail with a link error when running on S-.
        // To solve this, make the lambda serializable, which causes the compiler to emit a
        // synthetic class instead of a synthetic method.
        tryTest @JvmSerializableLambda {
            val slotIndex = SubscriptionManager.getSlotIndex(subId)!!
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.registerCarrierPrivilegesCallback(slotIndex, { it.run() }, cpb)
            }
            // Wait for the callback to be registered
            assertTrue(cv.block(CARRIER_CONFIG_CHANGE_TIMEOUT_MS),
                "Can't register CarrierPrivilegesCallback")
            if (cpb.hasPrivilege == hold) {
                if (hold) {
                    Log.w(TAG, "Package ${context.opPackageName} already is privileged")
                } else {
                    Log.w(TAG, "Package ${context.opPackageName} already isn't privileged")
                }
                return@tryTest
            }
            cv.close()
            if (hold) {
                addConfigOverrides(subId, PersistableBundle().also {
                    it.putStringArray(CarrierConfigManager.KEY_CARRIER_CERTIFICATE_STRING_ARRAY,
                        arrayOf(getCertHash()))
                })
            } else {
                cleanUpNow()
            }
            assertTrue(cv.block(CARRIER_CONFIG_CHANGE_TIMEOUT_MS),
                "Timed out waiting for CarrierPrivilegesCallback")
            assertEquals(cpb.hasPrivilege, hold, "Couldn't set carrier privilege")
        } cleanup @JvmSerializableLambda {
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.unregisterCarrierPrivilegesCallback(cpb)
            }
        }
    }

    /**
     * Acquires carrier privilege on the given subscription ID.
     */
    fun acquireCarrierPrivilege(subId: Int) = setHoldCarrierPrivilege(true, subId)

    /**
     * Drops carrier privilege from the given subscription ID.
     */
    fun dropCarrierPrivilege(subId: Int) = setHoldCarrierPrivilege(false, subId)

    /**
     * Sets the carrier service package override for the given subscription ID. A null argument will
     * clear any previously-set override.
     */
    fun setCarrierServicePackageOverride(subId: Int, pkg: String?) {
        if (!SdkLevel.isAtLeastU()) {
            throw UnsupportedOperationException(
                "Setting carrier service package override requires at least U SDK"
            )
        }

        val tm = context.getSystemService(TelephonyManager::class.java)!!

        val cv = ConditionVariable()
        val cpb = CarrierServiceChangedWaiterCallback(cv)
        // The lambda below is capturing |cpb|, whose type inherits from a class that appeared in
        // T. This means the lambda will compile as a private method of this class taking a
        // PrivilegeWaiterCallback argument. As JUnit uses reflection to enumerate all methods
        // including private methods, this would fail with a link error when running on S-.
        // To solve this, make the lambda serializable, which causes the compiler to emit a
        // synthetic class instead of a synthetic method.
        tryTest @JvmSerializableLambda {
            val slotIndex = SubscriptionManager.getSlotIndex(subId)!!
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.registerCarrierPrivilegesCallback(slotIndex, { it.run() }, cpb)
            }
            // Wait for the callback to be registered
            assertTrue(cv.block(CARRIER_CONFIG_CHANGE_TIMEOUT_MS),
                "Can't register CarrierPrivilegesCallback")
            if (cpb.pkgName == pkg) {
                Log.w(TAG, "Carrier service package was already $pkg")
                return@tryTest
            }
            if (!originalCarrierServicePackages.contains(subId)) {
                originalCarrierServicePackages.put(subId, cpb.pkgName)
            }
            cv.close()
            runAsShell(MODIFY_PHONE_STATE) {
                if (null == pkg) {
                    // There is a bug in clear-carrier-service-package-override where not adding
                    // the -s argument will use the wrong slot index : b/299604822
                    runShellCommand("cmd phone clear-carrier-service-package-override" +
                            " -s $subId")
                } else {
                    runShellCommand("cmd phone set-carrier-service-package-override $pkg" +
                            " -s $subId")
                }
            }
            assertTrue(cv.block(CARRIER_CONFIG_CHANGE_TIMEOUT_MS),
                "Can't modify carrier service package")
        } cleanup @JvmSerializableLambda {
            runAsShell(READ_PRIVILEGED_PHONE_STATE) @JvmSerializableLambda {
                tm.unregisterCarrierPrivilegesCallback(cpb)
            }
        }
    }

    private class PrivilegeWaiterCallback(private val cv: ConditionVariable) :
        CarrierPrivilegesCallback {
        var hasPrivilege = false
        override fun onCarrierPrivilegesChanged(p: MutableSet<String>, uids: MutableSet<Int>) {
            hasPrivilege = uids.contains(Process.myUid())
            cv.open()
        }
    }

    private class CarrierServiceChangedWaiterCallback(private val cv: ConditionVariable) :
        CarrierPrivilegesCallback {
        var pkgName: String? = null
        override fun onCarrierPrivilegesChanged(p: MutableSet<String>, u: MutableSet<Int>) {}
        override fun onCarrierServiceChanged(pkgName: String?, uid: Int) {
            this.pkgName = pkgName
            cv.open()
        }
    }

    /**
     * Cleanup overrides that were added by the test case.
     *
     * This will be called automatically on test teardown, so it does not need to be called by the
     * test case unless cleaning up earlier is required.
     */
    fun cleanUpNow() {
        runAsShell(MODIFY_PHONE_STATE) {
            originalConfigs.forEach { (subId, config) ->
                try {
                    // Do not use null as the config to reset, as it would reset configs that may
                    // have been set by target preparers such as
                    // ConnectivityTestTargetPreparer / CarrierConfigSetupTest.
                    overrideConfigAndWait(subId, config)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error resetting carrier config for subId $subId")
                }
            }
            originalConfigs.clear()
        }
        originalCarrierServicePackages.forEach { (subId, pkg) ->
            setCarrierServicePackageOverride(subId, pkg)
        }
        originalCarrierServicePackages.clear()
    }
}

private fun CarrierConfigManager.getConfigForSubIdCompat(
    subId: Int,
    keys: Set<String>
): PersistableBundle {
    return if (SdkLevel.isAtLeastU()) {
        // This method is U+
        getConfigForSubId(subId, *keys.toTypedArray())
    } else {
        @Suppress("DEPRECATION")
        val config = assertNotNull(getConfigForSubId(subId))
        val allKeys = config.keySet().toList()
        allKeys.forEach {
            if (!keys.contains(it)) {
                config.remove(it)
            }
        }
        config
    }
}
