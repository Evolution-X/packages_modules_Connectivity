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

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_DETACHED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.utils.IntegrationTestUtils.CALLBACK_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.getIpv6LinkAddresses;
import static android.net.thread.utils.IntegrationTestUtils.getPrefixesFromNetData;
import static android.net.thread.utils.IntegrationTestUtils.getThreadNetwork;
import static android.net.thread.utils.IntegrationTestUtils.isInMulticastGroup;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;
import static android.net.thread.utils.ThreadNetworkControllerWrapper.JOIN_TIMEOUT;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;
import static com.android.server.thread.openthread.IOtDaemon.TUN_IF_NAME;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeTrue;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
import android.net.thread.utils.ThreadStateListener;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tests for E2E Android Thread integration with ot-daemon, ConnectivityService, etc.. */
@LargeTest
@RequiresThreadFeature
@RunWith(Parameterized.class)
public class ThreadIntegrationTest {
    // The byte[] buffer size for UDP tests
    private static final int UDP_BUFFER_SIZE = 1024;

    // The maximum time for OT addresses to be propagated to the TUN interface "thread-wpan"
    private static final Duration TUN_ADDR_UPDATE_TIMEOUT = Duration.ofSeconds(1);

    // The maximum time for changes to be propagated to netdata.
    private static final Duration NET_DATA_UPDATE_TIMEOUT = Duration.ofSeconds(1);

    // The maximum time for changes in netdata to be propagated to link properties.
    private static final Duration LINK_PROPERTIES_UPDATE_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration NETWORK_CALLBACK_TIMEOUT = Duration.ofSeconds(10);

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

    public final boolean mIsBorderRouterEnabled;
    private final ThreadConfiguration mConfig;

    @Parameterized.Parameters
    public static Collection configArguments() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    public ThreadIntegrationTest(boolean isBorderRouterEnabled) {
        mIsBorderRouterEnabled = isBorderRouterEnabled;
        mConfig =
                new ThreadConfiguration.Builder()
                        .setBorderRouterEnabled(isBorderRouterEnabled)
                        .build();
    }

    @Before
    public void setUp() throws Exception {
        mExecutor = Executors.newSingleThreadExecutor();
        mOtCtl = new OtDaemonController();
        mController.setEnabledAndWait(true);
        mController.setConfigurationAndWait(mConfig);
        mController.leaveAndWait();
        mFtd = new FullThreadDevice(10 /* nodeId */);
    }

    @After
    public void tearDown() throws Exception {
        ThreadStateListener.stopAllListeners();

        mController.setTestNetworkAsUpstreamAndWait(null);
        mController.leaveAndWait();

        mFtd.destroy();
        mExecutor.shutdownNow();
    }

    @Test
    public void otDaemonRestart_notJoinedAndStopped_deviceRoleIsStopped() throws Exception {
        mController.leaveAndWait();

        runShellCommand("stop ot-daemon");
        // TODO(b/323331973): the sleep is needed to workaround the race conditions
        SystemClock.sleep(200);

        mController.waitForRole(DEVICE_ROLE_STOPPED, CALLBACK_TIMEOUT);
    }

    @Test
    public void otDaemonRestart_JoinedNetworkAndStopped_autoRejoinedAndTunIfStateConsistent()
            throws Exception {
        assumeTrue(mController.getConfiguration().isBorderRouterEnabled());
        mController.joinAndWait(DEFAULT_DATASET);

        runShellCommand("stop ot-daemon");

        mController.waitForRole(DEVICE_ROLE_DETACHED, CALLBACK_TIMEOUT);
        mController.waitForRole(DEVICE_ROLE_LEADER, RESTART_JOIN_TIMEOUT);
        assertThat(mOtCtl.isInterfaceUp()).isTrue();
        assertThat(runShellCommand("ifconfig thread-wpan")).contains("UP POINTOPOINT RUNNING");
    }

    @Test
    public void otDaemonFactoryReset_deviceRoleIsStopped() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        mOtCtl.factoryReset();

        assertThat(mController.getDeviceRole()).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void otDaemonFactoryReset_addressesRemoved() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        mOtCtl.factoryReset();

        String ifconfig = runShellCommand("ifconfig thread-wpan");
        assertThat(ifconfig).doesNotContain("inet6 addr");
    }

    // TODO (b/323300829): add test for removing an OT address
    @Test
    public void tunInterface_joinedNetwork_otAndTunAddressesMatch() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        List<Inet6Address> otAddresses = mOtCtl.getAddresses();
        assertThat(otAddresses).isNotEmpty();
        // TODO: it's cleaner to have a retry() method to retry failed asserts in given delay so
        // that we can write assertThat() in the Predicate
        waitFor(
                () -> {
                    List<Inet6Address> tunAddresses =
                            getIpv6LinkAddresses("thread-wpan").stream()
                                    .map(linkAddr -> (Inet6Address) linkAddr.getAddress())
                                    .toList();
                    return otAddresses.containsAll(tunAddresses)
                            && tunAddresses.containsAll(otAddresses);
                },
                TUN_ADDR_UPDATE_TIMEOUT);
    }

    @Test
    public void otDaemonRestart_latestCountryCodeIsSetToOtDaemon() throws Exception {
        assumeTrue(mOtCtl.isCountryCodeSupported());

        runThreadCommand("force-country-code enabled CN");

        runShellCommand("stop ot-daemon");
        // TODO(b/323331973): the sleep is needed to workaround the race conditions
        SystemClock.sleep(200);
        mController.waitForRole(DEVICE_ROLE_STOPPED, CALLBACK_TIMEOUT);

        assertThat(mOtCtl.getCountryCode()).isEqualTo("CN");
    }

    @Test
    @RequiresSimulationThreadDevice
    public void udp_appStartEchoServer_endDeviceUdpEchoSuccess() throws Exception {
        // Topology:
        //   Test App ------ thread-wpan ------ End Device

        mController.joinAndWait(DEFAULT_DATASET);
        startFtdChild(mFtd, DEFAULT_DATASET);
        final Inet6Address serverAddress = mOtCtl.getMeshLocalAddresses().get(0);
        final int serverPort = 9527;

        mExecutor.execute(() -> startUdpEchoServerAndWait(serverAddress, serverPort));
        mFtd.udpOpen();
        mFtd.udpSend("Hello,Thread", serverAddress, serverPort);
        String udpReply = mFtd.udpReceive();

        assertThat(udpReply).isEqualTo("Hello,Thread");
    }

    @Test
    public void joinNetworkWithBrDisabled_meshLocalAddressesArePreferred() throws Exception {
        // When BR feature is disabled, there is no OMR address, so the mesh-local addresses are
        // expected to be preferred.
        mOtCtl.executeCommand("br disable");
        mController.joinAndWait(DEFAULT_DATASET);

        IpPrefix meshLocalPrefix = DEFAULT_DATASET.getMeshLocalPrefix();
        List<LinkAddress> linkAddresses = getIpv6LinkAddresses("thread-wpan");
        for (LinkAddress address : linkAddresses) {
            if (meshLocalPrefix.contains(address.getAddress())) {
                assertThat(address.getDeprecationTime())
                        .isGreaterThan(SystemClock.elapsedRealtime());
                assertThat(address.isPreferred()).isTrue();
            }
        }

        mOtCtl.executeCommand("br enable");
    }

    @Test
    public void joinNetwork_tunInterfaceJoinsAllRouterMulticastGroup() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);

        assertTunInterfaceMemberOfGroup(GROUP_ADDR_ALL_ROUTERS);
    }

    @Test
    public void joinNetwork_joinTheSameNetworkTwice_neverDetached() throws Exception {
        mController.joinAndWait(DEFAULT_DATASET);
        mController.waitForRole(DEVICE_ROLE_LEADER, JOIN_TIMEOUT);

        var listener = ThreadStateListener.startListener(mController.get());
        mController.joinAndWait(DEFAULT_DATASET);

        assertThat(
                        listener.pollForAnyRoleOf(
                                List.of(DEVICE_ROLE_DETACHED, DEVICE_ROLE_STOPPED), JOIN_TIMEOUT))
                .isNull();
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

    @Test
    @RequiresSimulationThreadDevice
    public void setConfiguration_disableBorderRouter_borderRoutingDisabled() throws Exception {
        startFtdLeader(mFtd, DEFAULT_DATASET);

        mController.setConfigurationAndWait(
                new ThreadConfiguration.Builder().setBorderRouterEnabled(false).build());
        mController.joinAndWait(DEFAULT_DATASET);

        assertThat(mOtCtl.getBorderRoutingState()).ignoringCase().isEqualTo("disabled");
        // TODO: b/376217403 - enables / disables Border Agent at runtime
    }

    private NetworkCapabilities registerNetworkCallbackAndWait(NetworkRequest request)
            throws Exception {
        CompletableFuture<Network> networkFuture = new CompletableFuture<>();
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        networkFuture.complete(network);
                    }
                };

        runAsShell(ACCESS_NETWORK_STATE, () -> cm.registerNetworkCallback(request, callback));

        assertThat(networkFuture.get(NETWORK_CALLBACK_TIMEOUT.getSeconds(), SECONDS)).isNotNull();
        return runAsShell(
                ACCESS_NETWORK_STATE, () -> cm.getNetworkCapabilities(networkFuture.get()));
    }

    // TODO (b/323300829): add more tests for integration with linux platform and
    // ConnectivityService

    private static String runThreadCommand(String cmd) {
        return runShellCommandOrThrow("cmd thread_network " + cmd);
    }

    private void startFtdChild(FullThreadDevice ftd, ActiveOperationalDataset activeDataset)
            throws Exception {
        ftd.factoryReset();
        ftd.joinNetwork(activeDataset);
        ftd.waitForStateAnyOf(List.of("router", "child"), Duration.ofSeconds(8));
    }

    /** Starts a Thread FTD device as a leader. */
    private void startFtdLeader(FullThreadDevice ftd, ActiveOperationalDataset activeDataset)
            throws Exception {
        ftd.factoryReset();
        ftd.joinNetwork(activeDataset);
        ftd.waitForStateAnyOf(List.of("leader"), Duration.ofSeconds(8));
    }

    /**
     * Starts a UDP echo server and replies to the first UDP message.
     *
     * <p>This method exits when the first UDP message is received and the reply is sent
     */
    private void startUdpEchoServerAndWait(InetAddress serverAddress, int serverPort) {
        try (var udpServerSocket = new DatagramSocket(serverPort, serverAddress)) {
            DatagramPacket recvPacket =
                    new DatagramPacket(new byte[UDP_BUFFER_SIZE], UDP_BUFFER_SIZE);
            udpServerSocket.receive(recvPacket);
            byte[] sendBuffer = Arrays.copyOf(recvPacket.getData(), recvPacket.getData().length);
            udpServerSocket.send(
                    new DatagramPacket(
                            sendBuffer,
                            sendBuffer.length,
                            (Inet6Address) recvPacket.getAddress(),
                            recvPacket.getPort()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertTunInterfaceMemberOfGroup(Inet6Address address) throws Exception {
        waitFor(() -> isInMulticastGroup(TUN_IF_NAME, address), TUN_ADDR_UPDATE_TIMEOUT);
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
