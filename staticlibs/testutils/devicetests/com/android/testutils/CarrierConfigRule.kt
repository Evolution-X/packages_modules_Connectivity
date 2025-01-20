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
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel.isAtLeastU
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import kotlin.test.assertNotNull
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

private val TAG = CarrierConfigRule::class.simpleName

/**
 * A [TestRule] that helps set [CarrierConfigManager] overrides for tests and clean up the test
 * configuration automatically on teardown.
 */
class CarrierConfigRule : TestRule {
    private val ccm by lazy { InstrumentationRegistry.getInstrumentation().context.getSystemService(
        CarrierConfigManager::class.java
    ) }

    // Map of (subId) -> (original values of overridden settings)
    private val originalConfigs = mutableMapOf<Int, PersistableBundle>()

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
            ccm.overrideConfig(subId, config)
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
                    // Do not use overrideConfig with null, as it would reset configs that may
                    // have been set by target preparers such as
                    // ConnectivityTestTargetPreparer / CarrierConfigSetupTest.
                    ccm.overrideConfig(subId, config)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error resetting carrier config for subId $subId")
                }
            }
            originalConfigs.clear()
        }
    }
}

private fun CarrierConfigManager.getConfigForSubIdCompat(
    subId: Int,
    keys: Set<String>
): PersistableBundle {
    return if (isAtLeastU()) {
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
