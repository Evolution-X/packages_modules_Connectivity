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

package android.net.thread.utils

import android.system.OsConstants.IPPROTO_IP
import android.system.OsConstants.IPPROTO_UDP
import com.android.net.module.util.DnsPacket
import com.android.net.module.util.PacketBuilder
import com.android.net.module.util.structs.Ipv4Header
import com.android.net.module.util.structs.UdpHeader
import com.android.testutils.PollPacketReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * A class that simulates a DNS server.
 *
 * <p>The server responds to DNS requests with the given {@code answerRecords}.
 *
 * @param packetReader the packet reader to poll DNS requests from
 * @param serverAddress the address of the DNS server
 * @param answerRecords the records to respond to the DNS requests
 */
class TestDnsServer(
    private val packetReader: PollPacketReader,
    private val serverAddress: InetAddress,
    private val serverAnswers: List<DnsPacket.DnsRecord>,
) : TestUdpServer(packetReader, InetSocketAddress(serverAddress, DNS_UDP_PORT)) {
    companion object {
        private val TAG = TestDnsServer::class.java.simpleName
        private const val DNS_UDP_PORT = 53
    }

    private class TestDnsPacket : DnsPacket {

        constructor(buf: ByteArray) : super(buf)

        constructor(
            header: DnsHeader,
            qd: List<DnsRecord>,
            an: List<DnsRecord>,
        ) : super(header, qd, an) {}

        val header = super.mHeader
        val records = super.mRecords
    }

    override fun buildResponse(
        requestIpv4Header: Ipv4Header,
        requestUdpHeader: UdpHeader,
        requestUdpPayload: ByteArray,
    ): ByteBuffer? {
        val requestDnsPacket = TestDnsPacket(requestUdpPayload)
        val requestDnsHeader = requestDnsPacket.header

        val answerRecords =
            buildDnsAnswerRecords(requestDnsPacket.records[DnsPacket.QDSECTION], serverAnswers)
        // TODO: return NXDOMAIN if no answer is found.
        val responseFlags = 1 shl 15 // QR bit
        val responseDnsHeader =
            DnsPacket.DnsHeader(
                requestDnsHeader.id,
                responseFlags,
                requestDnsPacket.records[DnsPacket.QDSECTION].size,
                answerRecords.size,
            )
        val responseDnsPacket =
            TestDnsPacket(
                responseDnsHeader,
                requestDnsPacket.records[DnsPacket.QDSECTION],
                answerRecords,
            )

        val buf =
            PacketBuilder.allocate(
                false /* hasEther */,
                IPPROTO_IP,
                IPPROTO_UDP,
                responseDnsPacket.bytes.size,
            )

        val packetBuilder = PacketBuilder(buf)
        packetBuilder.writeIpv4Header(
            requestIpv4Header.tos,
            requestIpv4Header.id,
            requestIpv4Header.flagsAndFragmentOffset,
            0x40 /* ttl */,
            IPPROTO_UDP.toByte(),
            requestIpv4Header.dstIp, /* srcIp */
            requestIpv4Header.srcIp, /* dstIp */
        )
        packetBuilder.writeUdpHeader(
            requestUdpHeader.dstPort.toShort() /* srcPort */,
            requestUdpHeader.srcPort.toShort(), /* dstPort */
        )
        buf.put(responseDnsPacket.bytes)

        return packetBuilder.finalizePacket()
    }

    private fun buildDnsAnswerRecords(
        questions: List<DnsPacket.DnsRecord>,
        serverAnswers: List<DnsPacket.DnsRecord>,
    ): List<DnsPacket.DnsRecord> {
        val answers = ArrayList<DnsPacket.DnsRecord>()
        for (answer in serverAnswers) {
            if (
                questions.any {
                    answer.dName.equals(it.dName, ignoreCase = true) && answer.nsType == it.nsType
                }
            ) {
                answers.add(answer)
            }
        }
        return answers
    }
}
