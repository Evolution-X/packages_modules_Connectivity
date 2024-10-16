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

package com.android.testutils

import android.device.collectors.BaseMetricListener
import android.device.collectors.DataRecord
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.time.ZonedDateTime
import kotlin.test.assertNull
import org.junit.AssumptionViolatedException
import org.junit.runner.Description
import org.junit.runner.notification.Failure

/**
 * A diagnostics collector that outputs diagnostics files as test artifacts.
 *
 * <p>Collects diagnostics automatically by default on non-local builds. Can be enabled/disabled
 * manually with:
 * ```
 * atest MyModule -- \
 *     --module-arg MyModule:instrumentation-arg:connectivity-diagnostics-on-failure:=false
 * ```
 */
class ConnectivityDiagnosticsCollector : BaseMetricListener() {
    companion object {
        private const val ARG_RUN_ON_FAILURE = "connectivity-diagnostics-on-failure"
        private const val COLLECTOR_DIR = "run_listeners/connectivity_diagnostics"
        private const val FILENAME_SUFFIX = "_conndiag.txt"
        private const val MAX_DUMPS = 20

        private val TAG = ConnectivityDiagnosticsCollector::class.simpleName
        var instance: ConnectivityDiagnosticsCollector? = null
    }

    private val buffer = ByteArrayOutputStream()
    private val collectorDir: File by lazy {
        createAndEmptyDirectory(COLLECTOR_DIR)
    }
    private val outputFiles = mutableSetOf<String>()

    override fun onSetUp() {
        assertNull(instance, "ConnectivityDiagnosticsCollectors were set up multiple times")
        instance = this
        TryTestConfig.setDiagnosticsCollector { throwable ->
            if (runOnFailure(throwable)) {
                collectTestFailureDiagnostics(throwable)
            }
        }
    }

    override fun onCleanUp() {
        instance = null
    }

    override fun onTestFail(testData: DataRecord, description: Description, failure: Failure) {
        // TODO: find a way to disable this behavior only on local runs, to avoid slowing them down
        // when iterating on failing tests.
        if (!runOnFailure(failure.exception)) return
        if (outputFiles.size >= MAX_DUMPS) return
        Log.i(TAG, "Collecting diagnostics for test failure. Disable by running tests with: " +
                "atest MyModule -- " +
                "--module-arg MyModule:instrumentation-arg:$ARG_RUN_ON_FAILURE:=false")
        collectTestFailureDiagnostics(failure.exception)

        val baseFilename = "${description.className}#${description.methodName}_failure"
        flushBufferToFileMetric(testData, baseFilename)
    }

    override fun onTestEnd(testData: DataRecord, description: Description) {
        // Tests may call methods like collectDumpsysConnectivity to collect diagnostics at any time
        // during the run, for example to observe state at various points to investigate a flake
        // and compare passing/failing cases.
        // Flush the contents of the buffer to a file when the test ends, even when successful.
        if (buffer.size() == 0) return
        if (outputFiles.size >= MAX_DUMPS) return

        // Flush any data that the test added to the buffer for dumping
        val baseFilename = "${description.className}#${description.methodName}_testdump"
        flushBufferToFileMetric(testData, baseFilename)
    }

    private fun runOnFailure(exception: Throwable): Boolean {
        // Assumption failures (assumeTrue/assumeFalse) are not actual failures
        if (exception is AssumptionViolatedException) return false

        // Do not run on local builds (which have ro.build.version.incremental set to eng.username)
        // to avoid slowing down local runs.
        val enabledByDefault = !Build.VERSION.INCREMENTAL.startsWith("eng.")
        return argsBundle.getString(ARG_RUN_ON_FAILURE)?.toBooleanStrictOrNull() ?: enabledByDefault
    }

    private fun flushBufferToFileMetric(testData: DataRecord, baseFilename: String) {
        var filename = baseFilename
        // In case a method was run multiple times (typically retries), append a number
        var i = 2
        while (outputFiles.contains(filename)) {
            filename = baseFilename + "_$i"
            i++
        }
        val outFile = File(collectorDir, filename + FILENAME_SUFFIX)
        outputFiles.add(filename)
        outFile.writeBytes(buffer.toByteArray())
        buffer.reset()
        val fileKey = "${ConnectivityDiagnosticsCollector::class.qualifiedName}_$filename"
        testData.addFileMetric(fileKey, outFile)
    }

    /**
     * Add connectivity diagnostics to the test data dump.
     *
     * <p>This collects a set of diagnostics that are relevant to connectivity test failures.
     * <p>The dump will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectTestFailureDiagnostics(exceptionContext: Throwable? = null) {
        collectDumpsysConnectivity(exceptionContext)
    }

    /**
     * Add dumpsys connectivity to the test data dump.
     *
     * <p>The dump will be collected immediately, and exported to a test artifact file when the
     * test ends.
     * @param exceptionContext An exception to write a stacktrace to the dump for context.
     */
    fun collectDumpsysConnectivity(exceptionContext: Throwable? = null) {
        Log.i(TAG, "Collecting dumpsys connectivity for test artifacts")
        PrintWriter(buffer).let {
            it.println("--- Dumpsys connectivity at ${ZonedDateTime.now()} ---")
            maybeWriteExceptionContext(it, exceptionContext)
            it.flush()
        }
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "dumpsys connectivity --dump-priority HIGH")).use {
            it.copyTo(buffer)
        }
    }

    private fun maybeWriteExceptionContext(writer: PrintWriter, exceptionContext: Throwable?) {
        if (exceptionContext == null) return
        writer.println("At: ")
        exceptionContext.printStackTrace(writer)
    }
}