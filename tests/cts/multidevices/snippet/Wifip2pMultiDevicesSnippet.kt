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

package com.google.snippet.connectivity

import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.fail

private const val TIMEOUT_MS = 60000L

class Wifip2pMultiDevicesSnippet : Snippet {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().getTargetContext() }
    private val wifiManager by lazy {
        context.getSystemService(WifiManager::class.java)
                ?: fail("Could not get WifiManager service")
    }
    private val wifip2pManager by lazy {
        context.getSystemService(WifiP2pManager::class.java)
                ?: fail("Could not get WifiP2pManager service")
    }
    private lateinit var wifip2pChannel: WifiP2pManager.Channel

    @Rpc(description = "Check whether the device supports Wi-Fi P2P.")
    fun isP2pSupported() = wifiManager.isP2pSupported()

    @Rpc(description = "Start Wi-Fi P2P")
    fun startWifiP2p() {
        // Initialize Wi-Fi P2P
        wifip2pChannel = wifip2pManager.initialize(context, context.mainLooper, null)

        // Ensure the Wi-Fi P2P channel is available
        val p2pStateEnabledFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestP2pState(wifip2pChannel) { state ->
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                p2pStateEnabledFuture.complete(true)
            }
        }
        p2pStateEnabledFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Stop Wi-Fi P2P")
    fun stopWifiP2p() {
        if (this::wifip2pChannel.isInitialized) {
            wifip2pManager.cancelConnect(wifip2pChannel, null)
            wifip2pManager.removeGroup(wifip2pChannel, null)
        }
    }
}
