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

package android.net.thread;

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.thread.utils.IntegrationTestUtils.DEFAULT_DATASET;
import static android.net.thread.utils.IntegrationTestUtils.buildIcmpv4EchoReply;
import static android.net.thread.utils.IntegrationTestUtils.getIpv6LinkAddresses;
import static android.net.thread.utils.IntegrationTestUtils.isExpectedIcmpv4Packet;
import static android.net.thread.utils.IntegrationTestUtils.isExpectedIcmpv6Packet;
import static android.net.thread.utils.IntegrationTestUtils.isFrom;
import static android.net.thread.utils.IntegrationTestUtils.isInMulticastGroup;
import static android.net.thread.utils.IntegrationTestUtils.isTo;
import static android.net.thread.utils.IntegrationTestUtils.joinNetworkAndWaitForOmr;
import static android.net.thread.utils.IntegrationTestUtils.newPacketReader;
import static android.net.thread.utils.IntegrationTestUtils.pollForPacket;
import static android.net.thread.utils.IntegrationTestUtils.sendUdpMessage;
import static android.net.thread.utils.IntegrationTestUtils.stopOtDaemon;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;
import static android.system.OsConstants.ICMP_ECHO;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.InfraNetworkDevice;
import android.net.thread.utils.IntegrationTestUtils;
import android.net.thread.utils.OtDaemonController;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresIpv6MulticastRouting;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.net.thread.utils.ThreadNetworkControllerWrapper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.PollPacketReader;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Integration test cases for Thread Border Routing feature. */
@RunWith(AndroidJUnit4.class)
@RequiresThreadFeature
@RequiresSimulationThreadDevice
@LargeTest
public class BorderRoutingTest {
    private static final String TAG = BorderRoutingTest.class.getSimpleName();
    private static final int NUM_FTD = 2;
    private static final Inet6Address GROUP_ADDR_SCOPE_5 =
            (Inet6Address) parseNumericAddress("ff05::1234");
    private static final Inet6Address GROUP_ADDR_SCOPE_4 =
            (Inet6Address) parseNumericAddress("ff04::1234");
    private static final Inet6Address GROUP_ADDR_SCOPE_3 =
            (Inet6Address) parseNumericAddress("ff03::1234");
    private static final Inet4Address IPV4_SERVER_ADDR =
            (Inet4Address) parseNumericAddress("8.8.8.8");
    private static final IpPrefix DHCP6_PD_PREFIX = new IpPrefix("2001:db8::/64");
    private static final IpPrefix AIL_NAT64_PREFIX = new IpPrefix("2001:db8:1234::/96");
    private static final Inet6Address AIL_NAT64_SYNTHESIZED_SERVER_ADDR =
            (Inet6Address) parseNumericAddress("2001:db8:1234::8.8.8.8");
    private static final Duration UPDATE_NAT64_PREFIX_TIMEOUT = Duration.ofSeconds(10);

    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkControllerWrapper mController =
            ThreadNetworkControllerWrapper.newInstance(mContext);
    private OtDaemonController mOtCtl;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TestNetworkTracker mInfraNetworkTracker;
    private List<FullThreadDevice> mFtds;
    private PollPacketReader mInfraNetworkReader;
    private InfraNetworkDevice mInfraDevice;

    @Before
    public void setUp() throws Exception {
        // TODO: b/323301831 - This is a workaround to avoid unnecessary delay to re-form a network
        mOtCtl = new OtDaemonController();
        mOtCtl.factoryReset();

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mFtds = new ArrayList<>();

        setUpInfraNetwork();
        mController.setEnabledAndWait(true);
        mController.joinAndWait(DEFAULT_DATASET);

        // Creates a infra network device.
        mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        startInfraDeviceAndWaitForOnLinkAddr();

        // Create Ftds
        for (int i = 0; i < NUM_FTD; ++i) {
            mFtds.add(new FullThreadDevice(15 + i /* node ID */));
        }
    }

    @After
    public void tearDown() throws Exception {
        mController.setTestNetworkAsUpstreamAndWait(null);
        mController.leaveAndWait();
        tearDownInfraNetwork();

        mHandlerThread.quitSafely();
        mHandlerThread.join();

        for (var ftd : mFtds) {
            ftd.destroy();
        }
        mFtds.clear();
    }

    @Test
    public void unicastRouting_infraDevicePingThreadDeviceOmr_replyReceived() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);

        mInfraDevice.sendEchoRequest(ftd.getOmrAddress());

        // Infra device receives an echo reply sent by FTD.
        assertNotNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    public void unicastRouting_afterFactoryResetInfraDevicePingThreadDeviceOmr_replyReceived()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        startInfraDeviceAndWaitForOnLinkAddr();
        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);

        mInfraDevice.sendEchoRequest(ftd.getOmrAddress());

        assertNotNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    public void unicastRouting_afterInfraNetworkSwitchInfraDevicePingThreadDeviceOmr_replyReceived()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        Inet6Address ftdOmr = ftd.getOmrAddress();
        // Create a new infra network and let Thread prefer it
        TestNetworkTracker oldInfraNetworkTracker = mInfraNetworkTracker;
        try {
            setUpInfraNetwork();
            mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
            startInfraDeviceAndWaitForOnLinkAddr();

            mInfraDevice.sendEchoRequest(ftd.getOmrAddress());

            assertNotNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftdOmr));
        } finally {
            runAsShell(MANAGE_TEST_NETWORKS, () -> oldInfraNetworkTracker.teardown());
        }
    }

    @Test
    public void unicastRouting_borderRouterSendsUdpToThreadDevice_datagramReceived()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                   Thread
         * Border Router -------------- Full Thread device
         *  (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        Inet6Address ftdOmr = requireNonNull(ftd.getOmrAddress());
        Inet6Address ftdMlEid = requireNonNull(ftd.getMlEid());

        ftd.udpBind(ftdOmr, 12345);
        sendUdpMessage(ftdOmr, 12345, "aaaaaaaa");
        assertEquals("aaaaaaaa", ftd.udpReceive());

        ftd.udpBind(ftdMlEid, 12345);
        sendUdpMessage(ftdMlEid, 12345, "bbbbbbbb");
        assertEquals("bbbbbbbb", ftd.udpReceive());
    }

    @Test
    public void unicastRouting_meshLocalAddressesAreNotPreferred() throws Exception {
        // When BR is enabled, there will be OMR address, so the mesh-local addresses are expected
        // to be deprecated.
        List<LinkAddress> linkAddresses = getIpv6LinkAddresses("thread-wpan");
        IpPrefix meshLocalPrefix = DEFAULT_DATASET.getMeshLocalPrefix();

        for (LinkAddress address : linkAddresses) {
            if (meshLocalPrefix.contains(address.getAddress())) {
                assertThat(address.getDeprecationTime()).isAtMost(SystemClock.elapsedRealtime());
                assertThat(address.isPreferred()).isFalse();
            }
        }
    }

    @Test
    public void unicastRouting_otDaemonRestarts_borderRoutingWorks() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);

        stopOtDaemon();
        ftd.waitForStateAnyOf(List.of("leader", "router", "child"), Duration.ofSeconds(40));

        startInfraDeviceAndWaitForOnLinkAddr();
        mInfraDevice.sendEchoRequest(ftd.getOmrAddress());
        assertNotNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_ftdSubscribedMulticastAddress_infraLinkJoinsMulticastGroup()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);

        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_5);

        assertInfraLinkMemberOfGroup(GROUP_ADDR_SCOPE_5);
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void
            multicastRouting_ftdSubscribedScope3MulticastAddress_infraLinkNotJoinMulticastGroup()
                    throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);

        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_3);

        assertInfraLinkNotMemberOfGroup(GROUP_ADDR_SCOPE_3);
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_ftdSubscribedMulticastAddress_canPingfromInfraLink()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        subscribeMulticastAddressAndWait(ftd, GROUP_ADDR_SCOPE_5);

        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        assertNotNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_inboundForwarding_afterBrRejoinFtdRepliesSubscribedAddress()
            throws Exception {

        // TODO (b/327311034): Testing bbr state switch from primary mode to secondary mode and back
        // to primary mode requires an additional BR in the Thread network. This is not currently
        // supported, to be implemented when possible.
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_ftdSubscribedScope3MulticastAddress_cannotPingfromInfraLink()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_3);

        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_3);

        assertNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_ftdNotSubscribedMulticastAddress_cannotPingFromInfraDevice()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);

        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_4);

        assertNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_multipleFtdsSubscribedDifferentAddresses_canPingFromInfraDevice()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device 1
         *                                   (Cuttlefish)
         *                                         |
         *                                         | Thread
         *                                         |
         *                                  Full Thread device 2
         * </pre>
         */

        FullThreadDevice ftd1 = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd1, DEFAULT_DATASET);
        subscribeMulticastAddressAndWait(ftd1, GROUP_ADDR_SCOPE_5);

        FullThreadDevice ftd2 = mFtds.get(1);
        joinNetworkAndWaitForOmr(ftd2, DEFAULT_DATASET);
        subscribeMulticastAddressAndWait(ftd2, GROUP_ADDR_SCOPE_4);

        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd1.getOmrAddress()));

        // Verify ping reply from ftd1 and ftd2 separately as the order of replies can't be
        // predicted.
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_4);

        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd2.getOmrAddress()));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_multipleFtdsSubscribedSameAddress_canPingFromInfraDevice()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device 1
         *                                   (Cuttlefish)
         *                                         |
         *                                         | Thread
         *                                         |
         *                                  Full Thread device 2
         * </pre>
         */

        FullThreadDevice ftd1 = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd1, DEFAULT_DATASET);
        subscribeMulticastAddressAndWait(ftd1, GROUP_ADDR_SCOPE_5);

        FullThreadDevice ftd2 = mFtds.get(1);
        joinNetworkAndWaitForOmr(ftd2, DEFAULT_DATASET);
        subscribeMulticastAddressAndWait(ftd2, GROUP_ADDR_SCOPE_5);

        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd1.getOmrAddress()));

        // Send the request twice as the order of replies from ftd1 and ftd2 are not guaranteed
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftd2.getOmrAddress()));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_outboundForwarding_scopeLargerThan3IsForwarded() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        Inet6Address ftdOmr = ftd.getOmrAddress();

        ftd.ping(GROUP_ADDR_SCOPE_5);
        ftd.ping(GROUP_ADDR_SCOPE_4);

        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(
                        ICMPV6_ECHO_REQUEST_TYPE, ftdOmr, GROUP_ADDR_SCOPE_5));
        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(
                        ICMPV6_ECHO_REQUEST_TYPE, ftdOmr, GROUP_ADDR_SCOPE_4));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_outboundForwarding_scopeSmallerThan4IsNotForwarded()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);

        ftd.ping(GROUP_ADDR_SCOPE_3);

        assertNull(
                pollForIcmpPacketOnInfraNetwork(
                        ICMPV6_ECHO_REQUEST_TYPE, ftd.getOmrAddress(), GROUP_ADDR_SCOPE_3));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_outboundForwarding_llaToScope4IsNotForwarded() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        Inet6Address ftdLla = ftd.getLinkLocalAddress();
        assertNotNull(ftdLla);

        ftd.ping(GROUP_ADDR_SCOPE_4, ftdLla);

        assertNull(
                pollForIcmpPacketOnInfraNetwork(
                        ICMPV6_ECHO_REQUEST_TYPE, ftdLla, GROUP_ADDR_SCOPE_4));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_outboundForwarding_mlaToScope4IsNotForwarded() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        List<Inet6Address> ftdMlas = ftd.getMeshLocalAddresses();
        assertFalse(ftdMlas.isEmpty());

        for (Inet6Address ftdMla : ftdMlas) {
            ftd.ping(GROUP_ADDR_SCOPE_4, ftdMla);

            assertNull(
                    pollForIcmpPacketOnInfraNetwork(
                            ICMPV6_ECHO_REQUEST_TYPE, ftdMla, GROUP_ADDR_SCOPE_4));
        }
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_infraNetworkSwitch_ftdRepliesToSubscribedAddress()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        subscribeMulticastAddressAndWait(ftd, GROUP_ADDR_SCOPE_5);
        Inet6Address ftdOmr = ftd.getOmrAddress();

        // Destroy infra link and re-create
        tearDownInfraNetwork();
        setUpInfraNetwork();
        mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        startInfraDeviceAndWaitForOnLinkAddr();

        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        assertNotNull(pollForIcmpPacketOnInfraNetwork(ICMPV6_ECHO_REPLY_TYPE, ftdOmr));
    }

    @Test
    @RequiresIpv6MulticastRouting
    public void multicastRouting_infraNetworkSwitch_outboundPacketIsForwarded() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        Inet6Address ftdOmr = ftd.getOmrAddress();

        // Destroy infra link and re-create
        tearDownInfraNetwork();
        setUpInfraNetwork();
        mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        startInfraDeviceAndWaitForOnLinkAddr();

        ftd.ping(GROUP_ADDR_SCOPE_4);

        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(
                        ICMPV6_ECHO_REQUEST_TYPE, ftdOmr, GROUP_ADDR_SCOPE_4));
    }

    @Test
    public void nat64_threadDevicePingIpv4InfraDevice_outboundPacketIsForwardedAndReplyIsReceived()
            throws Exception {
        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        mController.setNat64EnabledAndWait(true);
        waitFor(() -> mOtCtl.hasNat64PrefixInNetdata(), UPDATE_NAT64_PREFIX_TIMEOUT);
        Thread echoReplyThread = new Thread(() -> respondToEchoRequestOnce(IPV4_SERVER_ADDR));
        echoReplyThread.start();

        assertThat(ftd.ping(IPV4_SERVER_ADDR, 1 /* count */)).isEqualTo(1);

        echoReplyThread.join();
    }

    private void respondToEchoRequestOnce(Inet4Address dstAddress) {
        byte[] echoRequest = pollForIcmpPacketOnInfraNetwork(ICMP_ECHO, null, dstAddress);
        assertNotNull(echoRequest);
        try {
            mInfraNetworkReader.sendResponse(buildIcmpv4EchoReply(ByteBuffer.wrap(echoRequest)));
        } catch (IOException ignored) {
        }
    }

    @Ignore("TODO: b/376573921 - Enable when it's not flaky at all")
    @Test
    public void nat64_withAilNat64Prefix_threadDevicePingIpv4InfraDevice_outboundPacketIsForwarded()
            throws Exception {
        tearDownInfraNetwork();
        LinkProperties lp = new LinkProperties();
        // NAT64 feature requires the infra network to have an IPv4 default route.
        lp.addRoute(
                new RouteInfo(
                        new IpPrefix("0.0.0.0/0") /* destination */,
                        null /* gateway */,
                        null /* iface */,
                        RouteInfo.RTN_UNICAST,
                        1500 /* mtu */));
        lp.addRoute(
                new RouteInfo(
                        new IpPrefix("::/0") /* destination */,
                        null /* gateway */,
                        null /* iface */,
                        RouteInfo.RTN_UNICAST,
                        1500 /* mtu */));
        lp.setNat64Prefix(AIL_NAT64_PREFIX);
        mInfraNetworkTracker = IntegrationTestUtils.setUpInfraNetwork(mContext, mController, lp);
        mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        FullThreadDevice ftd = mFtds.get(0);
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET);
        mController.setNat64EnabledAndWait(true);
        mOtCtl.addPrefixInNetworkData(DHCP6_PD_PREFIX, "paros", "med");
        waitFor(() -> mOtCtl.hasNat64PrefixInNetdata(), UPDATE_NAT64_PREFIX_TIMEOUT);

        ftd.ping(IPV4_SERVER_ADDR);

        assertNotNull(
                pollForIcmpPacketOnInfraNetwork(
                        ICMPV6_ECHO_REQUEST_TYPE, null, AIL_NAT64_SYNTHESIZED_SERVER_ADDR));
    }

    private void setUpInfraNetwork() throws Exception {
        mInfraNetworkTracker = IntegrationTestUtils.setUpInfraNetwork(mContext, mController);
    }

    private void tearDownInfraNetwork() {
        IntegrationTestUtils.tearDownInfraNetwork(mInfraNetworkTracker);
    }

    private void startInfraDeviceAndWaitForOnLinkAddr() {
        mInfraDevice =
                IntegrationTestUtils.startInfraDeviceAndWaitForOnLinkAddr(mInfraNetworkReader);
    }

    private void assertInfraLinkMemberOfGroup(Inet6Address address) throws Exception {
        waitFor(
                () ->
                        isInMulticastGroup(
                                mInfraNetworkTracker.getTestIface().getInterfaceName(), address),
                Duration.ofSeconds(3));
    }

    private void assertInfraLinkNotMemberOfGroup(Inet6Address address) throws Exception {
        waitFor(
                () ->
                        !isInMulticastGroup(
                                mInfraNetworkTracker.getTestIface().getInterfaceName(), address),
                Duration.ofSeconds(3));
    }

    private void subscribeMulticastAddressAndWait(FullThreadDevice ftd, Inet6Address address)
            throws Exception {
        ftd.subscribeMulticastAddress(address);

        assertInfraLinkMemberOfGroup(address);
    }

    private byte[] pollForIcmpPacketOnInfraNetwork(int type, InetAddress srcAddress) {
        return pollForIcmpPacketOnInfraNetwork(type, srcAddress, null /* destAddress */);
    }

    private byte[] pollForIcmpPacketOnInfraNetwork(
            int type, InetAddress srcAddress, InetAddress destAddress) {
        if (srcAddress == null && destAddress == null) {
            throw new IllegalArgumentException("srcAddress and destAddress cannot be both null");
        }
        if (srcAddress != null && destAddress != null) {
            if ((srcAddress instanceof Inet4Address) != (destAddress instanceof Inet4Address)) {
                throw new IllegalArgumentException(
                        "srcAddress and destAddress must be both IPv4 or both IPv6");
            }
        }
        boolean isIpv4 =
                (srcAddress instanceof Inet4Address) || (destAddress instanceof Inet4Address);
        final Predicate<byte[]> filter =
                p ->
                        (isIpv4 ? isExpectedIcmpv4Packet(p, type) : isExpectedIcmpv6Packet(p, type))
                                && (srcAddress == null || isFrom(p, srcAddress))
                                && (destAddress == null || isTo(p, destAddress));
        return pollForPacket(mInfraNetworkReader, filter);
    }
}
