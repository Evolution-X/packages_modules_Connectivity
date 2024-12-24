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

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Reserved
import com.android.testutils.RecorderCallback.CallbackEntry.Unavailable
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkOfferCallback
import com.android.testutils.TestableNetworkOfferCallback.CallbackEntry.OnNetworkNeeded
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun NetworkCapabilities.copyWithReservationId(resId: Int) = NetworkCapabilities(this).also {
        it.reservationId = resId
    }

    @Test
    fun testReservationRequest() {
        val provider = NetworkProvider(context, csHandlerThread.looper, "Ethernet provider")
        val blanketOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)

        cm.registerNetworkProvider(provider)

        val blanketCaps = ETHERNET_CAPS.copyWithReservationId(RES_ID_MATCH_ALL_RESERVATIONS)
        provider.registerNetworkOffer(ETHERNET_SCORE, blanketCaps, {r -> r.run()}, blanketOfferCb)

        val req = NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET).build()
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(req, csHandler, cb)

        // validate the reservation matches the blanket offer.
        val reservationReq = blanketOfferCb.expectOnNetworkNeeded(blanketCaps).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        // bring up specific reservation offer
        val specificCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val specificOfferCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        provider.registerNetworkOffer(ETHERNET_SCORE, specificCaps, {r -> r.run()}, specificOfferCb)

        // validate onReserved was sent to the app
        val reservedCaps = cb.expect<Reserved>().caps
        assertEquals(specificCaps, reservedCaps)

        // validate the reservation matches the specific offer.
        specificOfferCb.expectOnNetworkNeeded(specificCaps)

        // Specific offer goes away
        provider.unregisterNetworkOffer(specificOfferCb)
        cb.expect<Unavailable>()
    }

    fun TestableNetworkOfferCallback.expectNoCallbackWhere(
            predicate: (TestableNetworkOfferCallback.CallbackEntry) -> Boolean
    ) {
        val event = history.poll(NO_CB_TIMEOUT_MS) { predicate(it) }
        assertNull(event)
    }

    @Test
    fun testReservationRequest_notDeliveredToRegularOffer() {
        val provider = NetworkProvider(context, csHandlerThread.looper, "Ethernet provider")
        val offerCb = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)

        cm.registerNetworkProvider(provider)
        provider.registerNetworkOffer(ETHERNET_SCORE, ETHERNET_CAPS, {r -> r.run()}, offerCb)

        val req = NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET).build()
        val cb = TestableNetworkCallback()
        cm.reserveNetwork(req, csHandler, cb)

        // validate the offer does not receive onNetworkNeeded for reservation request
        offerCb.expectNoCallbackWhere {
            it is OnNetworkNeeded && it.request.type == NetworkRequest.Type.RESERVATION
        }
    }
}
