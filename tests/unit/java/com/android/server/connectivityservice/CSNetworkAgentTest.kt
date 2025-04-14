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

import android.net.NativeNetworkConfig
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.net.netd.aidl.NativeUidRangeConfig
import android.os.Build
import android.os.Process
import android.util.Range
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S)
class CSNetworkAgentTest : CSTest() {
    @Test fun testVpnUidAgent() = testUidAgent(
        TRANSPORT_VPN,
        expectAddUidRanges = true
    )
    @ConnectivityModuleTest
    @Test fun testWifiUidAgent() = testUidAgent(TRANSPORT_WIFI, expectAddUidRanges = false)

    fun testUidAgent(transport: Int, expectAddUidRanges: Boolean) {
        val netdInOrder = inOrder(netd)
        val uid = Process.myUid()

        val nc = defaultNc()
            .addTransportType(transport)
            .setUids(setOf(Range(uid, uid)))
        if (TRANSPORT_VPN == transport) {
            nc.removeCapability(NET_CAPABILITY_NOT_VPN)
            nc.setTransportInfo(
                VpnTransportInfo(
                    VpnManager.TYPE_VPN_SERVICE,
                    "MySession12345",
                    true /* bypassable */,
                    false /* longLivedTcpConnectionsExpensive */
                )
            )
        }
        val agent = Agent(nc)
        agent.connect()

        netdInOrder.verify(netd).networkCreate(argThat { it: NativeNetworkConfig ->
            it.netId == agent.network.netId
        })
        if (deps.isAtLeastU()) {
          // The call to setNetworkAllowlist was added in U.
          netdInOrder.verify(netd).setNetworkAllowlist(any())
        }
        if (expectAddUidRanges) {
            netdInOrder.verify(netd).networkAddUidRangesParcel(argThat { it: NativeUidRangeConfig ->
                it.netId == agent.network.netId &&
                        it.uidRanges.size == 1 &&
                        it.uidRanges[0].start == uid &&
                        it.uidRanges[0].stop == uid &&
                        it.subPriority == 0 // VPN priority
            })
        } else {
            netdInOrder.verify(netd, never()).networkAddUidRangesParcel(any())
        }
        // The old method should never be called in any case
        netdInOrder.verify(netd, never()).networkAddUidRanges(anyInt(), any())
    }
}
