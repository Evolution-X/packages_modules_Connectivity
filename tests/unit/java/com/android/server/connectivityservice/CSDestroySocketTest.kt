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

package com.android.server

import android.app.ActivityManager.UidFrozenStateChangedCallback
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_FROZEN
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_UNFROZEN
import android.net.ConnectivityManager.BLOCKED_REASON_APP_BACKGROUND
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.ConnectivityManager.FIREWALL_CHAIN_BACKGROUND
import android.net.ConnectivityManager.FIREWALL_RULE_ALLOW
import android.net.ConnectivityManager.FIREWALL_RULE_DENY
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import com.android.net.module.util.BaseNetdUnsolicitedEventListener
import com.android.server.connectivity.ConnectivityFlags.DELAY_DESTROY_SOCKETS
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkAgent.Event.OnNetworkDestroyed
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import java.net.Inet6Address
import java.net.InetAddress
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

private const val TIMESTAMP = 1234L
private const val TEST_UID = 1234
private const val TEST_UID2 = 5678
private const val TEST_CELL_IFACE = "test_rmnet"

private fun cellNc() = makeNc(TRANSPORT_CELLULAR)

private fun makeNc(transportType: Int) = nc(transportType, NET_CAPABILITY_INTERNET)

private fun cellLp() = makeLp(TEST_CELL_IFACE)

private fun makeLp(interfaceName: String) = LinkProperties().also{
    it.interfaceName = interfaceName
}

private fun makeRequest(transportType: Int) = NetworkRequest.Builder()
    .clearCapabilities()
    .addTransportType(transportType)
    .build()

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSDestroySocketTest : CSTest() {
    private fun getRegisteredNetdUnsolicitedEventListener(): BaseNetdUnsolicitedEventListener {
        val captor = ArgumentCaptor.forClass(BaseNetdUnsolicitedEventListener::class.java)
        verify(netd).registerUnsolicitedEventListener(captor.capture())
        return captor.value
    }

    private fun getUidFrozenStateChangedCallback(): UidFrozenStateChangedCallback {
        val captor = ArgumentCaptor.forClass(UidFrozenStateChangedCallback::class.java)
        verify(activityManager).registerUidFrozenStateChangedCallback(any(), captor.capture())
        return captor.value
    }

    private fun doTestBackgroundRestrictionDestroySockets(
            restrictionWithIdleNetwork: Boolean,
            expectDelay: Boolean
    ) {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val inOrder = inOrder(destroySocketsWrapper)

        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        if (restrictionWithIdleNetwork) {
            // Make cell default network idle
            netdEventListener.onInterfaceClassActivityChanged(
                    false, // isActive
                    cellAgent.network.netId,
                    TIMESTAMP,
                    TEST_UID
            )
        }

        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_DENY
        )
        waitForIdle()
        if (expectDelay) {
            inOrder.verify(destroySocketsWrapper, never())
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            inOrder.verify(destroySocketsWrapper)
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }

        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()
        if (expectDelay) {
            inOrder.verify(destroySocketsWrapper)
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            inOrder.verify(destroySocketsWrapper, never())
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }

        cellAgent.disconnect()
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, true)])
    fun testBackgroundAppDestroySockets() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = true,
                expectDelay = true
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, true)])
    fun testBackgroundAppDestroySockets_activeNetwork() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = false,
                expectDelay = false
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, false)])
    fun testBackgroundAppDestroySockets_featureIsDisabled() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = true,
                expectDelay = false
        )
    }

    @Test
    fun testReplaceFirewallChain() {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()

        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        // Make cell default network idle
        netdEventListener.onInterfaceClassActivityChanged(
                false, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )

        // Set allow rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_ALLOW
        )
        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID2)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID2,
                FIREWALL_RULE_DENY
        )

        // Put only TEST_UID2 on background chain (deny TEST_UID and allow TEST_UID2)
        doReturn(setOf(TEST_UID))
                .`when`(bpfNetMaps).getUidsWithAllowRuleOnAllowListChain(FIREWALL_CHAIN_BACKGROUND)
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID2)
        cm.replaceFirewallChain(FIREWALL_CHAIN_BACKGROUND, intArrayOf(TEST_UID2))
        waitForIdle()
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        verify(quicConnectionCloser, never()).closeQuicConnectionByUids(setOf(TEST_UID))

        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()
        verify(destroySocketsWrapper).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        verify(quicConnectionCloser).closeQuicConnectionByUids(setOf(TEST_UID))

        cellAgent.disconnect()
    }

    private fun doTestDestroySockets(
            isFrozen: Boolean,
            denyOnBackgroundChain: Boolean,
            enableBackgroundChain: Boolean,
            expectDestroySockets: Boolean
    ) {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val frozenStateCallback = getUidFrozenStateChangedCallback()

        // Make cell default network idle
        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        netdEventListener.onInterfaceClassActivityChanged(
                false, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )

        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_DENY
        )

        // Freeze TEST_UID
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        if (!isFrozen) {
            // Unfreeze TEST_UID
            frozenStateCallback.onUidFrozenStateChanged(
                    intArrayOf(TEST_UID),
                    intArrayOf(UID_FROZEN_STATE_UNFROZEN)
            )
        }
        if (!enableBackgroundChain) {
            // Disable background chain
            cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, false)
        }
        if (!denyOnBackgroundChain) {
            // Set allow rule on background chain for TEST_UID
            doReturn(BLOCKED_REASON_NONE)
                    .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
            cm.setUidFirewallRule(
                    FIREWALL_CHAIN_BACKGROUND,
                    TEST_UID,
                    FIREWALL_RULE_ALLOW
            )
        }
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))

        // Make cell network active
        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()

        if (expectDestroySockets) {
            verify(destroySocketsWrapper).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
            verify(quicConnectionCloser).closeQuicConnectionByUids(setOf(TEST_UID))
        } else {
            verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
            verify(quicConnectionCloser, never()).closeQuicConnectionByUids(setOf(TEST_UID))
        }
    }

    @Test
    fun testDestroySockets_backgroundDeny_frozen() {
        doTestDestroySockets(
                isFrozen = true,
                denyOnBackgroundChain = true,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundDeny_nonFrozen() {
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = true,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundAllow_frozen() {
        doTestDestroySockets(
                isFrozen = true,
                denyOnBackgroundChain = false,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundAllow_nonFrozen() {
        // If the app is neither frozen nor under background restriction, sockets are not
        // destroyed
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = false,
                enableBackgroundChain = true,
                expectDestroySockets = false
        )
    }

    @Test
    fun testDestroySockets_backgroundChainDisabled_nonFrozen() {
        // If the app is neither frozen nor under background restriction, sockets are not
        // destroyed
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = true,
                enableBackgroundChain = false,
                expectDestroySockets = false
        )
    }

    private fun InetAddress.toLinkAddress() =
        LinkAddress(this, if (this is Inet6Address) 64 else 24)

    private fun prepareNetworkAgent(
        interfaceName: String,
        addresses: List<LinkAddress>,
        transportType: Int
    ): Pair<CSAgentWrapper, TestableNetworkCallback> {
        val callback = TestableNetworkCallback()
        cm.registerNetworkCallback(makeRequest(transportType), callback)
        val linkProperties = makeLp(interfaceName)
        for (address in addresses) {
            linkProperties.addLinkAddress(address)
        }
        val agent = Agent(nc = makeNc(transportType), lp = linkProperties)
        agent.connect()
        return agent to callback
    }

    @Test
    fun testIpToNetworksMap() {
        val addressV6_1 = InetAddress.getByName("2001:DB8:0100::1111")
        val addressV6_2 = InetAddress.getByName("2001:DB8:0200::2222")
        val addressV6_3 = InetAddress.getByName("2001:DB8:0333::3333")
        val addressV6LinkLocal = InetAddress.getByName("FE80::1234")
        val addressV4_1 = InetAddress.getByName("192.0.2.10")
        val addressV4_2 = InetAddress.getByName("192.0.2.11")
        // Creates 3 NetworkAgent with various IP addresses(some are used for only one network
        // agent some are used for multiple network agents.)

        val (cellAgent, cellCallback) = prepareNetworkAgent(
            "rmnet1",
            arrayListOf(addressV6_1.toLinkAddress(), addressV4_1.toLinkAddress()),
            TRANSPORT_CELLULAR
        )

        val (wlanAgent, wlanCallback) = prepareNetworkAgent(
            "wlan1",
            arrayListOf(addressV6_2.toLinkAddress(), addressV4_1.toLinkAddress()),
            TRANSPORT_WIFI
        )
        val (ethAgent, ethCallback) = prepareNetworkAgent(
            "eth2",
            arrayListOf(addressV6_2.toLinkAddress(), addressV4_2.toLinkAddress()),
            TRANSPORT_ETHERNET
        )
        val ipToNetworksMap = assertNotNull(service.mIpToNetworksMap)
        fun InetAddress.getNetworks() = ipToNetworksMap[this]!!.map{ it.network }.toSet()

        // Verify mIpToNetworksMap properly stores network agents based on IP addresses.
        assertEquals(setOf(cellAgent.network), addressV6_1.getNetworks())
        assertEquals(
            setOf(wlanAgent.network, ethAgent.network),
            addressV6_2.getNetworks()
        )
        assertEquals(
            setOf(wlanAgent.network, cellAgent.network),
            addressV4_1.getNetworks()
        )
        assertEquals(setOf(ethAgent.network), addressV4_2.getNetworks())

        // Disconnect network agent#2 & #3.
        wlanAgent.disconnect()
        ethAgent.disconnect()
        // Update only interface name without IP address change for network agent #1.
        val linkProperties = makeLp("ipsec1")
        linkProperties.addLinkAddress(addressV4_1.toLinkAddress())
        linkProperties.addLinkAddress(addressV6_1.toLinkAddress())
        cellAgent.sendLinkProperties(linkProperties)
        cellCallback.eventuallyExpect<LinkPropertiesChanged> {
            it.network == cellAgent.network && it.lp.interfaceName == "ipsec1"
        }

        // Verify mIpToNetworksMap for the LinkPropertiesChanged
        assertFalse(ipToNetworksMap.containsKey(addressV6_2))
        assertFalse(ipToNetworksMap.containsKey(addressV4_2))
        assertEquals(setOf(cellAgent.network), addressV6_1.getNetworks())
        assertEquals(setOf(cellAgent.network), addressV4_1.getNetworks())

        // Change IP addresses for network agent #1.
        val linkProperties2 = makeLp("rmnet1")
        linkProperties2.addLinkAddress(addressV4_2.toLinkAddress())
        linkProperties2.addLinkAddress(addressV6_1.toLinkAddress())
        linkProperties2.addLinkAddress(addressV6_3.toLinkAddress())
        cellAgent.sendLinkProperties(linkProperties2)
        // Add network agent#4
        val (agent4, callback4) = prepareNetworkAgent(
            "bt1",
            arrayListOf(addressV6LinkLocal.toLinkAddress(), addressV4_2.toLinkAddress()),
            TRANSPORT_BLUETOOTH
        )
        assertEquals(3, ipToNetworksMap.size)
        assertEquals(setOf(cellAgent.network), addressV6_1.getNetworks())
        assertEquals(
            setOf(cellAgent.network, agent4.network),
            addressV4_2.getNetworks()
        )
        assertEquals(setOf(cellAgent.network), addressV6_3.getNetworks())
        assertFalse(ipToNetworksMap.containsKey(addressV4_1))
        // This map doesn't handle IPv6 link local addresses.
        assertFalse(ipToNetworksMap.containsKey(addressV6LinkLocal))

        // Disconnect all remaining network agents.
        cellAgent.disconnect()
        agent4.disconnect()
        agent4.eventuallyExpect<OnNetworkDestroyed>()
        // Verify mIpToNetworksMap is empty.
        assertTrue(ipToNetworksMap.isEmpty())
    }
}
