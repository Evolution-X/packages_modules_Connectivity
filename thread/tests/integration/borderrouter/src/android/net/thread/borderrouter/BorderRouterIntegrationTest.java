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

package android.net.thread;

import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.utils.IntegrationTestUtils.CALLBACK_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.getIpv6Addresses;
import static android.net.thread.utils.IntegrationTestUtils.getIpv6LinkAddresses;
import static android.net.thread.utils.IntegrationTestUtils.getPrefixesFromNetData;
import static android.net.thread.utils.IntegrationTestUtils.getThreadNetwork;
import static android.net.thread.utils.IntegrationTestUtils.isInMulticastGroup;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;
import static android.os.SystemClock.elapsedRealtime;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.TapTestNetworkTracker;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
import android.net.thread.utils.ThreadStateListener;
import android.os.HandlerThread;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.FluentIterable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tests for E2E Border Router integration with ot-daemon, ConnectivityService, etc.. */
@LargeTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public class BorderRouterIntegrationTest {
    // The maximum time for changes to be propagated to netdata.
    private static final Duration NET_DATA_UPDATE_TIMEOUT = Duration.ofSeconds(1);

    // The maximum time for OT addresses to be propagated to the TUN interface "thread-wpan"
    private static final Duration TUN_ADDR_UPDATE_TIMEOUT = Duration.ofSeconds(1);

    // The maximum time for changes in netdata to be propagated to link properties.
    private static final Duration LINK_PROPERTIES_UPDATE_TIMEOUT = Duration.ofSeconds(1);

    // The duration between attached and OMR address shows up on thread-wpan
    private static final Duration OMR_LINK_ADDR_TIMEOUT = Duration.ofSeconds(30);

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset init new".
    private static final byte[] DEFAULT_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS);

    private static final Inet6Address GROUP_ADDR_ALL_ROUTERS =
            (Inet6Address) InetAddresses.parseNumericAddress("ff02::2");

    private static final String TEST_NO_SLAAC_PREFIX = "9101:dead:beef:cafe::/64";
    private static final InetAddress TEST_NO_SLAAC_PREFIX_ADDRESS =
            InetAddresses.parseNumericAddress("9101:dead:beef:cafe::");

    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private ExecutorService mExecutor;
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);
    private OtDaemonController mOtCtl;
    private FullThreadDevice mFtd;
    private HandlerThread mHandlerThread;
    private TapTestNetworkTracker mTestNetworkTracker;

    @Before
    public void setUp() throws Exception {
        mExecutor = Executors.newSingleThreadExecutor();
        mFtd = new FullThreadDevice(10 /* nodeId */);
        mOtCtl = new OtDaemonController();
        mController.setEnabledAndWait(true);
        mController.setConfigurationAndWait(
                new ThreadConfiguration.Builder().setBorderRouterEnabled(true).build());
        mController.leaveAndWait();

        mHandlerThread = new HandlerThread("ThreadIntegrationTest");
        mHandlerThread.start();

        mTestNetworkTracker = new TapTestNetworkTracker(mContext, mHandlerThread.getLooper());
        assertThat(mTestNetworkTracker).isNotNull();
        mController.setTestNetworkAsUpstreamAndWait(mTestNetworkTracker.getInterfaceName());
    }

    @After
    public void tearDown() throws Exception {
        ThreadStateListener.stopAllListeners();

        if (mTestNetworkTracker != null) {
            mTestNetworkTracker.tearDown();
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread.join();
        }
        mController.setTestNetworkAsUpstreamAndWait(null);
        mController.leaveAndWait();

        mFtd.destroy();
        mExecutor.shutdownNow();
    }

    @Test
    public void otDaemonRestart_JoinedNetworkAndStopped_autoRejoinedAndTunIfStateConsistent()
            throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        runShellCommand("stop ot-daemon");

        mController.waitForRole(DEVICE_ROLE_DETACHED, CALLBACK_TIMEOUT);
        mController.waitForRole(DEVICE_ROLE_LEADER, RESTART_JOIN_TIMEOUT);
        assertThat(mOtCtl.isInterfaceUp()).isTrue();
        assertThat(runShellCommand("ifconfig thread-wpan")).contains("UP POINTOPOINT RUNNING");
    }

    @Test
    public void joinNetwork_tunInterfaceJoinsAllRouterMulticastGroup() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        waitFor(
                () -> isInMulticastGroup("thread-wpan", GROUP_ADDR_ALL_ROUTERS),
                TUN_ADDR_UPDATE_TIMEOUT);
    }

    @Test
    public void joinNetwork_allMlAddrAreNotPreferredAndOmrIsPreferred() throws Exception {
        mController.setTestNetworkAsUpstreamAndWait(mTestNetworkTracker.getInterfaceName());
        mController.joinAndWait(DEFAULT_DATASET);
        waitFor(
                () -> getIpv6Addresses("thread-wpan").contains(mOtCtl.getOmrAddress()),
                OMR_LINK_ADDR_TIMEOUT);

        IpPrefix meshLocalPrefix = DEFAULT_DATASET.getMeshLocalPrefix();
        var linkAddrs = FluentIterable.from(getIpv6LinkAddresses("thread-wpan"));
        var meshLocalAddrs = linkAddrs.filter(addr -> meshLocalPrefix.contains(addr.getAddress()));
        assertThat(meshLocalAddrs).isNotEmpty();
        assertThat(meshLocalAddrs.allMatch(addr -> !addr.isPreferred())).isTrue();
        assertThat(meshLocalAddrs.allMatch(addr -> addr.getDeprecationTime() <= elapsedRealtime()))
                .isTrue();
        var omrAddrs = linkAddrs.filter(addr -> addr.getAddress().equals(mOtCtl.getOmrAddress()));
        assertThat(omrAddrs).hasSize(1);
        assertThat(omrAddrs.get(0).isPreferred()).isTrue();
        assertThat(omrAddrs.get(0).getDeprecationTime() > elapsedRealtime()).isTrue();
    }

    @Test
    @RequiresSimulationThreadDevice
    public void edPingsMeshLocalAddresses_oneReplyPerRequest() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);
        startFtdChild(mFtd, DEFAULT_DATASET);
        List<Inet6Address> meshLocalAddresses = mOtCtl.getMeshLocalAddresses();

        for (Inet6Address address : meshLocalAddresses) {
            assertWithMessage(
                            "There may be duplicated replies of ping request to "
                                    + address.getHostAddress())
                    .that(mFtd.ping(address, 2))
                    .isEqualTo(2);
        }
    }

    @Test
    public void addPrefixToNetData_routeIsAddedToTunInterface() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        // Ftd child doesn't have the ability to add a prefix, so let BR itself add a prefix.
        mOtCtl.executeCommand("prefix add " + TEST_NO_SLAAC_PREFIX + " pros med");
        mOtCtl.executeCommand("netdata register");
        waitFor(
                () -> {
                    String netData = mOtCtl.executeCommand("netdata show");
                    return getPrefixesFromNetData(netData).contains(TEST_NO_SLAAC_PREFIX);
                },
                NET_DATA_UPDATE_TIMEOUT);

        assertRouteAddedOrRemovedInLinkProperties(true /* isAdded */, TEST_NO_SLAAC_PREFIX_ADDRESS);
    }

    @Test
    public void removePrefixFromNetData_routeIsRemovedFromTunInterface() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);
        mOtCtl.executeCommand("prefix add " + TEST_NO_SLAAC_PREFIX + " pros med");
        mOtCtl.executeCommand("netdata register");

        mOtCtl.executeCommand("prefix remove " + TEST_NO_SLAAC_PREFIX);
        mOtCtl.executeCommand("netdata register");
        waitFor(
                () -> {
                    String netData = mOtCtl.executeCommand("netdata show");
                    return !getPrefixesFromNetData(netData).contains(TEST_NO_SLAAC_PREFIX);
                },
                NET_DATA_UPDATE_TIMEOUT);

        assertRouteAddedOrRemovedInLinkProperties(
                false /* isAdded */, TEST_NO_SLAAC_PREFIX_ADDRESS);
    }

    @Test
    public void toggleThreadNetwork_routeFromPreviousNetDataIsRemoved() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);
        mOtCtl.executeCommand("prefix add " + TEST_NO_SLAAC_PREFIX + " pros med");
        mOtCtl.executeCommand("netdata register");

        mController.leaveAndWait();
        mController.joinAndWait(DEFAULT_DATASET);

        assertRouteAddedOrRemovedInLinkProperties(
                false /* isAdded */, TEST_NO_SLAAC_PREFIX_ADDRESS);
    }

    private void startFtdChild(FullThreadDevice ftd, ActiveOperationalDataset activeDataset)
            throws Exception {
        ftd.factoryReset();
        ftd.joinNetwork(activeDataset);
        ftd.waitForStateAnyOf(List.of("router", "child"), Duration.ofSeconds(8));
    }

    private void assertRouteAddedOrRemovedInLinkProperties(boolean isAdded, InetAddress addr)
            throws Exception {
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);

        waitFor(
                () -> {
                    try {
                        LinkProperties lp =
                                cm.getLinkProperties(getThreadNetwork(CALLBACK_TIMEOUT));
                        return lp != null
                                && isAdded
                                        == lp.getRoutes().stream().anyMatch(r -> r.matches(addr));
                    } catch (Exception e) {
                        return false;
                    }
                },
                LINK_PROPERTIES_UPDATE_TIMEOUT);
    }
}
