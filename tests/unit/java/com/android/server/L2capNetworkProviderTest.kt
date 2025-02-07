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

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
import android.net.ConnectivityManager
import android.net.ConnectivityManager.TYPE_NONE
import android.net.L2capNetworkSpecifier
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_NONE
import android.net.L2capNetworkSpecifier.ROLE_SERVER
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkProvider
import android.net.NetworkProvider.NetworkOfferCallback
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val TAG = "L2capNetworkProviderTest"

private val RESERVATION_CAPS = NetworkCapabilities.Builder.withoutDefaultCapabilities()
    .addTransportType(TRANSPORT_BLUETOOTH)
    .build()

private val RESERVATION = NetworkRequest(
        NetworkCapabilities(RESERVATION_CAPS),
        TYPE_NONE,
        42 /* rId */,
        NetworkRequest.Type.RESERVATION
)

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class L2capNetworkProviderTest {
    @Mock private lateinit var context: Context
    @Mock private lateinit var deps: L2capNetworkProvider.Dependencies
    @Mock private lateinit var provider: NetworkProvider
    @Mock private lateinit var cm: ConnectivityManager
    @Mock private lateinit var pm: PackageManager

    private val handlerThread = HandlerThread("$TAG handler thread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doReturn(provider).`when`(deps).getNetworkProvider(any(), any())
        doReturn(handlerThread).`when`(deps).getHandlerThread()
        doReturn(cm).`when`(context).getSystemService(eq(ConnectivityManager::class.java))
        doReturn(pm).`when`(context).getPackageManager()
        doReturn(true).`when`(pm).hasSystemFeature(FEATURE_BLUETOOTH_LE)
    }

    @After
    fun tearDown() {
        handlerThread.quitSafely()
        handlerThread.join()
    }

    @Test
    fun testNetworkProvider_registeredWhenSupported() {
        L2capNetworkProvider(deps, context).start()
        verify(cm).registerNetworkProvider(eq(provider))
        verify(provider).registerNetworkOffer(any(), any(), any(), any())
    }

    @Test
    fun testNetworkProvider_notRegisteredWhenNotSupported() {
        doReturn(false).`when`(pm).hasSystemFeature(FEATURE_BLUETOOTH_LE)
        L2capNetworkProvider(deps, context).start()
        verify(cm, never()).registerNetworkProvider(eq(provider))
    }

    fun doTestBlanketOfferIgnoresRequest(request: NetworkRequest) {
        clearInvocations(provider)
        L2capNetworkProvider(deps, context).start()

        val blanketOfferCaptor = ArgumentCaptor.forClass(NetworkOfferCallback::class.java)
        verify(provider).registerNetworkOffer(any(), any(), any(), blanketOfferCaptor.capture())

        blanketOfferCaptor.value.onNetworkNeeded(request)
        verify(provider).registerNetworkOffer(any(), any(), any(), any())
    }

    fun doTestBlanketOfferCreatesReservation(
            request: NetworkRequest,
            reservation: NetworkCapabilities
    ) {
        clearInvocations(provider)
        L2capNetworkProvider(deps, context).start()

        val blanketOfferCaptor = ArgumentCaptor.forClass(NetworkOfferCallback::class.java)
        verify(provider).registerNetworkOffer(any(), any(), any(), blanketOfferCaptor.capture())

        blanketOfferCaptor.value.onNetworkNeeded(request)

        val capsCaptor = ArgumentCaptor.forClass(NetworkCapabilities::class.java)
        verify(provider, times(2)).registerNetworkOffer(any(), capsCaptor.capture(), any(), any())

        assertTrue(reservation.satisfiedByNetworkCapabilities(capsCaptor.value))
    }

    @Test
    fun testBlanketOffer_reservationWithoutSpecifier() {
        val caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .build()
        val nr = NetworkRequest(caps, TYPE_NONE, 42 /* rId */, NetworkRequest.Type.RESERVATION)

        doTestBlanketOfferIgnoresRequest(nr)
    }

    @Test
    fun testBlanketOffer_reservationWithCorrectSpecifier() {
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        var caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .setNetworkSpecifier(specifier)
                .build()
        var nr = NetworkRequest(caps, TYPE_NONE, 42 /* rId */, NetworkRequest.Type.RESERVATION)
        doTestBlanketOfferCreatesReservation(nr, caps)

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .build()
        caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .setNetworkSpecifier(specifier)
                .build()
        nr = NetworkRequest(caps, TYPE_NONE, 43 /* rId */, NetworkRequest.Type.RESERVATION)
        doTestBlanketOfferCreatesReservation(nr, caps)
    }

    @Test
    fun testBlanketOffer_reservationWithIncorrectSpecifier() {
        var specifier = L2capNetworkSpecifier.Builder().build()
        var caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .setNetworkSpecifier(specifier)
                .build()
        var nr = NetworkRequest(caps, TYPE_NONE, 42 /* rId */, NetworkRequest.Type.RESERVATION)
        doTestBlanketOfferIgnoresRequest(nr)

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .build()
        caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .setNetworkSpecifier(specifier)
                .build()
        nr = NetworkRequest(caps, TYPE_NONE, 44 /* rId */, NetworkRequest.Type.RESERVATION)
        doTestBlanketOfferIgnoresRequest(nr)

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setPsm(0x81)
                .build()
        caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .setNetworkSpecifier(specifier)
                .build()
        nr = NetworkRequest(caps, TYPE_NONE, 45 /* rId */, NetworkRequest.Type.RESERVATION)
        doTestBlanketOfferIgnoresRequest(nr)

        specifier = L2capNetworkSpecifier.Builder()
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .build()
        caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .setNetworkSpecifier(specifier)
                .build()
        nr = NetworkRequest(caps, TYPE_NONE, 47 /* rId */, NetworkRequest.Type.RESERVATION)
        doTestBlanketOfferIgnoresRequest(nr)
    }
}
