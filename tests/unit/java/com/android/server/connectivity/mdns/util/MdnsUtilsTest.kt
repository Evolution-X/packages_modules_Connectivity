/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.android.server.connectivity.mdns.util

import android.net.InetAddresses
import android.os.Build
import com.android.server.connectivity.mdns.MdnsConstants
import com.android.server.connectivity.mdns.MdnsConstants.FLAG_TRUNCATED
import com.android.server.connectivity.mdns.MdnsConstants.IPV4_SOCKET_ADDR
import com.android.server.connectivity.mdns.MdnsConstants.IPV6_SOCKET_ADDR
import com.android.server.connectivity.mdns.MdnsPacket
import com.android.server.connectivity.mdns.MdnsPacketReader
import com.android.server.connectivity.mdns.MdnsPointerRecord
import com.android.server.connectivity.mdns.MdnsRecord
import com.android.server.connectivity.mdns.util.MdnsUtils.createQueryDatagramPackets
import com.android.server.connectivity.mdns.util.MdnsUtils.truncateServiceName
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.DatagramPacket
import kotlin.test.assertContentEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsUtilsTest {

    @Test
    fun testTruncateServiceName() {
        assertEquals(truncateServiceName("测试abcde", 7), "测试a")
        assertEquals(truncateServiceName("测试abcde", 100), "测试abcde")
    }

    @Test
    fun testTypeEqualsOrIsSubtype() {
        assertTrue(MdnsUtils.typeEqualsOrIsSubtype(
            arrayOf("_type", "_tcp", "local"),
            arrayOf("_type", "_TCP", "local")
        ))
        assertTrue(MdnsUtils.typeEqualsOrIsSubtype(
            arrayOf("_type", "_tcp", "local"),
            arrayOf("a", "_SUB", "_type", "_TCP", "local")
        ))
        assertFalse(MdnsUtils.typeEqualsOrIsSubtype(
            arrayOf("_sub", "_type", "_tcp", "local"),
                arrayOf("_type", "_TCP", "local")
        ))
        assertFalse(MdnsUtils.typeEqualsOrIsSubtype(
                arrayOf("a", "_other", "_type", "_tcp", "local"),
                arrayOf("a", "_SUB", "_type", "_TCP", "local")
        ))
    }

    @Test
    fun testCreateQueryDatagramPackets() {
        // Question data bytes:
        // Name label(17)(duplicated labels) + PTR type(2) + cacheFlush(2) = 21
        //
        // Known answers data bytes:
        // Name label(17)(duplicated labels) + PTR type(2) + cacheFlush(2) + receiptTimeMillis(4)
        // + Data length(2) + Pointer data(18)(duplicated labels) = 45
        val questions = mutableListOf<MdnsRecord>()
        val knownAnswers = mutableListOf<MdnsRecord>()
        for (i in 1..100) {
            questions.add(MdnsPointerRecord(arrayOf("_testservice$i", "_tcp", "local"), false))
            knownAnswers.add(MdnsPointerRecord(
                    arrayOf("_testservice$i", "_tcp", "local"),
                    0L,
                    false,
                    4_500_000L,
                    arrayOf("MyTestService$i", "_testservice$i", "_tcp", "local")
            ))
        }
        // MdnsPacket data bytes:
        // Questions(21 * 100) + Answers(45 * 100) = 6600 -> at least 5 packets
        val query = MdnsPacket(
                MdnsConstants.FLAGS_QUERY,
                questions as List<MdnsRecord>,
                knownAnswers as List<MdnsRecord>,
                emptyList(),
                emptyList()
        )
        // Expect the oversize MdnsPacket to be separated into 5 DatagramPackets.
        val bufferSize = 1500
        val packets = createQueryDatagramPackets(
                ByteArray(bufferSize),
                query,
                MdnsConstants.IPV4_SOCKET_ADDR
        )
        assertEquals(5, packets.size)
        assertTrue(packets.all { packet -> packet.length < bufferSize })

        val mdnsPacket = createMdnsPacketFromMultipleDatagramPackets(packets)
        assertEquals(query.flags, mdnsPacket.flags)
        assertContentEquals(query.questions, mdnsPacket.questions)
        assertContentEquals(query.answers, mdnsPacket.answers)
    }

    private fun createMdnsPacketFromMultipleDatagramPackets(
            packets: List<DatagramPacket>
    ): MdnsPacket {
        var flags = 0
        val questions = mutableListOf<MdnsRecord>()
        val answers = mutableListOf<MdnsRecord>()
        for ((index, packet) in packets.withIndex()) {
            val mdnsPacket = MdnsPacket.parse(MdnsPacketReader(packet))
            if (index != packets.size - 1) {
                assertTrue((mdnsPacket.flags and FLAG_TRUNCATED) == FLAG_TRUNCATED)
            }
            flags = mdnsPacket.flags
            questions.addAll(mdnsPacket.questions)
            answers.addAll(mdnsPacket.answers)
        }
        return MdnsPacket(flags, questions, answers, emptyList(), emptyList())
    }

    @Test
    fun testCheckAllPacketsWithSameAddress() {
        val buffer = ByteArray(10)
        val v4Packet = DatagramPacket(buffer, buffer.size, IPV4_SOCKET_ADDR)
        val otherV4Packet = DatagramPacket(
            buffer,
            buffer.size,
            InetAddresses.parseNumericAddress("192.0.2.1"),
            1234
        )
        val v6Packet = DatagramPacket(ByteArray(10), 10, IPV6_SOCKET_ADDR)
        val otherV6Packet = DatagramPacket(
            buffer,
            buffer.size,
            InetAddresses.parseNumericAddress("2001:db8::"),
            1234
        )
        assertTrue(MdnsUtils.checkAllPacketsWithSameAddress(listOf()))
        assertTrue(MdnsUtils.checkAllPacketsWithSameAddress(listOf(v4Packet)))
        assertTrue(MdnsUtils.checkAllPacketsWithSameAddress(listOf(v4Packet, v4Packet)))
        assertFalse(MdnsUtils.checkAllPacketsWithSameAddress(listOf(v4Packet, otherV4Packet)))
        assertTrue(MdnsUtils.checkAllPacketsWithSameAddress(listOf(v6Packet)))
        assertTrue(MdnsUtils.checkAllPacketsWithSameAddress(listOf(v6Packet, v6Packet)))
        assertFalse(MdnsUtils.checkAllPacketsWithSameAddress(listOf(v6Packet, otherV6Packet)))
        assertFalse(MdnsUtils.checkAllPacketsWithSameAddress(listOf(v4Packet, v6Packet)))
    }
}
