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
// Ktlint forces a blank line between declarations with comments which wastes space in enum
// declarations.
@file:Suppress("ktlint:standard:spacing-between-declarations-with-comments")

package com.android.testutils

import android.net.IpPrefix
import android.net.MacAddress
import com.android.net.module.util.IpUtils
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.EnumSet

class PacketBuilder(outerPacket: Packet, innerPacket: Packet) {
    private data class PacketHolder(
            /** Points to the last encapsulating packet that includes a pseudo header (IPv4 or IPv6)
             * for the purpose of checksum calculation. May be null if no such packet exists. */
            val pseudoHeaderPacket: PseudoHeaderPacket?,
            val packet: Packet,
    )
    private var lastPseudoHeaderPacket: PseudoHeaderPacket? = null
    /** Collects packets in order, where the outermost packet is at index 0. */
    private val packetCollector = ArrayList<PacketHolder>()

    private fun addPacket(p: Packet) {
        if (p is PseudoHeaderPacket) {
            lastPseudoHeaderPacket = p
        }
        packetCollector.add(PacketHolder(lastPseudoHeaderPacket, p))
    }

    init {
        addPacket(outerPacket)
        addPacket(innerPacket)
    }

    operator fun div(p: Packet): PacketBuilder {
        addPacket(p)
        return this
    }

    fun build(): ByteArray {
        var payload: FinalizedPacket? = null
        for (holder in packetCollector.reversed()) {
            payload = holder.packet.build(payload, holder.pseudoHeaderPacket)
        }

        // payload is guaranteed non-null as PacketBuilder is created with at least two packets.
        return payload!!.bytes
    }

    // For ByteArray.toHexString
    @kotlin.ExperimentalStdlibApi
    override fun toString(): String {
        return build().toHexString()
    }
}

/** Interface to support PacketBuilder syntax, i.e. {@code val p = ether / ipv6 / icmpv6} */
interface Packet {
    operator fun div(p: Packet): PacketBuilder {
        return PacketBuilder(this, p)
    }
    fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket
}

interface PseudoHeaderPacket : Packet {
    /**
     * Calculates the partial checksum (i.e. pseudo header checksum) to be used as a seed value for
     * the higher layer checksum calculation.
     */
    fun calculatePseudoHeaderCsum(proto: Byte, length: Int): Int
}

/**
 * Represents a packet that has been built. Depending on the type of packet, it includes additional
 * information on top of the ByteArray.
 */
interface FinalizedPacket {
    val bytes: ByteArray
}

data class L2Packet(override val bytes: ByteArray) : FinalizedPacket
// Some L3 packets can be carried by an L3 packet (e.g. 6in4), so consider adding proto to L3Packet.
data class L3Packet(override val bytes: ByteArray, val etherType: Short) : FinalizedPacket
data class L4Packet(override val bytes: ByteArray, val proto: Byte) : FinalizedPacket

private interface Flags {
    val value: Int
}

private fun <E> EnumSet<E>.toByte(): Byte where E : Enum<E>, E : Flags {
    val result = this.fold(0) { result, next -> result or next.value }
    return result.toByte()
}

private inline fun <reified E> enumSetOfFlags(str: String): EnumSet<E>
        where E : Enum<E>, E : Flags {
    val result = EnumSet.noneOf(E::class.java)
    for (char in str) {
        result.add(enumValueOf<E>(char.uppercase()))
    }
    return result
}

/** Base class that holds the common checksum implementation */
open class Icmp6Pkt : Packet {
    protected val outputStream = ByteArrayOutputStream()

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        // ICMPv6 packets do not carry a payload but require the pseudo-header to calculate their
        // checksum.
        require(payload == null)
        require(pseudo != null)

        val packetBytes = outputStream.toByteArray()
        val packetBuffer = ByteBuffer.wrap(packetBytes)
        var csum = pseudo.calculatePseudoHeaderCsum(58 /* proto */, packetBytes.size)
        csum = IpUtils.checksum(packetBuffer, csum, 0 /*start*/, packetBytes.size)

        // Fixup packetBuffer
        packetBuffer.position(2)
        packetBuffer.putShort(csum.toShort())

        // TODO: calculate checksum
        return L4Packet(proto = 58, bytes = packetBytes)
    }
}

/**
 * Class that facilitates the creation of NA packets.
 *
 * @param target The IPv6 address of the target.
 * @param flags The Neighbor Advertisement flags.
 */
class NaPkt(
    target: Inet6Address,
    flags: EnumSet<NaFlags>,
) : Icmp6Pkt() {
    /**
     * Convenience constructor accepting parameters as Strings.
     *
     * @param target Target IPv6 address as a String; e.g. {@code "2001:db8::1"}.
     * @param flags Neighbor Advertisement flags as a String; e.g. {@code "RS"}
     */
    constructor(target: String, flags: String = "RO") :
        this(Inet6Address.getByName(target) as Inet6Address, enumSetOfFlags<NaFlags>(flags))

    enum class NaFlags(override val value: Int) : Flags {
        /** Router flag: indicates that the sender is a router. */
        R(0x80),
        /** Solicited flag: indicates the advertisement was sent in response to a solicitation. */
        S(0x40),
        /** Override flag: indicates the advertisement should override an existing cache entry. */
        O(0x20),
    }

    init {
        val naHeader = ByteBuffer.allocate(24)
        naHeader.put(136.toByte()) // Type = 136 (Neighbor Advertisement)
        naHeader.put(0) // Code = 0
        naHeader.putShort(0) // Checksum = 0 (ignored)
        naHeader.put(flags.toByte())
        naHeader.put(0) // reserved
        naHeader.putShort(0) // reserved
        naHeader.put(target.address) // Target address
        naHeader.flip()
        outputStream.write(naHeader.array())
    }

    /** Add Target Link-Layer Address Option */
    fun addTllaOption(lla: MacAddress): NaPkt {
        val llao = ByteBuffer.allocate(8)
        llao.put(2) // Type = 2 (Target Link-layer Address)
        llao.put(1) // Length in units of 8 octets
        llao.put(lla.toByteArray())
        llao.flip()
        outputStream.write(llao.array())
        return this
    }

    /**
     * Add Target Link-Layer Address Option
     *
     * @param lla Link-Layer Address as a String; e.g. {@code "0:0:5e:0:53:0"}
     */
    fun addTllaOption(lla: String): NaPkt {
        return addTllaOption(MacAddress.fromString(lla))
    }
}

/**
 * Class that facilitates the creation of RA packets.
 *
 * Default argument values reflect the rfc4861 defaults where applicable.
 *
 * @param routerLft lifetime of the default router in seconds.
 * @param reachableTime The time, in milliseconds, that a node assumes a neighbor is reachable.
 * @param retransTimer The time, in milliseconds, between retransmitted NS messages.
 * @param flags The Router Advertisement flags.
 */
class RaPkt(
    private val routerLft: Short,
    private val reachableTime: Int,
    private val retransTimer: Int,
    private val flags: EnumSet<RaFlags>,
) : Icmp6Pkt() {
    /**
     * Convenience constructor accepting flags parameter as String
     *
     * @param routerLft lifetime of the default router in seconds.
     * @param reachableTime The time, in milliseconds, that a node assumes a neighbor is reachable.
     * @param retransTimer The time, in milliseconds, between retransmitted NS messages.
     * @param flags The Router Advertisement flags as a String; e.g. {@code "MO"}. Defaults to "".
     */
    constructor(
        routerLft: Short = 1800,
        reachableTime: Int = 0,
        retransTimer: Int = 0,
        flags: String = "",
    ) : this(routerLft, reachableTime, retransTimer, enumSetOfFlags<RaFlags>(flags))

    enum class RaFlags(override val value: Int) : Flags {
        /** Managed address configuration: indicates addresses are available via DHCPv6. */
        M(0x80),
        /** Other configuration: indicates other configuration info is available via DHCPv6. */
        O(0x40),
    }

    init {
        val raHeader = ByteBuffer.allocate(16)
        raHeader.put(134.toByte()) // Type = 134 (Router Advertisement)
        raHeader.put(0) // Code = 0
        raHeader.putShort(0) // Checksum = 0 (filled in later)
        raHeader.put(255.toByte()) // Cur Hop Limit
        raHeader.put(flags.toByte())
        raHeader.putShort(routerLft)
        raHeader.putInt(reachableTime)
        raHeader.putInt(retransTimer)
        raHeader.flip()
        outputStream.write(raHeader.array())
    }

    enum class PioFlags(override val value: Int) : Flags {
        /** On-Link: indicates that this prefix can be used for on-link determination. */
        L(0x80),
        /** Autonomous address-configuration: indicates that this prefix can be used for SLAAC. */
        A(0x40),
        /** Router Address: obsolete. */
        R(0x20),
        /** PD Preferred: indicates that the network prefers the use of DHCPv6-PD over SLAAC. */
        P(0x10),
    }

    /**
     * Add a Prefix Information Option
     *
     * @param prefix The prefix of an IPv6 address.
     * @param validLft The time, in seconds, that the prefix is valid for on-link determination.
     * @param preferredLft The time, in seconds, that SLAAC-generated addresses remain preferred.
     * @param flags The Prefix Information Option flags.
     */
    fun addPioOption(
            prefix: IpPrefix,
            validLft: Int,
            preferredLft: Int,
            flags: EnumSet<PioFlags>,
    ): RaPkt {
        if (!prefix.isIPv6()) throw IllegalArgumentException("Invalid prefix")
        val pio = ByteBuffer.allocate(32)
        pio.put(3) // Type = 3
        pio.put(4) // Length = 4 (*8)
        pio.put(prefix.prefixLength.toByte()) // Prefix Length
        pio.put(flags.toByte()) // Flags
        pio.putInt(validLft) // Valid Lifetime
        pio.putInt(preferredLft) // Preferred Lifetime
        pio.putInt(0) // Reserved2
        pio.put(prefix.rawAddress)
        pio.flip()
        outputStream.write(pio.array())
        return this
    }

    /**
     * Add a Prefix Information Option
     *
     * @param prefix The IPv6 prefix as a String; e.g. {@code "2001:db8::/64"}
     * @param validLft The time, in seconds, that the prefix is valid for on-link determination.
     * @param preferredLft The time, in seconds, that SLAAC-generated addresses remain preferred.
     * @param flags The PIO flags as a String; e.g. {@code "LA"}
     */
    fun addPioOption(
            prefix: String,
            validLft: Int = 2592000,
            preferredLft: Int = 604800,
            flags: String = "L",
    ): RaPkt {
        return addPioOption(
                IpPrefix(prefix),
                validLft,
                preferredLft,
                enumSetOfFlags<PioFlags>(flags)
        )
    }

    /** Add a Recursive DNS Server Option
     *
     * @param dns The list of DNS servers.
     * @param lft The time, in seconds, over which the DNS servers may be used for resolution.
     */
    fun addRdnssOption(dns: List<Inet6Address>, lft: Int): RaPkt {
        val length = 1 + (dns.size * 2)
        val rdnss = ByteBuffer.allocate(length * 8) // length is in units of 8 octets.
        rdnss.put(25) // Type = 25
        rdnss.put(length.toByte()) // Length = 1 + number of dns servers * 2 in units of 8 octets.
        rdnss.putShort(0) // Reserved
        rdnss.putInt(lft)
        for (dnsServer in dns) {
            rdnss.put(dnsServer.address)
        }
        rdnss.flip()
        outputStream.write(rdnss.array())
        return this
    }

    /** Add a Recursive DNS Server Option
     *
     * @param dns The list of DNS servers as a String; e.g. {@code "2001:db8:1,2001:db8:2"}
     * @param lft The time, in seconds, over which the DNS servers may be used for resolution.
     */
    fun addRdnssOption(dns: String, lft: Int = 1800): RaPkt {
        val dnsServers = dns.split(",").map { InetAddress.getByName(it) as Inet6Address }
        return addRdnssOption(dnsServers, lft)
    }

    /** Add a Route Information Option
     *
     * @param prefix The IPv6 prefix of the route.
     * @param lft The lifetime of the route in seconds.
     */
    fun addRioOption(prefix: IpPrefix, lft: Int): RaPkt {
        val rio = ByteBuffer.allocate(24)
        rio.put(24) // Type = 24
        rio.put(3) // Length = 3 -- TODO: support variable prefix length
        rio.put(prefix.prefixLength.toByte())
        // TODO: support setting pref
        rio.put(0) // res (3 bits) | pref (2 bits) | res (3 bits)
        rio.putInt(lft)
        rio.put(prefix.rawAddress)
        rio.flip()
        outputStream.write(rio.array())
        return this
    }

    /** Add a Route Information Option
     *
     * @param prefix The IPv6 prefix of the route as a String; e.g. {@code "2001:db8::/48"}
     * @param lft The lifetime of the route in seconds.
     */
    fun addRioOption(prefix: String, lft: Int = 1800): RaPkt {
        return addRioOption(IpPrefix(prefix), lft)
    }

    /** Add a PREF64 Option
     *
     * @param prefix The PREF64 prefix.
     * @param lft The lifetime of the PREF64 prefix in seconds.
     */
    fun addPref64Option(prefix: IpPrefix, lft: Int): RaPkt {
        val pref64 = ByteBuffer.allocate(16)
        pref64.put(38) // Type = 38
        pref64.put(2) // Length = 2 (*8)
        val scaledLifetime = lft and 0xfff8
        val plc = when (prefix.prefixLength) {
            96 -> 0
            64 -> 1
            56 -> 2
            48 -> 3
            40 -> 4
            32 -> 5
            else -> throw IllegalArgumentException("Invalid pref64 prefix length")
        }
        pref64.putShort((scaledLifetime or plc).toShort()) // Scaled lft (13 bits) | plc (3 bits)
        pref64.put(prefix.rawAddress, 0 /*offset*/, 12 /*length*/) // highest 96 bits of prefix.
        pref64.flip()
        outputStream.write(pref64.array())
        return this
    }

    /** Add a PREF64 Option
     *
     * @param prefix The PREF64 prefix as a String; e.g. {@code "2001:db8::/64"}
     * @param lft The lifetime of the PREF64 prefix in seconds.
     */
    fun addPref64Option(prefix: String, lft: Int = 1800): RaPkt {
        return addPref64Option(IpPrefix(prefix), lft)
    }
}

/** Class that facilitates the creation of IPv6 packets. */
class Ip6Pkt(
        private val src: Inet6Address,
        private val dst: Inet6Address,
) : PseudoHeaderPacket {
    constructor(src: String, dst: String) : this(
            InetAddress.getByName(src) as Inet6Address,
            InetAddress.getByName(dst) as Inet6Address,
    )

    private val tc = 0
    private val flowlabel = 0
    private val hlim = 255.toByte()

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(payload != null)
        require(payload is L4Packet)

        val ipv6Header = ByteBuffer.allocate(40)
        ipv6Header.putInt((6 shl 28) or (tc shl 20) or flowlabel)
        ipv6Header.putShort(payload.bytes.size.toShort())
        ipv6Header.put(payload.proto)
        ipv6Header.put(hlim)
        ipv6Header.put(src.address)
        ipv6Header.put(dst.address)
        ipv6Header.flip()

        val outputStream = ByteArrayOutputStream()
        outputStream.write(ipv6Header.array())
        outputStream.write(payload.bytes)
        return L3Packet(etherType = 0x86dd.toShort(), bytes = outputStream.toByteArray())
    }

    override fun calculatePseudoHeaderCsum(proto: Byte, length: Int): Int {
        // Calculates the checksum over the src and dst IPv6 addresses.
        // TODO: assemble IPv6 header in constructor and let build() update payload length and
        // proto instead.
        val buffer = ByteBuffer.allocate((2 * 16) + 4 + 2)
        buffer.put(src.address)
        buffer.put(dst.address)
        buffer.putInt(length)
        buffer.putShort(proto.toShort())
        buffer.flip()
        // Note that IpUtils.checksum() flips the result, so it is flipped back here.
        return IpUtils.checksum(buffer, 0 /*seed*/, 0 /*start*/, buffer.limit()) xor 0xffff
    }
}

/**
 * Class that facilitates the creation of ethernet packets.
 *
 * Example code:
 *
 * <pre>
 * {@code
 * val ether = EtherPkt(src = "1:2:3:4:5:6", dst = "1:1:1:1:1:1")
 * val ipv6 = Ip6Pkt(src = "fe80::1", dst = "fe80::2")
 * val ra = RaPkt(routerLft = 50, reachableTime = 100, flags = "O")
 *         .addPioOption(prefix = "2001:db8::1/64", flags = "LA")
 * val p = ether / ipv6 / ra
 * val bytes = p.bytes
 * }
 * </pre>
 **/
class EtherPkt(
        private val dst: MacAddress,
        private val src: MacAddress,
    ) : Packet {
    constructor(dst: String, src: String) :
        this(MacAddress.fromString(dst), MacAddress.fromString(src))

    override fun build(payload: FinalizedPacket?, pseudo: PseudoHeaderPacket?): FinalizedPacket {
        require(payload != null)
        require(payload is L3Packet)

        val ethernetHeader = ByteBuffer.allocate(14)
        ethernetHeader.put(dst.toByteArray())
        ethernetHeader.put(src.toByteArray())
        ethernetHeader.putShort(payload.etherType)
        ethernetHeader.flip()

        val outputStream = ByteArrayOutputStream()
        outputStream.write(ethernetHeader.array())
        outputStream.write(payload.bytes)
        return L2Packet(bytes = outputStream.toByteArray())
    }
}
