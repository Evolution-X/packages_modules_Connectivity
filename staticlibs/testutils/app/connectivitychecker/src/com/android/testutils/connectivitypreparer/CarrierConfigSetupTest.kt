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

package com.android.testutils.connectivitypreparer

import android.Manifest.permission.MODIFY_PHONE_STATE
import android.Manifest.permission.READ_PHONE_STATE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.FEATURE_TELEPHONY_IMS
import android.content.pm.PackageManager.FEATURE_WIFI
import android.os.Build
import android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.runAsShell
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

private const val CONFIG_CHANGE_TIMEOUT_MS = 10_000L
private val TAG = CarrierConfigSetupTest::class.simpleName

@RunWith(AndroidJUnit4::class)
class CarrierConfigSetupTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val pm by lazy { context.packageManager }
    private val carrierConfigManager by lazy {
        context.getSystemService(CarrierConfigManager::class.java)
    }

    @Test
    fun testSetCarrierConfig() {
        if (!shouldDisableIwlan()) return
        overrideAllSubscriptions(PersistableBundle().apply {
            putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, false)
        })
    }

    @Test
    fun testClearCarrierConfig() {
        // set/clear are in different test runs so it is difficult to share state between them.
        // The conditions to disable IWLAN should not change over time (in particular
        // force_iwlan_mms is a readonly flag), so just perform the same check again on teardown.
        // CarrierConfigManager overrides are cleared on reboot by default anyway, so any missed
        // cleanup should not be too damaging.
        if (!shouldDisableIwlan()) return
        overrideAllSubscriptions(null)
    }

    private class ConfigChangedReceiver : BroadcastReceiver() {
        val receivedSubIds = ArrayTrackRecord<Int>()
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CARRIER_CONFIG_CHANGED) return
            val subIdx = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
            // It is possible this is a configuration change for a different setting, so the test
            // may not wait for long enough. Unfortunately calling CarrierConfigManager to check
            // if the config was applied does not help because it will always return the override,
            // even if it was not applied to the subscription yet.
            // In practice, it is very unlikely that a different broadcast arrives, and then a test
            // flakes because of the iwlan behavior in the time it takes for the config to be
            // applied.
            Log.d(TAG, "Received config change for sub $subIdx")
            receivedSubIds.add(subIdx)
        }
    }

    private fun overrideAllSubscriptions(bundle: PersistableBundle?) {
        runAsShell(READ_PHONE_STATE, MODIFY_PHONE_STATE) {
            val receiver = ConfigChangedReceiver()
            context.registerReceiver(receiver, IntentFilter(ACTION_CARRIER_CONFIG_CHANGED))
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList
            subscriptions?.forEach { subInfo ->
                Log.d(TAG, "Overriding config for subscription $subInfo")
                carrierConfigManager.overrideConfig(subInfo.subscriptionId, bundle)
            }
            // Don't wait after each update before the next one, but expect all updates to be done
            // eventually
            subscriptions?.forEach { subInfo ->
                assertNotNull(receiver.receivedSubIds.poll(CONFIG_CHANGE_TIMEOUT_MS, pos = 0) {
                    it == subInfo.subscriptionId
                }, "Config override broadcast not received for subscription $subInfo")
            }
        }
    }

    private fun shouldDisableIwlan(): Boolean {
        // IWLAN on U 24Q2 release (U QPR3) causes cell data to reconnect when Wi-Fi is toggled due
        // to the implementation of the force_iwlan_mms feature, which does not work well with
        // multinetworking tests. Disable the feature on such builds (b/368477391).
        // The behavior changed in more recent releases (V) so only U 24Q2 is affected.
        return pm.hasSystemFeature(FEATURE_TELEPHONY_IMS) && pm.hasSystemFeature(FEATURE_WIFI) &&
                Build.VERSION.SDK_INT == UPSIDE_DOWN_CAKE &&
                isForceIwlanMmsEnabled()
    }

    private fun isForceIwlanMmsEnabled(): Boolean {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flagEnabledRegex = Regex(
            """telephony/com\.android\.internal\.telephony\.flags\.force_iwlan_mms:""" +
                    """.*ENABLED \(system\)""")
        ParcelFileDescriptor.AutoCloseInputStream(
            // If the command fails (for example if printflags is missing) this will return false
            // and the IWLAN disable will be skipped, which should be fine at it only helps with
            // flakiness.
            // This uses "sh -c" to cover that case as if "printflags" is used directly and the
            // binary is missing, the remote end will crash and the InputStream EOF is never
            // reached, so the read would hang.
            uiAutomation.executeShellCommand("sh -c printflags")).bufferedReader().use { reader ->
                return reader.lines().anyMatch {
                    it.contains(flagEnabledRegex)
                }
        }
    }
}