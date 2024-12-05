/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.testutils

import com.android.ddmlib.testrunner.TestResult
import com.android.tradefed.config.Option
import com.android.tradefed.invoker.ExecutionFiles.FilesKey
import com.android.tradefed.invoker.TestInformation
import com.android.tradefed.log.LogUtil.CLog
import com.android.tradefed.result.CollectingTestListener
import com.android.tradefed.result.ddmlib.DefaultRemoteAndroidTestRunner
import com.android.tradefed.targetprep.BaseTargetPreparer
import com.android.tradefed.targetprep.TargetSetupError
import com.android.tradefed.targetprep.suite.SuiteApkInstaller
import java.io.File

private const val CONNECTIVITY_CHECKER_APK = "ConnectivityTestPreparer.apk"
private const val CONNECTIVITY_PKG_NAME = "com.android.testutils.connectivitypreparer"
private const val CONNECTIVITY_CHECK_CLASS = "$CONNECTIVITY_PKG_NAME.ConnectivityCheckTest"
private const val CARRIER_CONFIG_SETUP_CLASS = "$CONNECTIVITY_PKG_NAME.CarrierConfigSetupTest"

// As per the <instrumentation> defined in the checker manifest
private const val CONNECTIVITY_CHECK_RUNNER_NAME = "androidx.test.runner.AndroidJUnitRunner"
private const val IGNORE_WIFI_CHECK = "ignore-wifi-check"
private const val IGNORE_MOBILE_DATA_CHECK = "ignore-mobile-data-check"

// The default updater package names, which might be updating packages while the CTS
// are running
private val UPDATER_PKGS = arrayOf("com.google.android.gms", "com.android.vending")

/**
 * A target preparer that sets up and verifies a device for connectivity tests.
 *
 * For quick and dirty local testing, the connectivity check can be disabled by running tests with
 * "atest -- \
 * --test-arg com.android.testutils.ConnectivityTestTargetPreparer:ignore-mobile-data-check:true". \
 * --test-arg com.android.testutils.ConnectivityTestTargetPreparer:ignore-wifi-check:true".
 */
open class ConnectivityTestTargetPreparer : BaseTargetPreparer() {
    private val installer = ApkInstaller()

    private class ApkInstaller : SuiteApkInstaller() {
        override fun getLocalPathForFilename(
            testInfo: TestInformation,
            apkFileName: String
        ): File {
            if (apkFileName == CONNECTIVITY_CHECKER_APK) {
                // For the connectivity checker APK, explicitly look for it in the directory of the
                // host-side preparer.
                // This preparer is part of the net-tests-utils-host-common library, which includes
                // the checker APK via device_common_data in its build rule. Both need to be at the
                // same version so that the preparer calls the right test methods in the checker
                // APK.
                // The default strategy for finding test files is to do a recursive search in test
                // directories, which may find wrong files in wrong directories. In particular,
                // if some MTS test includes the checker APK, and that test is linked to a module
                // that boards the train at a version different from this target preparer, there
                // could be a version difference between the APK and the host-side preparer.
                // Explicitly looking for the APK in the host-side preparer directory ensures that
                // it uses the version that was packaged together with the host-side preparer.
                val testsDir = testInfo.executionFiles().get(FilesKey.TESTS_DIRECTORY)
                val f = File(testsDir, "net-tests-utils-host-common/$CONNECTIVITY_CHECKER_APK")
                if (f.isFile) {
                    return f
                }
                // When running locally via atest, device_common_data does cause the APK to be put
                // into the test temp directory, so recursive search is still used to find it in the
                // directory of the test module that is being run. This is fine because atest runs
                // are on local trees that do not have versioning problems.
                CLog.i("APK not found at $f, falling back to recursive search")
            }
            return super.getLocalPathForFilename(testInfo, apkFileName)
        }
    }

    @Option(
        name = IGNORE_WIFI_CHECK,
            description = "Disables the check for wifi"
    )
    private var ignoreWifiCheck = false
    @Option(
        name = IGNORE_MOBILE_DATA_CHECK,
            description = "Disables the check for mobile data"
    )
    private var ignoreMobileDataCheck = false

    // The default value is never used, but false is a reasonable default
    private var originalTestChainEnabled = false
    private val originalUpdaterPkgsStatus = HashMap<String, Boolean>()

    override fun setUp(testInfo: TestInformation) {
        if (isDisabled) return
        disableGmsUpdate(testInfo)
        originalTestChainEnabled = getTestChainEnabled(testInfo)
        originalUpdaterPkgsStatus.putAll(getUpdaterPkgsStatus(testInfo))
        setUpdaterNetworkingEnabled(
            testInfo,
            enableChain = true,
            enablePkgs = UPDATER_PKGS.associateWith { false }
        )
        runConnectivityCheckApk(testInfo)
        refreshTime(testInfo)
    }

    private fun runConnectivityCheckApk(testInfo: TestInformation) {
        installer.setCleanApk(true)
        installer.addTestFileName(CONNECTIVITY_CHECKER_APK)
        installer.setShouldGrantPermission(true)
        installer.setUp(testInfo)

        val testMethods = mutableListOf<Pair<String, String>>()
        if (!ignoreWifiCheck) {
            testMethods.add(CONNECTIVITY_CHECK_CLASS to "testCheckWifiSetup")
        }
        if (!ignoreMobileDataCheck) {
            testMethods.add(CARRIER_CONFIG_SETUP_CLASS to "testSetCarrierConfig")
            testMethods.add(CONNECTIVITY_CHECK_CLASS to "testCheckTelephonySetup")
        }

        testMethods.forEach {
            runTestMethod(testInfo, it.first, it.second)
        }
    }

    private fun runTestMethod(testInfo: TestInformation, clazz: String, method: String) {
        val runner = DefaultRemoteAndroidTestRunner(
            CONNECTIVITY_PKG_NAME,
            CONNECTIVITY_CHECK_RUNNER_NAME,
            testInfo.device.iDevice
        )
        runner.runOptions = "--no-hidden-api-checks"
        runner.setMethodName(clazz, method)

        val receiver = CollectingTestListener()
        if (!testInfo.device.runInstrumentationTests(runner, receiver)) {
            throw TargetSetupError(
                "Device state check failed to complete",
                testInfo.device.deviceDescriptor
            )
        }

        val runResult = receiver.currentRunResults
        if (runResult.isRunFailure) {
            throw TargetSetupError(
                "Failed to check device state before the test: " +
                    runResult.runFailureMessage,
                testInfo.device.deviceDescriptor
            )
        }

        val errorMsg = runResult.testResults.mapNotNull { (testDescription, testResult) ->
            if (TestResult.TestStatus.FAILURE != testResult.status) {
                null
            } else {
                "$testDescription: ${testResult.stackTrace}"
            }
        }.joinToString("\n")
        if (errorMsg.isBlank()) return

        throw TargetSetupError(
            "Device setup checks failed. Check the test bench: \n$errorMsg",
            testInfo.device.deviceDescriptor
        )
    }

    private fun disableGmsUpdate(testInfo: TestInformation) {
        // This will be a no-op on devices without root (su) or not using gservices, but that's OK.
        testInfo.exec(
            "su 0 am broadcast " +
                "-a com.google.gservices.intent.action.GSERVICES_OVERRIDE " +
                "-e finsky.play_services_auto_update_enabled false"
        )
    }

    private fun clearGmsUpdateOverride(testInfo: TestInformation) {
        testInfo.exec(
            "su 0 am broadcast " +
                "-a com.google.gservices.intent.action.GSERVICES_OVERRIDE " +
                "--esn finsky.play_services_auto_update_enabled"
        )
    }

    private fun setUpdaterNetworkingEnabled(
            testInfo: TestInformation,
            enableChain: Boolean,
            enablePkgs: Map<String, Boolean>
    ) {
        // Build.VERSION_CODES.S = 31 where this is not available, then do nothing.
        if (testInfo.device.getApiLevel() < 31) return
        testInfo.exec("cmd connectivity set-chain3-enabled $enableChain")
        enablePkgs.forEach { (pkg, allow) ->
            testInfo.exec("cmd connectivity set-package-networking-enabled $allow $pkg")
        }
    }

    private fun getTestChainEnabled(testInfo: TestInformation) =
            testInfo.exec("cmd connectivity get-chain3-enabled").contains("chain:enabled")

    private fun getUpdaterPkgsStatus(testInfo: TestInformation) =
        UPDATER_PKGS.associateWith { pkg ->
            !testInfo.exec("cmd connectivity get-package-networking-enabled $pkg")
                .contains(":deny")
        }

    private fun refreshTime(testInfo: TestInformation) {
        // Forces a synchronous time refresh using the network. Time is fetched synchronously but
        // this does not guarantee that system time is updated when it returns.
        // This avoids flakes where the system clock rolls back, for example when using test
        // settings like test_url_expiration_time in NetworkMonitor.
        testInfo.exec("cmd network_time_update_service force_refresh")
    }

    override fun tearDown(testInfo: TestInformation, e: Throwable?) {
        if (isTearDownDisabled) return
        if (!ignoreMobileDataCheck) {
            runTestMethod(testInfo, CARRIER_CONFIG_SETUP_CLASS, "testClearCarrierConfig")
        }
        installer.tearDown(testInfo, e)
        setUpdaterNetworkingEnabled(
            testInfo,
            enableChain = originalTestChainEnabled,
            enablePkgs = originalUpdaterPkgsStatus
        )
        clearGmsUpdateOverride(testInfo)
    }
}
