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
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_NONE
import android.net.L2capNetworkSpecifier.ROLE_CLIENT
import android.net.L2capNetworkSpecifier.ROLE_SERVER
import android.net.MacAddress
import android.os.Build
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertParcelingIsLossless
import kotlin.test.assertEquals
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
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_NONE)
                .setPsm(123)
                .setRemoteAddress(remoteMac)
                .build()
        assertEquals(ROLE_SERVER, specifier.getRole())
        assertEquals(HEADER_COMPRESSION_NONE, specifier.getHeaderCompression())
        assertEquals(123, specifier.getPsm())
        assertEquals(remoteMac, specifier.getRemoteAddress())
    }
}
