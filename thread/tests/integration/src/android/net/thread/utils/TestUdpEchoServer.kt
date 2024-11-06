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
import com.android.net.module.util.PacketBuilder
import com.android.net.module.util.structs.Ipv4Header
import com.android.net.module.util.structs.UdpHeader
import com.android.testutils.PollPacketReader
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * A class that simulates a UDP echo server that replies to incoming UDP message with the same
 * payload.
 *
 * @param packetReader the packet reader to poll UDP requests from
 * @param serverAddress the address and port of the UDP server
 */
class TestUdpEchoServer(
    private val packetReader: PollPacketReader,
    private val serverAddress: InetSocketAddress,
) : TestUdpServer(packetReader, serverAddress) {
    companion object {
        private val TAG = TestUdpEchoServer::class.java.simpleName
    }

    override fun buildResponse(
        requestIpv4Header: Ipv4Header,
        requestUdpHeader: UdpHeader,
        requestUdpPayload: ByteArray,
    ): ByteBuffer? {
        val buf =
            PacketBuilder.allocate(
                false /* hasEther */,
                IPPROTO_IP,
                IPPROTO_UDP,
                requestUdpPayload.size,
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
        buf.put(requestUdpPayload)

        return packetBuilder.finalizePacket()
    }
}
