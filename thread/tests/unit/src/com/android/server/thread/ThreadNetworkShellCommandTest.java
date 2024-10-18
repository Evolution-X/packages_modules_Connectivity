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

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.IConfigurationReceiver;
import android.net.thread.IOperationReceiver;
import android.net.thread.IOutputReceiver;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadConfiguration;
import android.os.Binder;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Unit tests for {@link ThreadNetworkShellCommand}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ThreadNetworkShellCommandTest {
    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset new":
    // Active Timestamp: 1
    // Channel: 19
    // Channel Mask: 0x07FFF800
    // Ext PAN ID: ACC214689BC40BDF
    // Mesh Local Prefix: fd64:db12:25f4:7e0b::/64
    // Network Key: F26B3153760F519A63BAFDDFFC80D2AF
    // Network Name: OpenThread-d9a0
    // PAN ID: 0xD9A0
    // PSKc: A245479C836D551B9CA557F7B9D351B4
    // Security Policy: 672 onrcb
    private static final String DEFAULT_ACTIVE_DATASET_TLVS =
            "0E080000000000010000000300001335060004001FFFE002"
                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                    + "B9D351B40C0402A0FFF8";

    @Mock private ThreadNetworkControllerService mControllerService;
    @Mock private ThreadNetworkCountryCode mCountryCode;
    @Mock private PrintWriter mErrorWriter;
    @Mock private PrintWriter mOutputWriter;

    private Context mContext;
    private ThreadNetworkShellCommand mShellCommand;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = spy(ApplicationProvider.getApplicationContext());
        doNothing()
                .when(mContext)
                .enforceCallingOrSelfPermission(
                        eq("android.permission.THREAD_NETWORK_TESTING"), anyString());

        mShellCommand = new ThreadNetworkShellCommand(mContext, mControllerService, mCountryCode);
        mShellCommand.setPrintWriters(mOutputWriter, mErrorWriter);

        // by default emulate shell uid.
        BinderUtil.setUid(Process.SHELL_UID);
    }

    @After
    public void tearDown() throws Exception {
        validateMockitoUsage();
    }

    private void runShellCommand(String... args) {
        mShellCommand.exec(
                new Binder(),
                new FileDescriptor(),
                new FileDescriptor(),
                new FileDescriptor(),
                args);
    }

    @Test
    public void getCountryCode_testingPermissionIsChecked() {
        when(mCountryCode.getCountryCode()).thenReturn("US");

        runShellCommand("get-country-code");

        verify(mContext, times(1))
                .enforceCallingOrSelfPermission(
                        eq("android.permission.THREAD_NETWORK_TESTING"), anyString());
    }

    @Test
    public void getCountryCode_currentCountryCodePrinted() {
        when(mCountryCode.getCountryCode()).thenReturn("US");

        runShellCommand("get-country-code");

        verify(mOutputWriter).println(contains("US"));
    }

    @Test
    public void forceSetCountryCodeEnabled_testingPermissionIsChecked() {
        runShellCommand("force-country-code", "enabled", "US");

        verify(mContext, times(1))
                .enforceCallingOrSelfPermission(
                        eq("android.permission.THREAD_NETWORK_TESTING"), anyString());
    }

    @Test
    public void forceSetCountryCodeEnabled_countryCodeIsOverridden() {
        runShellCommand("force-country-code", "enabled", "US");

        verify(mCountryCode).setOverrideCountryCode(eq("US"));
    }

    @Test
    public void forceSetCountryCodeDisabled_overriddenCountryCodeIsCleared() {
        runShellCommand("force-country-code", "disabled");

        verify(mCountryCode).clearOverrideCountryCode();
    }

    @Test
    public void forceStopOtDaemon_testingPermissionIsChecked() {
        runShellCommand("force-stop-ot-daemon", "enabled");

        verify(mContext, times(1))
                .enforceCallingOrSelfPermission(
                        eq("android.permission.THREAD_NETWORK_TESTING"), anyString());
    }

    @Test
    public void forceStopOtDaemon_serviceThrows_failed() {
        doThrow(new SecurityException(""))
                .when(mControllerService)
                .forceStopOtDaemonForTest(eq(true), any());

        runShellCommand("force-stop-ot-daemon", "enabled");

        verify(mControllerService, times(1)).forceStopOtDaemonForTest(eq(true), any());
        verify(mOutputWriter, never()).println();
    }

    @Test
    public void forceStopOtDaemon_serviceApiTimeout_failedWithTimeoutError() {
        doNothing().when(mControllerService).forceStopOtDaemonForTest(eq(true), any());

        runShellCommand("force-stop-ot-daemon", "enabled");

        verify(mControllerService, times(1)).forceStopOtDaemonForTest(eq(true), any());
        verify(mErrorWriter, atLeastOnce()).println(contains("timeout"));
        verify(mOutputWriter, never()).println();
    }

    @Test
    public void join_controllerServiceJoinIsCalled() {
        doNothing().when(mControllerService).join(any(), any());

        runShellCommand("join", DEFAULT_ACTIVE_DATASET_TLVS);

        var activeDataset =
                ActiveOperationalDataset.fromThreadTlvs(
                        base16().decode(DEFAULT_ACTIVE_DATASET_TLVS));
        verify(mControllerService, times(1)).join(eq(activeDataset), any());
        verify(mErrorWriter, never()).println();
    }

    @Test
    public void join_invalidDataset_controllerServiceJoinIsNotCalled() {
        doNothing().when(mControllerService).join(any(), any());

        runShellCommand("join", "000102");

        verify(mControllerService, never()).join(any(), any());
        verify(mErrorWriter, times(1)).println(contains("Invalid dataset argument"));
    }

    @Test
    public void migrate_controllerServiceMigrateIsCalled() {
        doNothing().when(mControllerService).scheduleMigration(any(), any());

        runShellCommand("migrate", DEFAULT_ACTIVE_DATASET_TLVS, "300");

        ArgumentCaptor<PendingOperationalDataset> captor =
                ArgumentCaptor.forClass(PendingOperationalDataset.class);
        verify(mControllerService, times(1)).scheduleMigration(captor.capture(), any());
        assertThat(captor.getValue().getActiveOperationalDataset())
                .isEqualTo(
                        ActiveOperationalDataset.fromThreadTlvs(
                                base16().decode(DEFAULT_ACTIVE_DATASET_TLVS)));
        assertThat(captor.getValue().getDelayTimer().toSeconds()).isEqualTo(300);
        verify(mErrorWriter, never()).println();
    }

    @Test
    public void migrate_invalidDataset_controllerServiceMigrateIsNotCalled() {
        doNothing().when(mControllerService).scheduleMigration(any(), any());

        runShellCommand("migrate", "000102", "300");

        verify(mControllerService, never()).scheduleMigration(any(), any());
        verify(mErrorWriter, times(1)).println(contains("Invalid dataset argument"));
    }

    @Test
    public void leave_controllerServiceLeaveIsCalled() {
        doNothing().when(mControllerService).leave(any());

        runShellCommand("leave");

        verify(mControllerService, times(1)).leave(any());
        verify(mErrorWriter, never()).println();
    }

    @Test
    public void handleOtCtlCommand_testingPermissionIsChecked() {
        BinderUtil.setUid(Process.ROOT_UID);
        doAnswer(
                        invocation -> {
                            IOutputReceiver receiver = invocation.getArgument(1);
                            receiver.onComplete();
                            return null;
                        })
                .when(mControllerService)
                .runOtCtlCommand(anyString(), anyBoolean(), any());

        runShellCommand("ot-ctl", "state");

        verify(mContext, times(1))
                .enforceCallingOrSelfPermission(
                        eq("android.permission.THREAD_NETWORK_TESTING"), anyString());
    }

    @Test
    public void handleOtCtlCommand_failsWithNonRootProcess() {
        runShellCommand("ot-ctl", "state");

        verify(mErrorWriter, times(1)).println(contains("No access to ot-ctl command"));
        verify(mOutputWriter, never()).println();
    }

    @Test
    public void handleOtCtlCommand_nonInteractive_serviceTimeout_failsWithTimeoutError() {
        BinderUtil.setUid(Process.ROOT_UID);
        doNothing().when(mControllerService).runOtCtlCommand(anyString(), eq(false), any());

        runShellCommand("ot-ctl", "state");

        verify(mControllerService, times(1)).runOtCtlCommand(anyString(), eq(false), any());
        verify(mErrorWriter, atLeastOnce()).println(contains("timeout"));
        verify(mOutputWriter, never()).println();
    }

    @Test
    public void handleOtCtlCommand_nonInteractive_state_outputIsPrinted() {
        BinderUtil.setUid(Process.ROOT_UID);
        doAnswer(
                        invocation -> {
                            IOutputReceiver receiver = invocation.getArgument(2);

                            receiver.onOutput("leader");
                            receiver.onOutput("\r\n");
                            receiver.onOutput("Done");
                            receiver.onOutput("\r\n");

                            receiver.onComplete();
                            return null;
                        })
                .when(mControllerService)
                .runOtCtlCommand(eq("state"), eq(false), any());

        runShellCommand("ot-ctl", "state");

        InOrder inOrder = inOrder(mOutputWriter);
        inOrder.verify(mOutputWriter).print("leader");
        inOrder.verify(mOutputWriter).print("\r\n");
        inOrder.verify(mOutputWriter).print("Done");
        inOrder.verify(mOutputWriter).print("\r\n");
    }

    @Test
    public void config_getConfig_testingPermissionIsChecked() {
        runShellCommand("config");

        verify(mContext, times(1))
                .enforceCallingOrSelfPermission(
                        eq("android.permission.THREAD_NETWORK_TESTING"), anyString());
    }

    @Test
    public void config_getConfig_serviceTimeOut_failsWithTimeoutError() {
        runShellCommand("config");

        verify(mControllerService, times(1)).registerConfigurationCallback(any());
        verify(mErrorWriter, atLeastOnce()).println(contains("timeout"));
        verify(mOutputWriter, never()).println();
    }

    @Test
    public void config_getConfig_expectedValueIsPrinted() {
        doAnswer(
                        inv -> {
                            ((IConfigurationReceiver) inv.getArgument(0))
                                    .onConfigurationChanged(
                                            new ThreadConfiguration.Builder()
                                                    .setNat64Enabled(true)
                                                    .build());
                            return null;
                        })
                .when(mControllerService)
                .registerConfigurationCallback(any());

        runShellCommand("config");

        verify(mErrorWriter, never()).println();
        verify(mOutputWriter, times(1)).println(contains("Nat64Enabled=true"));
    }

    @Test
    public void config_setConfig_testingPermissionIsChecked() {
        runShellCommand("config", "nat64", "enabled");

        verify(mContext, times(1))
                .enforceCallingOrSelfPermission(
                        eq("android.permission.THREAD_NETWORK_TESTING"), anyString());
    }

    @Test
    public void config_setConfig_serviceTimeOut_failedWithTimeoutError() {
        runShellCommand("config", "nat64", "enabled");

        verify(mControllerService, times(1)).registerConfigurationCallback(any());
        verify(mErrorWriter, atLeastOnce()).println(contains("timeout"));
        verify(mOutputWriter, never()).println();
    }

    @Test
    public void config_invalidArgument_failsWithInvalidArgumentError() {
        doAnswer(
                        inv -> {
                            ((IConfigurationReceiver) inv.getArgument(0))
                                    .onConfigurationChanged(
                                            new ThreadConfiguration.Builder().build());
                            return null;
                        })
                .when(mControllerService)
                .registerConfigurationCallback(any());

        runShellCommand("config", "invalidName", "invalidValue");

        verify(mErrorWriter, atLeastOnce()).println(contains("Invalid config"));
        verify(mOutputWriter, never()).println();
    }

    @Test
    public void config_setConfig_expectedValueIsSet() {
        doAnswer(
                        inv -> {
                            ((IConfigurationReceiver) inv.getArgument(0))
                                    .onConfigurationChanged(
                                            new ThreadConfiguration.Builder()
                                                    .setNat64Enabled(false)
                                                    .build());
                            return null;
                        })
                .when(mControllerService)
                .registerConfigurationCallback(any());
        doAnswer(
                        inv -> {
                            ((IOperationReceiver) inv.getArgument(0)).onSuccess();
                            return null;
                        })
                .when(mControllerService)
                .setConfiguration(any(), any());

        runShellCommand("config", "nat64", "enabled");

        verify(mControllerService, times(1))
                .setConfiguration(
                        eq(new ThreadConfiguration.Builder().setNat64Enabled(true).build()), any());
        verify(mErrorWriter, never()).println();
        verify(mOutputWriter, never()).println();
    }
}
