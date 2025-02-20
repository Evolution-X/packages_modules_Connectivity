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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.L2capNetworkSpecifier
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_NONE
import android.net.L2capNetworkSpecifier.ROLE_SERVER
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkProvider
import android.net.NetworkProvider.NetworkOfferCallback
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.NetworkSpecifier
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import com.android.server.L2capNetworkProvider
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Reserved
import com.android.testutils.RecorderCallback.CallbackEntry.Unavailable
import com.android.testutils.TestableNetworkCallback
import java.io.IOException
import java.util.Optional
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

private const val PSM = 0x85
private val REMOTE_MAC = byteArrayOf(1, 2, 3, 4, 5, 6)
private val REQUEST = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_BLUETOOTH)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
        .build()

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSL2capProviderTest : CSTest() {
    private val btAdapter = mock<BluetoothAdapter>()
    private val btServerSocket = mock<BluetoothServerSocket>()
    private val btSocket = mock<BluetoothSocket>()
    private val providerDeps = mock<L2capNetworkProvider.Dependencies>()
    // BlockingQueue does not support put(null) operations, as null is used as an internal sentinel
    // value. Therefore, use Optional<BluetoothSocket> where an empty optional signals the
    // BluetoothServerSocket#close() operation.
    private val acceptQueue = LinkedBlockingQueue<Optional<BluetoothSocket>>()

    private val handlerThread = HandlerThread("CSL2capProviderTest thread").apply { start() }

    // Requires Dependencies mock to be setup before creation.
    private lateinit var provider: L2capNetworkProvider

    @Before
    fun innerSetUp() {
        doReturn(btAdapter).`when`(bluetoothManager).getAdapter()
        doReturn(btServerSocket).`when`(btAdapter).listenUsingInsecureL2capChannel()
        doReturn(PSM).`when`(btServerSocket).getPsm();

        doAnswer {
            val sock = acceptQueue.take()
            if (sock == null || !sock.isPresent()) throw IOException()
            sock.get()
        }.`when`(btServerSocket).accept()

        doAnswer {
            acceptQueue.put(Optional.empty())
        }.`when`(btServerSocket).close()

        doReturn(handlerThread).`when`(providerDeps).getHandlerThread()
        provider = L2capNetworkProvider(providerDeps, context)
        provider.start()
    }

    @After
    fun innerTearDown() {
        handlerThread.quitSafely()
        handlerThread.join()
    }

    private fun reserveNetwork(nr: NetworkRequest) = TestableNetworkCallback().also {
        cm.reserveNetwork(nr, csHandler, it)
    }

    private fun requestNetwork(nr: NetworkRequest) = TestableNetworkCallback().also {
        cm.requestNetwork(nr, it, csHandler)
    }

    private fun NetworkRequest.copyWithSpecifier(specifier: NetworkSpecifier): NetworkRequest {
        // Note: NetworkRequest.Builder(NetworkRequest) *does not* perform a defensive copy but
        // changes the underlying request.
        return NetworkRequest.Builder(NetworkRequest(this))
                .setNetworkSpecifier(specifier)
                .build()
    }

    @Test
    fun testReservation() {
        val l2capServerSpecifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        val l2capReservation = REQUEST.copyWithSpecifier(l2capServerSpecifier)
        val reservationCb = reserveNetwork(l2capReservation)

        val reservedCaps = reservationCb.expect<Reserved>().caps
        val reservedSpec = reservedCaps.networkSpecifier as L2capNetworkSpecifier

        assertEquals(PSM, reservedSpec.getPsm())
        assertEquals(HEADER_COMPRESSION_6LOWPAN, reservedSpec.headerCompression)
        assertNull(reservedSpec.remoteAddress)

        reservationCb.assertNoCallback()
    }

    @Test
    fun testBlanketOffer_reservationWithoutSpecifier() {
        reserveNetwork(REQUEST).assertNoCallback()
    }

    @Test
    fun testBlanketOffer_reservationWithCorrectSpecifier() {
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).expect<Reserved>()

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).expect<Reserved>()
    }

    @Test
    fun testBlanketOffer_reservationWithIncorrectSpecifier() {
        var specifier = L2capNetworkSpecifier.Builder().build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()

        specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setPsm(0x81)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()

        specifier = L2capNetworkSpecifier.Builder()
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .build()
        nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).assertNoCallback()
    }

    @Test
    fun testBluetoothException_listenUsingInsecureL2capChannelThrows() {
        doThrow(IOException()).`when`(btAdapter).listenUsingInsecureL2capChannel()
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        reserveNetwork(nr).expect<Unavailable>()

        doReturn(btServerSocket).`when`(btAdapter).listenUsingInsecureL2capChannel()
        reserveNetwork(nr).expect<Reserved>()
    }

    @Test
    fun testBluetoothException_acceptThrows() {
        doThrow(IOException()).`when`(btServerSocket).accept()
        var specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        var nr = REQUEST.copyWithSpecifier(specifier)
        val cb = reserveNetwork(nr)
        cb.expect<Reserved>()
        cb.expect<Unavailable>()

        // BluetoothServerSocket#close() puts Optional.empty() on the acceptQueue.
        acceptQueue.clear()
        doAnswer {
            val sock = acceptQueue.take()
            assertFalse(sock.isPresent())
            throw IOException() // to indicate the socket was closed.
        }.`when`(btServerSocket).accept()
        val cb2 = reserveNetwork(nr)
        cb2.expect<Reserved>()
        cb2.assertNoCallback()
    }
}
