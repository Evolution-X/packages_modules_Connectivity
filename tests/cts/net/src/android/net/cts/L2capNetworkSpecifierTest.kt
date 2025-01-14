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

package android.net.cts

import android.net.L2capNetworkSpecifier
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_ANY
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_NONE
import android.net.L2capNetworkSpecifier.PSM_ANY
import android.net.L2capNetworkSpecifier.ROLE_CLIENT
import android.net.L2capNetworkSpecifier.ROLE_SERVER
import android.net.MacAddress
import android.os.Build
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertParcelingIsLossless
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@ConnectivityModuleTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class L2capNetworkSpecifierTest {
    @Test
    fun testParcelUnparcel() {
        val remoteMac = MacAddress.fromString("01:02:03:04:05:06")
        val specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .setPsm(42)
                .setRemoteAddress(remoteMac)
                .build()
        assertParcelingIsLossless(specifier)
    }

    @Test
    fun testGetters() {
        val remoteMac = MacAddress.fromString("11:22:33:44:55:66")
        val specifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setPsm(123)
                .setRemoteAddress(remoteMac)
                .build()
        assertEquals(ROLE_CLIENT, specifier.getRole())
        assertEquals(HEADER_COMPRESSION_NONE, specifier.getHeaderCompression())
        assertEquals(123, specifier.getPsm())
        assertEquals(remoteMac, specifier.getRemoteAddress())
    }

    @Test
    fun testCanBeSatisfiedBy() {
        val blanketOffer = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_ANY)
                .setPsm(PSM_ANY)
                .build()

        val reservedOffer = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .setPsm(42)
                .build()

        val clientOffer = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_ANY)
                .build()

        val serverReservation = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()

        assertTrue(serverReservation.canBeSatisfiedBy(blanketOffer))
        assertTrue(serverReservation.canBeSatisfiedBy(reservedOffer))
        // Note: serverReservation can be filed using reserveNetwork, or it could be a regular
        // request filed using requestNetwork.
        assertFalse(serverReservation.canBeSatisfiedBy(clientOffer))

        val clientRequest = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_CLIENT)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .setRemoteAddress(MacAddress.fromString("00:01:02:03:04:05"))
                .setPsm(42)
                .build()

        assertTrue(clientRequest.canBeSatisfiedBy(clientOffer))
        // Note: the BlanketOffer also includes a RES_ID_MATCH_ALL_RESERVATIONS. Since the
        // clientRequest is not a reservation, it won't match that request to begin with.
        assertFalse(clientRequest.canBeSatisfiedBy(blanketOffer))
        assertFalse(clientRequest.canBeSatisfiedBy(reservedOffer))

        val matchAny = L2capNetworkSpecifier.Builder().build()
        assertTrue(matchAny.canBeSatisfiedBy(blanketOffer))
        assertTrue(matchAny.canBeSatisfiedBy(reservedOffer))
        assertTrue(matchAny.canBeSatisfiedBy(clientOffer))
    }
}
