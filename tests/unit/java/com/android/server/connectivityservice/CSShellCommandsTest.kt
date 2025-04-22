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

import android.annotation.SuppressLint
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_SATELLITE
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Losing
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.TestableNetworkCallback
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSShellCommandsTest : CSTest() {

    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    fun handleShellCommand(args: String) {
        val pfd = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_WRITE)
        service.handleShellCommand(pfd, pfd, pfd, args.split(" ").toTypedArray())
    }

    fun ncForTransport(transport: Int, otherCaps: IntArray = intArrayOf()): NetworkCapabilities {
        return NetworkCapabilities.Builder().apply {
            addTransportType(transport)
            addCapability(NET_CAPABILITY_INTERNET)
            addCapability(NET_CAPABILITY_NOT_ROAMING)
            addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            addCapability(NET_CAPABILITY_NOT_VPN)
            removeCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
            for (i in otherCaps) addCapability(i)
        }.build()
    }

    @SuppressLint("MissingPermission")
    @Test @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testDebugFallbackNetwork() {
        assumeTrue(Build.isDebuggable())

        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        val defaultCb = TestableNetworkCallback()
        cm.registerDefaultNetworkCallback(defaultCb)

        // Set myUid to default to satellite.
        handleShellCommand(
            "set-debug-fallback-network-for-uid ${Process.myUid()} $TRANSPORT_SATELLITE"
        )

        // When satellite connects, it becomes the default network for myUid.
        val satelliteAgent = Agent(ncForTransport(TRANSPORT_SATELLITE))
        satelliteAgent.connect()
        cb.expectAvailableCallbacks(satelliteAgent.network, validated = false)
        defaultCb.expectAvailableCallbacks(satelliteAgent.network, validated = false)

        // When wifi connects, satellite is no longer myUid's default and gets torn down.
        val wifiAgent = Agent(ncForTransport(
            TRANSPORT_WIFI,
            intArrayOf(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
        ))
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
        defaultCb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        cb.expect<Losing>(satelliteAgent.network)
        cb.expect<Lost>(satelliteAgent.network)

        // When wifi disconnects, satellite becomes the default again.
        wifiAgent.disconnect()
        cb.expect<Lost>(wifiAgent.network)
        defaultCb.expect<Lost>(wifiAgent.network)

        val satelliteAgent2 = Agent(ncForTransport(TRANSPORT_SATELLITE))
        satelliteAgent2.connect()
        cb.expectAvailableCallbacks(satelliteAgent2.network, validated = false)
        defaultCb.expectAvailableCallbacks(satelliteAgent2.network, validated = false)

        // Set myUid to no longer default to satellite, and expect satellite to disconnect.
        // It cannot be the system default network because it's bandwidth constrained.
        handleShellCommand("clear-debug-fallback-network-for-uid ${Process.myUid()}")
        cb.expect<Lost>(satelliteAgent2.network)
        defaultCb.expect<Lost>(satelliteAgent2.network)
    }
}
