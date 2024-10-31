/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import android.annotation.Nullable;
import android.content.Context;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.IConfigurationReceiver;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOutputReceiver;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadConfiguration;
import android.net.thread.ThreadNetworkException;
import android.os.Binder;
import android.os.Process;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BasicShellCommandHandler;
import com.android.net.module.util.HexDump;

import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Interprets and executes 'adb shell cmd thread_network <subcommand>'.
 *
 * <p>Subcommands which don't have an equivalent Java API now require the
 * "android.permission.THREAD_NETWORK_TESTING" permission. For a specific subcommand, it also
 * requires the same permissions of the equivalent Java / AIDL API.
 *
 * <p>To add new commands: - onCommand: Add a case "<command>" execute. Return a 0 if command
 * executed successfully. - onHelp: add a description string.
 */
public final class ThreadNetworkShellCommand extends BasicShellCommandHandler {
    private static final Duration SET_ENABLED_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration LEAVE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration MIGRATE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FORCE_STOP_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration OT_CTL_COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONFIG_TIMEOUT = Duration.ofSeconds(1);
    private static final String PERMISSION_THREAD_NETWORK_TESTING =
            "android.permission.THREAD_NETWORK_TESTING";

    private final Context mContext;
    private final ThreadNetworkControllerService mControllerService;
    private final ThreadNetworkCountryCode mCountryCode;

    @Nullable private PrintWriter mOutputWriter;
    @Nullable private PrintWriter mErrorWriter;

    @VisibleForTesting
    ThreadNetworkShellCommand(
            Context context,
            ThreadNetworkControllerService controllerService,
            ThreadNetworkCountryCode countryCode) {
        mContext = context;
        mControllerService = controllerService;
        mCountryCode = countryCode;
    }

    @VisibleForTesting
    public void setPrintWriters(PrintWriter outputWriter, PrintWriter errorWriter) {
        mOutputWriter = outputWriter;
        mErrorWriter = errorWriter;
    }

    private static boolean isRootProcess() {
        return Binder.getCallingUid() == Process.ROOT_UID;
    }

    private PrintWriter getOutputWriter() {
        return (mOutputWriter != null) ? mOutputWriter : getOutPrintWriter();
    }

    private PrintWriter getErrorWriter() {
        return (mErrorWriter != null) ? mErrorWriter : getErrPrintWriter();
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutputWriter();
        pw.println("Thread network commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        pw.println("  enable");
        pw.println("    Enables Thread radio");
        pw.println("  disable");
        pw.println("    Disables Thread radio");
        pw.println("  join <active-dataset-tlvs>");
        pw.println("    Joins a network of the given dataset");
        pw.println("  migrate <active-dataset-tlvs> <delay-seconds>");
        pw.println("    Migrate to the given network by a specific delay");
        pw.println("  leave");
        pw.println("    Leave the current network and erase datasets");
        pw.println("  force-stop-ot-daemon enabled | disabled ");
        pw.println("    force stop ot-daemon service");
        pw.println("  get-country-code");
        pw.println("    Gets country code as a two-letter string");
        pw.println("  force-country-code enabled <two-letter code> | disabled ");
        pw.println("    Sets country code to <two-letter code> or left for normal value");
        pw.println("  ot-ctl <subcommand>");
        pw.println("    Runs ot-ctl command");
        pw.println("  config [name] [value]");
        pw.println("    Gets the config or sets the value for a config entry");
    }

    @Override
    public int onCommand(String cmd) {
        // Treat no command as the "help" command
        if (TextUtils.isEmpty(cmd)) {
            cmd = "help";
        }

        switch (cmd) {
            case "enable":
                return setThreadEnabled(true);
            case "disable":
                return setThreadEnabled(false);
            case "config":
                return handleConfigCommand();
            case "join":
                return join();
            case "leave":
                return leave();
            case "migrate":
                return migrate();
            case "force-stop-ot-daemon":
                return forceStopOtDaemon();
            case "force-country-code":
                return forceCountryCode();
            case "get-country-code":
                return getCountryCode();
            case "ot-ctl":
                return handleOtCtlCommand();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private void ensureTestingPermission() {
        mContext.enforceCallingOrSelfPermission(
                PERMISSION_THREAD_NETWORK_TESTING,
                "Permission " + PERMISSION_THREAD_NETWORK_TESTING + " is missing!");
    }

    private int setThreadEnabled(boolean enabled) {
        CompletableFuture<Void> setEnabledFuture = new CompletableFuture<>();
        mControllerService.setEnabled(enabled, newOperationReceiver(setEnabledFuture));
        return waitForFuture(setEnabledFuture, SET_ENABLED_TIMEOUT, getErrorWriter());
    }

    private int join() {
        byte[] datasetTlvs = HexDump.hexStringToByteArray(getNextArgRequired());
        ActiveOperationalDataset dataset;
        try {
            dataset = ActiveOperationalDataset.fromThreadTlvs(datasetTlvs);
        } catch (IllegalArgumentException e) {
            getErrorWriter().println("Invalid dataset argument: " + e.getMessage());
            return -1;
        }
        // Do not wait for join to complete because this can take 8 to 30 seconds
        mControllerService.join(dataset, new IOperationReceiver.Default());
        return 0;
    }

    private int leave() {
        CompletableFuture<Void> leaveFuture = new CompletableFuture<>();
        mControllerService.leave(newOperationReceiver(leaveFuture));
        return waitForFuture(leaveFuture, LEAVE_TIMEOUT, getErrorWriter());
    }

    private int migrate() {
        byte[] datasetTlvs = HexDump.hexStringToByteArray(getNextArgRequired());
        ActiveOperationalDataset dataset;
        try {
            dataset = ActiveOperationalDataset.fromThreadTlvs(datasetTlvs);
        } catch (IllegalArgumentException e) {
            getErrorWriter().println("Invalid dataset argument: " + e.getMessage());
            return -1;
        }

        int delaySeconds;
        try {
            delaySeconds = Integer.parseInt(getNextArgRequired());
        } catch (NumberFormatException e) {
            getErrorWriter().println("Invalid delay argument: " + e.getMessage());
            return -1;
        }

        PendingOperationalDataset pendingDataset =
                new PendingOperationalDataset(
                        dataset,
                        OperationalDatasetTimestamp.fromInstant(Instant.now()),
                        Duration.ofSeconds(delaySeconds));
        CompletableFuture<Void> migrateFuture = new CompletableFuture<>();
        mControllerService.scheduleMigration(pendingDataset, newOperationReceiver(migrateFuture));
        return waitForFuture(migrateFuture, MIGRATE_TIMEOUT, getErrorWriter());
    }

    private int forceStopOtDaemon() {
        ensureTestingPermission();
        final PrintWriter errorWriter = getErrorWriter();
        boolean enabled;
        try {
            enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
        } catch (IllegalArgumentException e) {
            errorWriter.println("Invalid argument: " + e.getMessage());
            return -1;
        }

        CompletableFuture<Void> forceStopFuture = new CompletableFuture<>();
        mControllerService.forceStopOtDaemonForTest(enabled, newOperationReceiver(forceStopFuture));
        return waitForFuture(forceStopFuture, FORCE_STOP_TIMEOUT, getErrorWriter());
    }

    private int forceCountryCode() {
        ensureTestingPermission();
        final PrintWriter perr = getErrorWriter();
        boolean enabled;
        try {
            enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
        } catch (IllegalArgumentException e) {
            perr.println("Invalid argument: " + e.getMessage());
            return -1;
        }

        if (enabled) {
            String countryCode = getNextArgRequired();
            if (!ThreadNetworkCountryCode.isValidCountryCode(countryCode)) {
                perr.println(
                        "Invalid argument: Country code must be a 2-letter"
                                + " string. But got country code "
                                + countryCode
                                + " instead");
                return -1;
            }
            mCountryCode.setOverrideCountryCode(countryCode);
        } else {
            mCountryCode.clearOverrideCountryCode();
        }
        return 0;
    }

    private int getCountryCode() {
        ensureTestingPermission();
        getOutputWriter().println("Thread country code = " + mCountryCode.getCountryCode());
        return 0;
    }

    private int handleConfigCommand() {
        ensureTestingPermission();

        // Get config
        if (peekNextArg() == null) {
            try {
                final ThreadConfiguration config = getConfig();
                getOutputWriter().println("Thread configuration = " + config);
            } catch (AssertionError e) {
                getErrorWriter().println("Failed: " + e.getMessage());
                return -1;
            }
            return 0;
        }

        // Set config
        final String name = getNextArg();
        final String value = getNextArg();
        try {
            setConfig(name, value);
        } catch (AssertionError | IllegalArgumentException e) {
            getErrorWriter().println(e.getMessage());
            return -1;
        }
        return 0;
    }

    private ThreadConfiguration getConfig() throws AssertionError {
        final CompletableFuture<ThreadConfiguration> future = new CompletableFuture<>();
        mControllerService.registerConfigurationCallback(
                new IConfigurationReceiver.Stub() {
                    @Override
                    public void onConfigurationChanged(ThreadConfiguration config) {
                        future.complete(config);
                    }
                });
        try {
            return future.get(CONFIG_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Failed to get config within timeout", e);
        }
    }

    private void setConfig(String name, String value)
            throws IllegalArgumentException, AssertionError {
        if (name == null || value == null) {
            throw new IllegalArgumentException(
                    "Invalid config name = " + name + ", value=" + value);
        }
        final ThreadConfiguration oldConfig = getConfig();
        final ThreadConfiguration.Builder newConfigBuilder =
                new ThreadConfiguration.Builder(oldConfig);
        switch (name) {
            case "br" -> newConfigBuilder.setBorderRouterEnabled(argEnabledOrDisabled(value));
            case "nat64" -> newConfigBuilder.setNat64Enabled(argEnabledOrDisabled(value));
            case "pd" -> newConfigBuilder.setDhcpv6PdEnabled(argEnabledOrDisabled(value));
            default -> throw new IllegalArgumentException("Invalid config name: " + name);
        }
        CompletableFuture<Void> future = new CompletableFuture();
        mControllerService.setConfiguration(newConfigBuilder.build(), newOperationReceiver(future));
        waitForFuture(future, CONFIG_TIMEOUT, mErrorWriter);
    }

    private static final class OutputReceiver extends IOutputReceiver.Stub {
        private final CompletableFuture<Void> future;
        private final PrintWriter outputWriter;

        public OutputReceiver(CompletableFuture<Void> future, PrintWriter outputWriter) {
            this.future = future;
            this.outputWriter = outputWriter;
        }

        @Override
        public void onOutput(String output) {
            outputWriter.print(output);
            outputWriter.flush();
        }

        @Override
        public void onComplete() {
            future.complete(null);
        }

        @Override
        public void onError(int errorCode, String errorMessage) {
            future.completeExceptionally(new ThreadNetworkException(errorCode, errorMessage));
        }
    }

    private int handleOtCtlCommand() {
        ensureTestingPermission();

        if (!isRootProcess()) {
            getErrorWriter().println("No access to ot-ctl command");
            return -1;
        }

        final String subCommand = String.join(" ", peekRemainingArgs());

        CompletableFuture<Void> completeFuture = new CompletableFuture<>();
        mControllerService.runOtCtlCommand(
                subCommand,
                false /* isInteractive */,
                new OutputReceiver(completeFuture, getOutputWriter()));
        return waitForFuture(completeFuture, OT_CTL_COMMAND_TIMEOUT, getErrorWriter());
    }

    private static IOperationReceiver newOperationReceiver(CompletableFuture<Void> future) {
        return new IOperationReceiver.Stub() {
            @Override
            public void onSuccess() {
                future.complete(null);
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                future.completeExceptionally(new ThreadNetworkException(errorCode, errorMessage));
            }
        };
    }

    /**
     * Waits for the future to complete within given timeout.
     *
     * <p>Returns 0 if {@code future} completed successfully, or -1 if {@code future} failed to
     * complete. When failed, error messages are printed to {@code errorWriter}.
     */
    private int waitForFuture(
            CompletableFuture<Void> future, Duration timeout, PrintWriter errorWriter) {
        try {
            future.get(timeout.toSeconds(), TimeUnit.SECONDS);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorWriter.println("Failed: " + e.getMessage());
        } catch (ExecutionException e) {
            errorWriter.println("Failed: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            errorWriter.println("Failed: command timeout for " + timeout);
        }

        return -1;
    }

    private static boolean argTrueOrFalse(String arg, String trueString, String falseString) {
        if (trueString.equals(arg)) {
            return true;
        } else if (falseString.equals(arg)) {
            return false;
        } else {
            throw new IllegalArgumentException(
                    "Expected '"
                            + trueString
                            + "' or '"
                            + falseString
                            + "' as next arg but got '"
                            + arg
                            + "'");
        }
    }

    private static boolean argEnabledOrDisabled(String arg) {
        return argTrueOrFalse(arg, "enabled", "disabled");
    }

    private boolean getNextArgRequiredTrueOrFalse(String trueString, String falseString) {
        String nextArg = getNextArgRequired();
        return argTrueOrFalse(nextArg, trueString, falseString);
    }
}
