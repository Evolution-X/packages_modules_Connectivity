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

package com.android.server

import android.content.Intent
import android.net.ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED
import android.net.InetAddresses
import android.net.LinkProperties
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.visibleOnHandlerThread
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal val LOCAL_DNS = InetAddresses.parseNumericAddress("224.0.1.2")
internal val NON_LOCAL_DNS = InetAddresses.parseNumericAddress("76.76.75.75")

private const val IFNAME_1 = "wlan1"
private const val IFNAME_2 = "wlan2"
private const val PORT_53 = 53
private const val PROTOCOL_TCP = 6
private const val PROTOCOL_UDP = 17

private val lpWithNoLocalDns = LinkProperties().apply {
    addDnsServer(NON_LOCAL_DNS)
    interfaceName = IFNAME_1
}

private val lpWithLocalDns = LinkProperties().apply {
    addDnsServer(LOCAL_DNS)
    interfaceName = IFNAME_2
}

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2) // Bpf only supports in T+.
class CSBpfNetMapsTest : CSTest() {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    @IgnoreAfter(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testCSTrackDataSaverBeforeV() {
        val inOrder = inOrder(bpfNetMaps)
        mockDataSaverStatus(RESTRICT_BACKGROUND_STATUS_WHITELISTED)
        inOrder.verify(bpfNetMaps).setDataSaverEnabled(true)
        mockDataSaverStatus(RESTRICT_BACKGROUND_STATUS_DISABLED)
        inOrder.verify(bpfNetMaps).setDataSaverEnabled(false)
        mockDataSaverStatus(RESTRICT_BACKGROUND_STATUS_ENABLED)
        inOrder.verify(bpfNetMaps).setDataSaverEnabled(true)
    }

    // Data Saver Status is updated from platform code in V+.
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testCSTrackDataSaverAboveU() {
        listOf(RESTRICT_BACKGROUND_STATUS_WHITELISTED, RESTRICT_BACKGROUND_STATUS_ENABLED,
            RESTRICT_BACKGROUND_STATUS_DISABLED).forEach {
            mockDataSaverStatus(it)
            verify(bpfNetMaps, never()).setDataSaverEnabled(anyBoolean())
        }
    }

    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Test
    fun testLocalPrefixesUpdatedInBpfMap() {
        // Connect Wi-Fi network with non-local dns.
        val wifiAgent = Agent(nc = defaultNc(), lp = lpWithNoLocalDns)
        wifiAgent.connect()

        // Verify that block rule is added to BpfMap for local prefixes.
        verify(bpfNetMaps, atLeastOnce()).addLocalNetAccess(any(), eq(IFNAME_1),
            any(), eq(0), eq(0), eq(false))

        wifiAgent.disconnect()
        val cellAgent = Agent(nc = defaultNc(), lp = lpWithLocalDns)
        cellAgent.connect()

        // Verify that block rule is removed from BpfMap for local prefixes.
        verify(bpfNetMaps, atLeastOnce()).removeLocalNetAccess(any(), eq(IFNAME_1),
            any(), eq(0), eq(0))

        cellAgent.disconnect()
    }

    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Test
    fun testLocalDnsNotUpdatedInBpfMap() {
        // Connect Wi-Fi network with non-local dns.
        val wifiAgent = Agent(nc = defaultNc(), lp = lpWithNoLocalDns)
        wifiAgent.connect()

        // Verify that No allow rule is added to BpfMap since there is no local dns.
        verify(bpfNetMaps, never()).addLocalNetAccess(any(), any(), any(), any(), any(),
            eq(true))

        wifiAgent.disconnect()
        val cellAgent = Agent(nc = defaultNc(), lp = lpWithLocalDns)
        cellAgent.connect()

        // Verify that No allow rule from port 53 is removed on network change
        // because no dns was added
        verify(bpfNetMaps, never()).removeLocalNetAccess(eq(192), eq(IFNAME_1),
            eq(NON_LOCAL_DNS), any(), eq(PORT_53))

        cellAgent.disconnect()
    }

    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    @Test
    fun testLocalDnsUpdatedInBpfMap() {
        // Connect Wi-Fi network with one local Dns.
        val wifiAgent = Agent(nc = defaultNc(), lp = lpWithLocalDns)
        wifiAgent.connect()

        // Verify that allow rule is added to BpfMap for local dns at port 53,
        // for TCP(=6) protocol
        verify(bpfNetMaps, atLeastOnce()).addLocalNetAccess(eq(192), eq(IFNAME_2),
            eq(LOCAL_DNS), eq(PROTOCOL_TCP), eq(PORT_53), eq(true))
        // And for UDP(=17) protocol
        verify(bpfNetMaps, atLeastOnce()).addLocalNetAccess(eq(192), eq(IFNAME_2),
            eq(LOCAL_DNS), eq(PROTOCOL_UDP), eq(PORT_53), eq(true))

        wifiAgent.disconnect()
        val cellAgent = Agent(nc = defaultNc(), lp = lpWithNoLocalDns)
        cellAgent.connect()

        // Verify that allow rule is removed for local dns on network change,
        // for TCP(=6) protocol
        verify(bpfNetMaps, atLeastOnce()).removeLocalNetAccess(eq(192), eq(IFNAME_2),
            eq(LOCAL_DNS), eq(PROTOCOL_TCP), eq(PORT_53))
        // And for UDP(=17) protocol
        verify(bpfNetMaps, atLeastOnce()).removeLocalNetAccess(eq(192), eq(IFNAME_2),
            eq(LOCAL_DNS), eq(PROTOCOL_UDP), eq(PORT_53))

        cellAgent.disconnect()
    }

    private fun mockDataSaverStatus(status: Int) {
        doReturn(status).`when`(context.networkPolicyManager).getRestrictBackgroundStatus(anyInt())
        // While the production code dispatches the intent on the handler thread,
        // The test would dispatch the intent in the caller thread. Make it dispatch
        // on the handler thread to match production behavior.
        visibleOnHandlerThread(csHandler) {
            context.sendBroadcast(Intent(ACTION_RESTRICT_BACKGROUND_CHANGED))
        }
        waitForIdle()
    }
}
