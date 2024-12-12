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

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.os.Build
import android.os.Messenger
import android.os.Process.INVALID_UID
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkOfferCallback
import org.junit.Test
import org.junit.runner.RunWith


private val ETHERNET_SCORE = NetworkScore.Builder().build()
private val ETHERNET_CAPS = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_CONGESTED)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private const val TIMEOUT_MS = 5_000L
private const val NO_CB_TIMEOUT_MS = 200L

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSNetworkReservationTest : CSTest() {
    // TODO: remove this helper once reserveNetwork is added.
    // NetworkCallback does not currently do anything. It's just here so the API stays consistent
    // with the eventual ConnectivityManager API.
    private fun ConnectivityManager.reserveNetwork(req: NetworkRequest, cb: NetworkCallback) {
        service.requestNetwork(INVALID_UID, req.networkCapabilities,
                NetworkRequest.Type.RESERVATION.ordinal, Messenger(csHandler), 0 /* timeout */,
                null /* binder */, ConnectivityManager.TYPE_NONE, NetworkCallback.FLAG_NONE,
                context.packageName, context.attributionTag, NetworkCallback.DECLARED_METHODS_ALL)
    }

    @Test
    fun testReservationTriggersOnNetworkNeeded() {
        val provider = NetworkProvider(context, csHandlerThread.looper, "Ethernet provider")
        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)

        cm.registerNetworkProvider(provider)
        provider.registerNetworkOffer(ETHERNET_SCORE, ETHERNET_CAPS, {r -> r.run()}, offerCb)

        // TODO: add reservationId to offer, so it doesn't match the default request.
        offerCb.expectOnNetworkNeeded(ETHERNET_CAPS)

        val req = NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET).build()
        val cb = NetworkCallback()
        cm.reserveNetwork(req, cb)

        offerCb.expectOnNetworkNeeded(req.networkCapabilities)

        // TODO: also test onNetworkUnneeded is called once ConnectivityManager supports the
        // reserveNetwork API.
    }
}
