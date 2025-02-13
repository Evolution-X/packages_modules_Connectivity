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

package com.android.server.net

import android.os.Build
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.internal.util.HexDump
import com.google.common.truth.Truth.assertThat

import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT = 1000L

@ConnectivityModuleTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class HeaderCompressionUtilsTest {

    private fun decompressHex(hex: String): ByteArray {
        val bytes = HexDump.hexStringToByteArray(hex)
        val buf = bytes.copyOf(1500)
        val newLen = HeaderCompressionUtils.decompress6lowpan(buf, bytes.size)
        return buf.copyOf(newLen)
    }

    private fun compressHex(hex: String): ByteArray {
        val buf = HexDump.hexStringToByteArray(hex)
        val newLen = HeaderCompressionUtils.compress6lowpan(buf, buf.size)
        return buf.copyOf(newLen)
    }

    private fun String.decodeHex() = HexDump.hexStringToByteArray(this)

    @Test
    fun testHeaderDecompression() {
        // TF: 00, NH: 0, HLIM: 00, CID: 0, SAC: 0, SAM: 00, M: 0, DAC: 0, DAM: 00
        var input = "6000" +
                    "ccf" +                               // ECN + DSCP + 4-bit Pad (puts f in padding)
                    "12345" +                             // flow label
                    "11" +                                // next header
                    "e7" +                                // hop limit
                    "abcdef1234567890abcdef1234567890" +  // source
                    "aaabbbcccdddeeefff00011122233344" +  // dest
                    "abcd"                                // payload

        var output = "6" +                                // version
                     "cc" +                               // traffic class
                     "12345" +                            // flow label
                     "0002" +                             // payload length
                     "11" +                               // next header
                     "e7" +                               // hop limit
                     "abcdef1234567890abcdef1234567890" + // source
                     "aaabbbcccdddeeefff00011122233344" + // dest
                     "abcd"                               // payload
        assertThat(decompressHex(input)).isEqualTo(output.decodeHex())

        // TF: 01, NH: 0, HLIM: 01, CID: 0, SAC: 0, SAM: 01, M: 0, DAC: 0, DAM: 01
        input  = "6911" +
                 "5" +                                // ECN + 2-bit pad (puts 1 in padding)
                 "f100e" +                            // flow label
                 "42" +                               // next header
                 "1102030405060708" +                 // source
                 "aa0b0c0d0e0f1011" +                 // dest
                 "abcd"                               // payload

        output = "6" +                                // version
                 "01" +                               // traffic class
                 "f100e" +                            // flow label
                 "0002" +                             // payload length
                 "42" +                               // next header
                 "01" +                               // hop limit
                 "fe800000000000001102030405060708" + // source
                 "fe80000000000000aa0b0c0d0e0f1011" + // dest
                 "abcd"                               // payload
        assertThat(decompressHex(input)).isEqualTo(output.decodeHex())

        // TF: 10, NH: 0, HLIM: 10, CID: 0, SAC: 0, SAM: 10, M: 0, DAC: 0, DAM: 10
        input  = "7222" +
                 "cc" +                               // traffic class
                 "43" +                               // next header
                 "1234" +                             // source
                 "abcd" +                             // dest
                 "abcdef"                             // payload

        output = "6" +                                // version
                 "cc" +                               // traffic class
                 "00000" +                            // flow label
                 "0003" +                             // payload length
                 "43" +                               // next header
                 "40" +                               // hop limit
                 "fe80000000000000000000fffe001234" + // source
                 "fe80000000000000000000fffe00abcd" + // dest
                 "abcdef"                             // payload
        assertThat(decompressHex(input)).isEqualTo(output.decodeHex())

        // TF: 11, NH: 0, HLIM: 11, CID: 0, SAC: 0, SAM: 10, M: 1, DAC: 0, DAM: 00
        input  = "7b28" +
                 "44" +                               // next header
                 "1234" +                             // source
                 "ff020000000000000000000000000001" + // dest
                 "abcdef01"                           // payload

        output = "6" +                                // version
                 "00" +                               // traffic class
                 "00000" +                            // flow label
                 "0004" +                             // payload length
                 "44" +                               // next header
                 "ff" +                               // hop limit
                 "fe80000000000000000000fffe001234" + // source
                 "ff020000000000000000000000000001" + // dest
                 "abcdef01"                           // payload
        assertThat(decompressHex(input)).isEqualTo(output.decodeHex())

        // TF: 11, NH: 0, HLIM: 11, CID: 0, SAC: 0, SAM: 10, M: 1, DAC: 0, DAM: 01
        input  = "7b29" +
                 "44" +                               // next header
                 "1234" +                             // source
                 "02abcdef1234" +                     // dest
                 "abcdef01"                           // payload

        output = "6" +                                // version
                 "00" +                               // traffic class
                 "00000" +                            // flow label
                 "0004" +                             // payload length
                 "44" +                               // next header
                 "ff" +                               // hop limit
                 "fe80000000000000000000fffe001234" + // source
                 "ff02000000000000000000abcdef1234" + // dest
                 "abcdef01"                           // payload
        assertThat(decompressHex(input)).isEqualTo(output.decodeHex())

        // TF: 11, NH: 0, HLIM: 11, CID: 0, SAC: 0, SAM: 10, M: 1, DAC: 0, DAM: 10
        input  = "7b2a" +
                 "44" +                               // next header
                 "1234" +                             // source
                 "ee123456" +                         // dest
                 "abcdef01"                           // payload

        output = "6" +                                // version
                 "00" +                               // traffic class
                 "00000" +                            // flow label
                 "0004" +                             // payload length
                 "44" +                               // next header
                 "ff" +                               // hop limit
                 "fe80000000000000000000fffe001234" + // source
                 "ffee0000000000000000000000123456" + // dest
                 "abcdef01"                           // payload
        assertThat(decompressHex(input)).isEqualTo(output.decodeHex())

        // TF: 11, NH: 0, HLIM: 11, CID: 0, SAC: 0, SAM: 10, M: 1, DAC: 0, DAM: 11
        input  = "7b2b" +
                 "44" +                               // next header
                 "1234" +                             // source
                 "89" +                               // dest
                 "abcdef01"                           // payload

        output = "6" +                                // version
                 "00" +                               // traffic class
                 "00000" +                            // flow label
                 "0004" +                             // payload length
                 "44" +                               // next header
                 "ff" +                               // hop limit
                 "fe80000000000000000000fffe001234" + // source
                 "ff020000000000000000000000000089" + // dest
                 "abcdef01"                           // payload
        assertThat(decompressHex(input)).isEqualTo(output.decodeHex())
    }

    @Test
    fun testHeaderCompression() {
        val input  = "60120304000011fffe800000000000000000000000000001fe800000000000000000000000000002"
        val output = "60000102030411fffe800000000000000000000000000001fe800000000000000000000000000002"
        assertThat(compressHex(input)).isEqualTo(output.decodeHex())
    }
}
