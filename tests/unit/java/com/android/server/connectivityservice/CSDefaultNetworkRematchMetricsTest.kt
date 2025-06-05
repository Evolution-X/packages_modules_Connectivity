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

package com.android.server

import android.net.Network
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import com.android.server.ConnectivityService.NetworkRequestInfo
import com.android.server.ConnectivityStatsLog.DEFAULT_NETWORK_REMATCH__REMATCH_REASON__RMR_NETWORK_DISCONNECTED
import com.android.server.connectivity.NetworkAgentInfo
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

private const val DEFAULT_REQUEST_ID = 1
private inline fun <reified T> argumentCaptor() = ArgumentCaptor.forClass(T::class.java)

@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
@RunWith(DevSdkIgnoreRunner::class)
class CSDefaultNetworkRematchMetricsTest : CSTest() {
    @Test
    fun testRematchWritesStats() {
        waitForIdle()
        reset(defaultNetworkRematchMetrics)

        // 1. Connect a cellular network. It becomes the default.
        val naiCell = Agent(nc = nc(TRANSPORT_CELLULAR, NET_CAPABILITY_INTERNET))
        naiCell.connect(true)
        waitForIdle()
        eventuallyExpectAddEvent(DEFAULT_REQUEST_ID, null, naiCell.network)
        verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear()
        reset(defaultNetworkRematchMetrics)

        // 2. Connect a WiFi network. It has a higher score and will cause a rematch.
        val naiWifi = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        naiWifi.connect(true)
        waitForIdle()

        // 3. Verify: The rematch should trigger writing the stats.
        eventuallyExpectAddEvent(DEFAULT_REQUEST_ID, naiCell.network, naiWifi.network)
        verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear()
    }

    @Test
    fun testDisconnectWritesStats() {
        waitForIdle()
        reset(defaultNetworkRematchMetrics)

        // 1. Connect a cellular network. It becomes the default.
        val naiCell = Agent(nc = nc(TRANSPORT_CELLULAR, NET_CAPABILITY_INTERNET))
        naiCell.connect(true)
        waitForIdle()
        eventuallyExpectAddEvent(DEFAULT_REQUEST_ID, null, naiCell.network)
        verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear()
        reset(defaultNetworkRematchMetrics)

        // 2. Disconnect the cellular network.
        naiCell.disconnect()
        waitForIdle()

        // 3. Verify: Disconnecting the default network should trigger writing the stats.
        eventuallyExpectAddEvent(DEFAULT_REQUEST_ID, naiCell.network, null)
        verify(defaultNetworkRematchMetrics, atLeastOnce()).writeStatsAndClear(
                DEFAULT_NETWORK_REMATCH__REMATCH_REASON__RMR_NETWORK_DISCONNECTED
        )
    }

    private fun eventuallyExpectAddEvent(
            expectedRequestId: Int,
            expectedOldNetwork: Network?,
            expectedNewNetwork: Network?
    ) {
        val nriCaptor = argumentCaptor<NetworkRequestInfo>()
        val oldNetworkCaptor = argumentCaptor<NetworkAgentInfo?>()
        val newNetworkCaptor = argumentCaptor<NetworkAgentInfo?>()
        verify(defaultNetworkRematchMetrics, atLeastOnce()).addEvent(
                nriCaptor.capture(),
                oldNetworkCaptor.capture(),
                newNetworkCaptor.capture()
        )

        // TODO: Support checking multilayer requests.
        val capturedEvents = nriCaptor.allValues.indices.map { i ->
            val requestId = nriCaptor.allValues[i].mRequests[0].requestId
            val oldNetwork = oldNetworkCaptor.allValues[i]
            val newNetwork = newNetworkCaptor.allValues[i]
            if (requestId == expectedRequestId && oldNetwork?.network == expectedOldNetwork &&
                    newNetwork?.network == expectedNewNetwork) {
                        return
                    }
            Triple(requestId, oldNetwork?.network, newNetwork?.network)
        }

        fail(
                "Expecting ($expectedRequestId, $expectedOldNetwork, $expectedNewNetwork) " +
                        "but got $capturedEvents"
        )
    }
}
