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

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses.parseNumericAddress
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.net.TestNetworkInterface
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.thread.ActiveOperationalDataset
import android.net.thread.ThreadConfiguration
import android.net.thread.ThreadNetworkController
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.system.OsConstants
import android.system.OsConstants.IPPROTO_ICMP
import androidx.test.core.app.ApplicationProvider
import com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow
import com.android.net.module.util.IpUtils
import com.android.net.module.util.NetworkStackConstants
import com.android.net.module.util.NetworkStackConstants.ICMP_CHECKSUM_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV4_CHECKSUM_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV4_HEADER_MIN_LEN
import com.android.net.module.util.NetworkStackConstants.IPV4_LENGTH_OFFSET
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.Icmpv4Header
import com.android.net.module.util.structs.Icmpv6Header
import com.android.net.module.util.structs.Ipv4Header
import com.android.net.module.util.structs.Ipv6Header
import com.android.net.module.util.structs.PrefixInformationOption
import com.android.net.module.util.structs.RaHeader
import com.android.testutils.PollPacketReader
import com.android.testutils.TestNetworkTracker
import com.android.testutils.initTestNetwork
import com.android.testutils.runAsShell
import com.android.testutils.waitForIdle
import com.google.common.io.BaseEncoding
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.lang.Byte.toUnsignedInt
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Predicate
import java.util.function.Supplier
import org.junit.Assert

/** Utilities for Thread integration tests. */
object IntegrationTestUtils {
    // The timeout of join() after restarting ot-daemon. The device needs to send 6 Link Request
    // every 5 seconds, followed by 4 Parent Request every second. So this value needs to be 40
    // seconds to be safe
    @JvmField
    val RESTART_JOIN_TIMEOUT: Duration = Duration.ofSeconds(40)

    @JvmField
    val JOIN_TIMEOUT: Duration = Duration.ofSeconds(30)

    @JvmField
    val LEAVE_TIMEOUT: Duration = Duration.ofSeconds(2)

    @JvmField
    val CALLBACK_TIMEOUT: Duration = Duration.ofSeconds(1)

    @JvmField
    val SERVICE_DISCOVERY_TIMEOUT: Duration = Duration.ofSeconds(20)

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset init new".
    private val DEFAULT_DATASET_TLVS: ByteArray = BaseEncoding.base16().decode(
        ("0E080000000000010000000300001335060004001FFFE002"
                + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                + "B9D351B40C0402A0FFF8")
    )

    @JvmField
    val DEFAULT_DATASET: ActiveOperationalDataset =
        ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS)

    @JvmField
    val DEFAULT_CONFIG = ThreadConfiguration.Builder().build()

    /**
     * Waits for the given [Supplier] to be true until given timeout.
     *
     * @param condition the condition to check
     * @param timeout the time to wait for the condition before throwing
     * @throws TimeoutException if the condition is still not met when the timeout expires
     */
    @JvmStatic
    @Throws(TimeoutException::class)
    fun waitFor(condition: Supplier<Boolean>, timeout: Duration) {
        val intervalMills: Long = 500
        val timeoutMills = timeout.toMillis()

        var i: Long = 0
        while (i < timeoutMills) {
            if (condition.get()) {
                return
            }
            SystemClock.sleep(intervalMills)
            i += intervalMills
        }
        if (condition.get()) {
            return
        }
        throw TimeoutException("The condition failed to become true in $timeout")
    }

    /**
     * Creates a [PollPacketReader] given the [TestNetworkInterface] and [Handler].
     *
     * @param testNetworkInterface the TUN interface of the test network
     * @param handler the handler to process the packets
     * @return the [PollPacketReader]
     */
    @JvmStatic
    fun newPacketReader(
        testNetworkInterface: TestNetworkInterface, handler: Handler
    ): PollPacketReader {
        val fd = testNetworkInterface.fileDescriptor.fileDescriptor
        val reader = PollPacketReader(handler, fd, testNetworkInterface.mtu)
        handler.post { reader.start() }
        handler.waitForIdle(timeoutMs = 5000)
        return reader
    }

    /**
     * Waits for the Thread module to enter any state of the given `deviceRoles`.
     *
     * @param controller the [ThreadNetworkController]
     * @param deviceRoles the desired device roles. See also [     ]
     * @param timeout the time to wait for the expected state before throwing
     * @return the [ThreadNetworkController.DeviceRole] after waiting
     * @throws TimeoutException if the device hasn't become any of expected roles until the timeout
     * expires
     */
    @JvmStatic
    @Throws(TimeoutException::class)
    fun waitForStateAnyOf(
        controller: ThreadNetworkController, deviceRoles: List<Int>, timeout: Duration
    ): Int {
        val future = SettableFuture.create<Int>()
        val callback = ThreadNetworkController.StateCallback { newRole: Int ->
            if (deviceRoles.contains(newRole)) {
                future.set(newRole)
            }
        }
        controller.registerStateCallback(MoreExecutors.directExecutor(), callback)
        try {
            return future[timeout.toMillis(), TimeUnit.MILLISECONDS]
        } catch (e: InterruptedException) {
            throw TimeoutException(
                "The device didn't become an expected role in $timeout: $e.message"
            )
        } catch (e: ExecutionException) {
            throw TimeoutException(
                "The device didn't become an expected role in $timeout: $e.message"
            )
        } finally {
            controller.unregisterStateCallback(callback)
        }
    }

    /**
     * Polls for a packet from a given [PollPacketReader] that satisfies the `filter`.
     *
     * @param packetReader a TUN packet reader
     * @param filter the filter to be applied on the packet
     * @return the first IPv6 packet that satisfies the `filter`. If it has waited for more
     * than 3000ms to read the next packet, the method will return null
     */
    @JvmStatic
    fun pollForPacket(packetReader: PollPacketReader, filter: Predicate<ByteArray>): ByteArray? {
        var packet: ByteArray?
        while ((packetReader.poll(3000 /* timeoutMs */, filter).also { packet = it }) != null) {
            return packet
        }
        return null
    }

    /** Returns `true` if `packet` is an ICMPv4 packet of given `type`.  */
    @JvmStatic
    fun isExpectedIcmpv4Packet(packet: ByteArray, type: Int): Boolean {
        val buf = makeByteBuffer(packet)
        val header = extractIpv4Header(buf) ?: return false
        if (header.protocol != OsConstants.IPPROTO_ICMP.toByte()) {
            return false
        }
        try {
            return Struct.parse(Icmpv4Header::class.java, buf).type == type.toShort()
        } catch (ignored: IllegalArgumentException) {
            // It's fine that the passed in packet is malformed because it's could be sent
            // by anybody.
        }
        return false
    }

    /** Returns `true` if `packet` is an ICMPv6 packet of given `type`.  */
    @JvmStatic
    fun isExpectedIcmpv6Packet(packet: ByteArray, type: Int): Boolean {
        val buf = makeByteBuffer(packet)
        val header = extractIpv6Header(buf) ?: return false
        if (header.nextHeader != OsConstants.IPPROTO_ICMPV6.toByte()) {
            return false
        }
        try {
            return Struct.parse(Icmpv6Header::class.java, buf).type == type.toShort()
        } catch (ignored: IllegalArgumentException) {
            // It's fine that the passed in packet is malformed because it's could be sent
            // by anybody.
        }
        return false
    }

    @JvmStatic
    fun isFrom(packet: ByteArray, src: InetAddress): Boolean {
        when (src) {
            is Inet4Address -> return isFromIpv4Source(packet, src)
            is Inet6Address -> return isFromIpv6Source(packet, src)
            else -> return false
        }
    }

    @JvmStatic
    fun isTo(packet: ByteArray, dest: InetAddress): Boolean {
        when (dest) {
            is Inet4Address -> return isToIpv4Destination(packet, dest)
            is Inet6Address -> return isToIpv6Destination(packet, dest)
            else -> return false
        }
    }

    private fun isFromIpv4Source(packet: ByteArray, src: Inet4Address): Boolean {
        val header = extractIpv4Header(makeByteBuffer(packet))
        return header?.srcIp == src
    }

    private fun isFromIpv6Source(packet: ByteArray, src: Inet6Address): Boolean {
        val header = extractIpv6Header(makeByteBuffer(packet))
        return header?.srcIp == src
    }

    private fun isToIpv4Destination(packet: ByteArray, dest: Inet4Address): Boolean {
        val header = extractIpv4Header(makeByteBuffer(packet))
        return header?.dstIp == dest
    }

    private fun isToIpv6Destination(packet: ByteArray, dest: Inet6Address): Boolean {
        val header = extractIpv6Header(makeByteBuffer(packet))
        return header?.dstIp == dest
    }

    private fun makeByteBuffer(packet: ByteArray): ByteBuffer {
        return ByteBuffer.wrap(packet)
    }

    private fun extractIpv4Header(buf: ByteBuffer): Ipv4Header? {
        try {
            return Struct.parse(Ipv4Header::class.java, buf)
        } catch (ignored: IllegalArgumentException) {
            // It's fine that the passed in packet is malformed because it's could be sent
            // by anybody.
        }
        return null
    }

    private fun extractIpv6Header(buf: ByteBuffer): Ipv6Header? {
        try {
            return Struct.parse(Ipv6Header::class.java, buf)
        } catch (ignored: IllegalArgumentException) {
            // It's fine that the passed in packet is malformed because it's could be sent
            // by anybody.
        }
        return null
    }

    /** Builds an ICMPv4 Echo Reply packet to respond to the given ICMPv4 Echo Request packet. */
    @JvmStatic
    fun buildIcmpv4EchoReply(request: ByteBuffer): ByteBuffer? {
        val requestIpv4Header = Struct.parse(Ipv4Header::class.java, request) ?: return null
        val requestIcmpv4Header = Struct.parse(Icmpv4Header::class.java, request) ?: return null

        val id = request.getShort()
        val seq = request.getShort()

        val payload = ByteBuffer.allocate(4 + request.limit() - request.position())
        payload.putShort(id)
        payload.putShort(seq)
        payload.put(request)
        payload.rewind()

        val ipv4HeaderLen = Struct.getSize(Ipv4Header::class.java)
        val Icmpv4HeaderLen = Struct.getSize(Icmpv4Header::class.java)
        val payloadLen = payload.limit();

        val reply = ByteBuffer.allocate(ipv4HeaderLen + Icmpv4HeaderLen + payloadLen)

        // IPv4 header
        val replyIpv4Header = Ipv4Header(
            0 /* TYPE OF SERVICE */,
            0.toShort().toInt()/* totalLength, calculate later */,
            requestIpv4Header.id,
            requestIpv4Header.flagsAndFragmentOffset,
            0x40 /* ttl */,
            IPPROTO_ICMP.toByte(),
            0.toShort()/* checksum, calculate later */,
            requestIpv4Header.dstIp /* srcIp */,
            requestIpv4Header.srcIp /* dstIp */
        )
        replyIpv4Header.writeToByteBuffer(reply)

        // ICMPv4 header
        val replyIcmpv4Header = Icmpv4Header(
            0 /* type, ICMP_ECHOREPLY */,
            requestIcmpv4Header.code,
            0.toShort() /* checksum, calculate later */
        )
        replyIcmpv4Header.writeToByteBuffer(reply)

        // Payload
        reply.put(payload)
        reply.flip()

        // Populate the IPv4 totalLength field.
        reply.putShort(
            IPV4_LENGTH_OFFSET, (ipv4HeaderLen + Icmpv4HeaderLen + payloadLen).toShort()
        )

        // Populate the IPv4 header checksum field.
        reply.putShort(
            IPV4_CHECKSUM_OFFSET, IpUtils.ipChecksum(reply, 0 /* headerOffset */)
        )

        // Populate the ICMP checksum field.
        reply.putShort(
            IPV4_HEADER_MIN_LEN + ICMP_CHECKSUM_OFFSET, IpUtils.icmpChecksum(
                reply, IPV4_HEADER_MIN_LEN, Icmpv4HeaderLen + payloadLen
            )
        )

        return reply
    }

    /** Returns the Prefix Information Options (PIO) extracted from an ICMPv6 RA message.  */
    @JvmStatic
    fun getRaPios(raMsg: ByteArray?): List<PrefixInformationOption> {
        val pioList = ArrayList<PrefixInformationOption>()

        raMsg ?: return pioList

        val buf = ByteBuffer.wrap(raMsg)
        val ipv6Header = try {
            Struct.parse(Ipv6Header::class.java, buf)
        } catch (e: IllegalArgumentException) {
            // the packet is not IPv6
            return pioList
        }
        if (ipv6Header.nextHeader != OsConstants.IPPROTO_ICMPV6.toByte()) {
            return pioList
        }

        val icmpv6Header = Struct.parse(Icmpv6Header::class.java, buf)
        if (icmpv6Header.type != NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT.toShort()) {
            return pioList
        }

        Struct.parse(RaHeader::class.java, buf)
        while (buf.position() < raMsg.size) {
            val currentPos = buf.position()
            val type = toUnsignedInt(buf.get())
            val length = toUnsignedInt(buf.get())
            if (type == NetworkStackConstants.ICMPV6_ND_OPTION_PIO) {
                val pioBuf = ByteBuffer.wrap(
                    buf.array(), currentPos, Struct.getSize(PrefixInformationOption::class.java)
                )
                val pio = Struct.parse(PrefixInformationOption::class.java, pioBuf)
                pioList.add(pio)

                // Move ByteBuffer position to the next option.
                buf.position(
                    currentPos + Struct.getSize(PrefixInformationOption::class.java)
                )
            } else {
                // The length is in units of 8 octets.
                buf.position(currentPos + (length * 8))
            }
        }
        return pioList
    }

    /**
     * Sends a UDP message to a destination.
     *
     * @param dstAddress the IP address of the destination
     * @param dstPort the port of the destination
     * @param message the message in UDP payload
     * @throws IOException if failed to send the message
     */
    @JvmStatic
    @Throws(IOException::class)
    fun sendUdpMessage(dstAddress: InetAddress, dstPort: Int, message: String) {
        val dstSockAddr: SocketAddress = InetSocketAddress(dstAddress, dstPort)

        DatagramSocket().use { socket ->
            socket.connect(dstSockAddr)
            val msgBytes = message.toByteArray()
            val packet = DatagramPacket(msgBytes, msgBytes.size)
            socket.send(packet)
        }
    }

    @JvmStatic
    fun isInMulticastGroup(interfaceName: String, address: Inet6Address): Boolean {
        val cmd = "ip -6 maddr show dev $interfaceName"
        val output: String = runShellCommandOrThrow(cmd)
        val addressStr = address.hostAddress
        for (line in output.split("\\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            if (line.contains(addressStr)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun getIpv6LinkAddresses(interfaceName: String): List<LinkAddress> {
        val addresses: MutableList<LinkAddress> = ArrayList()
        val cmd = " ip -6 addr show dev $interfaceName"
        val output: String = runShellCommandOrThrow(cmd)

        for (line in output.split("\\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            if (line.contains("inet6")) {
                addresses.add(parseAddressLine(line))
            }
        }

        return addresses
    }

    /** Return the first discovered service of `serviceType`.  */
    @JvmStatic
    @Throws(Exception::class)
    fun discoverService(nsdManager: NsdManager, serviceType: String): NsdServiceInfo {
        val serviceInfoFuture = CompletableFuture<NsdServiceInfo>()
        val listener: NsdManager.DiscoveryListener = object : DefaultDiscoveryListener() {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                serviceInfoFuture.complete(serviceInfo)
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            serviceInfoFuture[SERVICE_DISCOVERY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS]
        } finally {
            nsdManager.stopServiceDiscovery(listener)
        }

        return serviceInfoFuture.get()
    }

    /**
     * Returns the [NsdServiceInfo] when a service instance of `serviceType` gets lost.
     */
    @JvmStatic
    fun discoverForServiceLost(
        nsdManager: NsdManager,
        serviceType: String?,
        serviceInfoFuture: CompletableFuture<NsdServiceInfo?>
    ): NsdManager.DiscoveryListener {
        val listener: NsdManager.DiscoveryListener = object : DefaultDiscoveryListener() {
            override fun onServiceLost(serviceInfo: NsdServiceInfo): Unit {
                serviceInfoFuture.complete(serviceInfo)
            }
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    /** Resolves the service.  */
    @JvmStatic
    @Throws(Exception::class)
    fun resolveService(nsdManager: NsdManager, serviceInfo: NsdServiceInfo): NsdServiceInfo {
        return resolveServiceUntil(nsdManager, serviceInfo) { true }
    }

    /** Returns the first resolved service that satisfies the `predicate`.  */
    @JvmStatic
    @Throws(Exception::class)
    fun resolveServiceUntil(
        nsdManager: NsdManager, serviceInfo: NsdServiceInfo, predicate: Predicate<NsdServiceInfo>
    ): NsdServiceInfo {
        val resolvedServiceInfoFuture = CompletableFuture<NsdServiceInfo>()
        val callback: NsdManager.ServiceInfoCallback = object : DefaultServiceInfoCallback() {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (predicate.test(serviceInfo)) {
                    resolvedServiceInfoFuture.complete(serviceInfo)
                }
            }
        }
        nsdManager.registerServiceInfoCallback(serviceInfo, directExecutor(), callback)
        try {
            return resolvedServiceInfoFuture[
                SERVICE_DISCOVERY_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS]
        } finally {
            nsdManager.unregisterServiceInfoCallback(callback)
        }
    }

    @JvmStatic
    fun getPrefixesFromNetData(netData: String): String {
        val startIdx = netData.indexOf("Prefixes:")
        val endIdx = netData.indexOf("Routes:")
        return netData.substring(startIdx, endIdx)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun getThreadNetwork(timeout: Duration): Network {
        val networkFuture = CompletableFuture<Network>()
        val cm =
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(ConnectivityManager::class.java)
        val networkRequestBuilder =
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
        // Before V, we need to explicitly set `NET_CAPABILITY_LOCAL_NETWORK` capability to request
        // a Thread network.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            networkRequestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
        }
        val networkRequest = networkRequestBuilder.build()
        val networkCallback: ConnectivityManager.NetworkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkFuture.complete(network)
                }
            }
        cm.registerNetworkCallback(networkRequest, networkCallback)
        return networkFuture[timeout.toSeconds(), TimeUnit.SECONDS]
    }

    /**
     * Let the FTD join the specified Thread network and wait for border routing to be available.
     *
     * @return the OMR address
     */
    @JvmStatic
    @Throws(Exception::class)
    fun joinNetworkAndWaitForOmr(
        ftd: FullThreadDevice, dataset: ActiveOperationalDataset
    ): Inet6Address {
        ftd.factoryReset()
        ftd.joinNetwork(dataset)
        ftd.waitForStateAnyOf(listOf("router", "child"), JOIN_TIMEOUT)
        waitFor({ ftd.omrAddress != null }, Duration.ofSeconds(60))
        Assert.assertNotNull(ftd.omrAddress)
        return ftd.omrAddress
    }

    /** Enables Thread and joins the specified Thread network. */
    @JvmStatic
    fun enableThreadAndJoinNetwork(dataset: ActiveOperationalDataset) {
        // TODO: b/323301831 - This is a workaround to avoid unnecessary delay to re-form a network
        OtDaemonController().factoryReset();

        val context: Context = requireNotNull(ApplicationProvider.getApplicationContext());
        val controller = requireNotNull(ThreadNetworkControllerWrapper.newInstance(context));
        controller.setEnabledAndWait(true);
        controller.joinAndWait(dataset);
    }

    /** Leaves the Thread network and disables Thread. */
    @JvmStatic
    fun leaveNetworkAndDisableThread() {
        val context: Context = requireNotNull(ApplicationProvider.getApplicationContext());
        val controller = requireNotNull(ThreadNetworkControllerWrapper.newInstance(context));
        controller.leaveAndWait();
        controller.setEnabledAndWait(false);
    }

    private open class DefaultDiscoveryListener : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
    }

    private open class DefaultServiceInfoCallback : NsdManager.ServiceInfoCallback {
        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {}
        override fun onServiceLost(): Unit {}
        override fun onServiceInfoCallbackUnregistered() {}
    }

    /**
     * Parses a line of output from "ip -6 addr show" into a [LinkAddress].
     *
     * Example line: "inet6 2001:db8:1:1::1/64 scope global deprecated"
     */
    private fun parseAddressLine(line: String): LinkAddress {
        val parts = line.split("\\s+".toRegex()).filter { it.isNotEmpty() }.toTypedArray()
        val addressString = parts[1]
        val pieces = addressString.split("/".toRegex(), limit = 2).toTypedArray()
        val prefixLength = pieces[1].toInt()
        val address = parseNumericAddress(pieces[0])
        val deprecationTimeMillis =
            if (line.contains("deprecated")) SystemClock.elapsedRealtime()
            else LinkAddress.LIFETIME_PERMANENT

        return LinkAddress(
            address, prefixLength,
            0 /* flags */, 0 /* scope */,
            deprecationTimeMillis, LinkAddress.LIFETIME_PERMANENT /* expirationTime */
        )
    }

    /**
     * Stop the ot-daemon by shell command.
     */
    @JvmStatic
    fun stopOtDaemon() {
        runShellCommandOrThrow("stop ot-daemon")
    }
}
