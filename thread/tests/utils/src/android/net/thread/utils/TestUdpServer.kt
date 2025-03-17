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
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.Ipv4Header
import com.android.net.module.util.structs.UdpHeader
import com.android.testutils.PollPacketReader
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * A class that simulates a UDP server that replies to incoming UDP messages.
 *
 * @param packetReader the packet reader to poll UDP requests from
 * @param serverAddress the address and port of the UDP server
 */
abstract class TestUdpServer(
    private val packetReader: PollPacketReader,
    private val serverAddress: InetSocketAddress,
) {
    private val TAG = TestUdpServer::class.java.simpleName
    private var workerThread: Thread? = null

    /**
     * Starts the UDP server to respond to UDP messages.
     *
     * <p> The server polls the UDP messages from the {@code packetReader} and responds with a
     * message built by {@code buildResponse}. The server will automatically stop when it fails to
     * poll a UDP request within the timeout (3000 ms, as defined in IntegrationTestUtils).
     */
    fun start() {
        workerThread = thread {
            var requestPacket: ByteArray
            while (true) {
                requestPacket = pollForUdpPacket() ?: break
                val buf = ByteBuffer.wrap(requestPacket)
                packetReader.sendResponse(buildResponse(buf) ?: break)
            }
        }
    }

    /** Stops the UDP server. */
    fun stop() {
        workerThread?.join()
    }

    /**
     * Builds the UDP response for the given UDP request.
     *
     * @param ipv4Header the IPv4 header of the UDP request
     * @param udpHeader the UDP header of the UDP request
     * @param udpPayload the payload of the UDP request
     * @return the UDP response
     */
    abstract fun buildResponse(
        requestIpv4Header: Ipv4Header,
        requestUdpHeader: UdpHeader,
        requestUdpPayload: ByteArray,
    ): ByteBuffer?

    private fun pollForUdpPacket(): ByteArray? {
        val filter =
            fun(packet: ByteArray): Boolean {
                val buf = ByteBuffer.wrap(packet)
                val ipv4Header = Struct.parse(Ipv4Header::class.java, buf) ?: return false
                val udpHeader = Struct.parse(UdpHeader::class.java, buf) ?: return false
                return ipv4Header.dstIp == serverAddress.address &&
                    udpHeader.dstPort == serverAddress.port
            }
        return pollForPacket(packetReader, filter)
    }

    private fun buildResponse(requestPacket: ByteBuffer): ByteBuffer? {
        val requestIpv4Header = Struct.parse(Ipv4Header::class.java, requestPacket) ?: return null
        val requestUdpHeader = Struct.parse(UdpHeader::class.java, requestPacket) ?: return null
        val remainingRequestPacket = ByteArray(requestPacket.remaining())
        requestPacket.get(remainingRequestPacket)

        return buildResponse(requestIpv4Header, requestUdpHeader, remainingRequestPacket)
    }
}
