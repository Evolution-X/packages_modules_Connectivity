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

import android.net.thread.utils.IntegrationTestUtils.pollForPacket
import android.system.OsConstants.IPPROTO_IP
import android.system.OsConstants.IPPROTO_UDP
import com.android.net.module.util.DnsPacket
import com.android.net.module.util.PacketBuilder
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.Ipv4Header
import com.android.net.module.util.structs.UdpHeader
import com.android.testutils.PollPacketReader
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

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
    private val answerRecords: List<DnsPacket.DnsRecord>,
) {
    private val TAG = TestDnsServer::class.java.simpleName
    private val DNS_UDP_PORT = 53
    private var workerThread: Thread? = null

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

    /**
     * Starts the DNS server to respond to DNS requests.
     *
     * <p> The server polls the DNS requests from the {@code packetReader} and responds with the
     * {@code answerRecords}. The server will automatically stop when it fails to poll a DNS request
     * within the timeout (3000 ms, as defined in IntegrationTestUtils).
     */
    fun start() {
        workerThread = thread {
            var requestPacket: ByteArray
            while (true) {
                requestPacket = pollForDnsPacket() ?: break
                val buf = ByteBuffer.wrap(requestPacket)
                packetReader.sendResponse(buildDnsResponse(buf, answerRecords))
            }
        }
    }

    /** Stops the DNS server. */
    fun stop() {
        workerThread?.join()
    }

    private fun pollForDnsPacket(): ByteArray? {
        val filter =
            fun(packet: ByteArray): Boolean {
                val buf = ByteBuffer.wrap(packet)
                val ipv4Header = Struct.parse(Ipv4Header::class.java, buf) ?: return false
                val udpHeader = Struct.parse(UdpHeader::class.java, buf) ?: return false
                return ipv4Header.dstIp == serverAddress && udpHeader.dstPort == DNS_UDP_PORT
            }
        return pollForPacket(packetReader, filter)
    }

    private fun buildDnsResponse(
        requestPacket: ByteBuffer,
        serverAnswers: List<DnsPacket.DnsRecord>,
    ): ByteBuffer? {
        val requestIpv4Header = Struct.parse(Ipv4Header::class.java, requestPacket) ?: return null
        val requestUdpHeader = Struct.parse(UdpHeader::class.java, requestPacket) ?: return null
        val remainingRequestPacket = ByteArray(requestPacket.remaining())
        requestPacket.get(remainingRequestPacket)
        val requestDnsPacket = TestDnsPacket(remainingRequestPacket)
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
