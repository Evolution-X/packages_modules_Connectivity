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

package android.net.thread;

import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;
import static android.net.thread.utils.IntegrationTestUtils.DEFAULT_CONFIG;
import static android.net.thread.utils.IntegrationTestUtils.DEFAULT_DATASET;

import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Integration tests for {@link ThreadNetworkShellCommand}. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class ThreadNetworkShellCommandTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);
    private final OtDaemonController mOtCtl = new OtDaemonController();
    private FullThreadDevice mFtd;

    @Before
    public void setUp() throws Exception {
        mController.leaveAndWait();

        mFtd = new FullThreadDevice(10 /* nodeId */);
        ensureThreadEnabled();
    }

    @After
    public void tearDown() throws Exception {
        mFtd.destroy();
        ensureThreadEnabled();
        mController.setConfigurationAndWait(DEFAULT_CONFIG);
    }

    private static void ensureThreadEnabled() {
        runThreadCommand("force-stop-ot-daemon disabled");
        runThreadCommand("enable");
    }

    private static void startFtdChild(FullThreadDevice ftd, ActiveOperationalDataset activeDataset)
            throws Exception {
        ftd.factoryReset();
        ftd.joinNetwork(activeDataset);
        ftd.waitForStateAnyOf(List.of("router", "child"), Duration.ofSeconds(8));
    }

    @Test
    public void enable_threadStateIsEnabled() throws Exception {
        runThreadCommand("enable");

        assertThat(mController.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void disable_threadStateIsDisabled() throws Exception {
        runThreadCommand("disable");

        assertThat(mController.getEnabledState()).isEqualTo(STATE_DISABLED);
    }

    @Test
    public void forceStopOtDaemon_forceStopEnabled_otDaemonServiceDisappear() {
        runThreadCommand("force-stop-ot-daemon enabled");

        assertThat(runShellCommandOrThrow("service list")).doesNotContain("ot_daemon");
    }

    @Test
    public void forceStopOtDaemon_forceStopEnabled_canNotEnableThread() throws Exception {
        runThreadCommand("force-stop-ot-daemon enabled");

        ExecutionException thrown =
                assertThrows(ExecutionException.class, () -> mController.setEnabledAndWait(true));
        ThreadNetworkException cause = (ThreadNetworkException) thrown.getCause();
        assertThat(cause.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void forceStopOtDaemon_forceStopDisabled_otDaemonServiceAppears() throws Exception {
        runThreadCommand("force-stop-ot-daemon disabled");

        assertThat(runShellCommandOrThrow("service list")).contains("ot_daemon");
    }

    @Test
    public void forceStopOtDaemon_forceStopDisabled_canEnableThread() throws Exception {
        runThreadCommand("force-stop-ot-daemon disabled");

        mController.setEnabledAndWait(true);
        assertThat(mController.getEnabledState()).isEqualTo(STATE_ENABLED);
    }

    @Test
    public void forceCountryCode_setCN_getCountryCodeReturnsCN() {
        assumeTrue(mOtCtl.isCountryCodeSupported());

        runThreadCommand("force-country-code enabled CN");

        final String result = runThreadCommand("get-country-code");
        assertThat(result).contains("Thread country code = CN");
    }

    @Test
    public void handleOtCtlCommand_enableIfconfig_getIfconfigReturnsUP() {
        runThreadCommand("ot-ctl ifconfig up");

        final String result = runThreadCommand("ot-ctl ifconfig");

        assertThat(result).isEqualTo("up\r\nDone\r\n");
    }

    @Test
    public void handleOtCtlCommand_disableIfconfig_startThreadFailsWithInvalidState() {
        runThreadCommand("ot-ctl ifconfig down");

        final String result = runThreadCommand("ot-ctl thread start");

        assertThat(result).isEqualTo("Error 13: InvalidState\r\n");
    }

    @Test
    @RequiresSimulationThreadDevice
    public void handleOtCtlCommand_pingFtd_getValidResponse() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);
        startFtdChild(mFtd, DEFAULT_DATASET);
        final Inet6Address ftdMlEid = mFtd.getMlEid();
        assertNotNull(ftdMlEid);

        final String result = runThreadCommand("ot-ctl ping " + ftdMlEid.getHostAddress());

        assertThat(result).contains("1 packets transmitted, 1 packets received");
        assertThat(result).contains("Packet loss = 0.0%");
        assertThat(result).endsWith("Done\r\n");
    }

    @Test
    public void config_getConfig_expectedValueIsPrinted() throws Exception {
        ThreadConfiguration config =
                new ThreadConfiguration.Builder().setNat64Enabled(true).build();
        mController.setConfigurationAndWait(config);

        final String result = runThreadCommand("config");

        assertThat(result).contains("nat64Enabled=true");
    }

    @Test
    public void config_setConfig_expectedValueIsSet() throws Exception {
        ThreadConfiguration config = new ThreadConfiguration.Builder().build();
        mController.setConfigurationAndWait(config);

        runThreadCommand("config nat64 enabled");

        assertThat(mController.getConfiguration().isNat64Enabled()).isTrue();
    }

    private static String runThreadCommand(String cmd) {
        return runShellCommandOrThrow("cmd thread_network " + cmd);
    }
}
