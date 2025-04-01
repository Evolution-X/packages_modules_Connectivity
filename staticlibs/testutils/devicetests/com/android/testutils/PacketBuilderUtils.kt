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

package com.android.testutils

import android.net.IpPrefix
import android.net.MacAddress
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.EnumSet

interface Packet {
    fun toByteArray(): ByteArray
}

interface L3Packet : Packet {
    val etherType: Short
}

interface L4Packet : Packet {
    val proto: Byte
    val size: Short
}

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

/**
 * Class that facilitates the creation of RA packets.
 *
 * Default argument values reflect the rfc4861 defaults where applicable.
 */
class RaPkt(
    private val routerLft: Short,
    private val reachableTime: Int,
    private val retransTimer: Int,
    private val flags: EnumSet<RaFlags>,
) : L4Packet {
    constructor(
        routerLft: Short = 1800,
        reachableTime: Int = 0,
        retransTimer: Int = 0,
        flags: String = "",
    ) : this(routerLft, reachableTime, retransTimer, enumSetOfFlags<RaFlags>(flags))

    enum class RaFlags(override val value: Int) : Flags {
        M(0x80),
        O(0x40),
    }

    override val proto: Byte = 58
    override val size: Short
      get() = outputStream.size().toShort()
    private val outputStream = ByteArrayOutputStream()

    init {
        val raHeader = ByteBuffer.allocate(16)
        raHeader.put(134.toByte()) // Type = 134 (Router Advertisement)
        raHeader.put(0) // Code = 0
        raHeader.putShort(0) // Checksum = 0 (ignored)
        raHeader.put(255.toByte()) // Cur Hop Limit
        raHeader.put(flags.toByte())
        raHeader.putShort(routerLft)
        raHeader.putInt(reachableTime)
        raHeader.putInt(retransTimer)
        raHeader.flip()
        outputStream.write(raHeader.array())
    }

    enum class PioFlags(override val value: Int) : Flags {
        L(0x80),
        A(0x40),
        R(0x20),
        P(0x10),
    }

    fun addPioOption(
            prefix: IpPrefix,
            validLft: Int,
            preferredLft: Int,
            flags: EnumSet<PioFlags>,
    ) {
        if (!prefix.isIPv6()) throw IllegalArgumentException("Invalid prefix")
        val pio = ByteBuffer.allocate(32)
        pio.put(3) // Type = 3
        pio.put(4) // Length = 4 (*8)
        pio.put(prefix.getPrefixLength().toByte()) // Prefix Length
        pio.put(flags.toByte()) // Flags
        pio.putInt(validLft) // Valid Lifetime
        pio.putInt(preferredLft) // Preferred Lifetime
        pio.putInt(0) // Reserved2
        pio.put(prefix.getRawAddress())
        pio.flip()
        outputStream.write(pio.array())
    }

    fun addPioOption(
            prefix: String,
            validLft: Int = 2592000,
            preferredLft: Int = 604800,
            flags: String = "L",
    ) {
        addPioOption(IpPrefix(prefix), validLft, preferredLft, enumSetOfFlags<PioFlags>(flags))
    }

    fun addRdnssOption(dns: List<Inet6Address>, lft: Int) {
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
    }

    fun addRdnssOption(dns: String, lft: Int = 1800) {
        val dnsServers = dns.split(",").map { InetAddress.getByName(it) as Inet6Address }
        addRdnssOption(dnsServers, lft)
    }

    fun addRioOption(prefix: IpPrefix, lft: Int) {
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
    }

    fun addRioOption(prefix: String, lft: Int = 1800) {
        addRioOption(IpPrefix(prefix), lft)
    }

    fun addPref64Option(prefix: IpPrefix, lft: Int) {
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
    }

    fun addPref64Option(prefix: String, lft: Int = 1800) {
        addPref64Option(IpPrefix(prefix), lft)
    }

    override fun toByteArray() = outputStream.toByteArray()
}

/** Class that facilitates the creation of IPv6 packets. */
class Ip6Pkt(
        private val src: Inet6Address,
        private val dst: Inet6Address,
        private val payload: L4Packet,
) : L3Packet {
    constructor(src: String, dst: String, payload: L4Packet) : this(
            InetAddress.getByName(src) as Inet6Address,
            InetAddress.getByName(dst) as Inet6Address,
            payload,
    )

    override val etherType = 0x86dd.toShort()

    private val tc = 0
    private val flowlabel = 0
    private val hlim = 255.toByte()

    private val bytes: ByteArray

    init {
        val ipv6Header = ByteBuffer.allocate(40)
        ipv6Header.putInt((6 shl 28) or (tc shl 20) or flowlabel)
        ipv6Header.putShort(payload.size)
        ipv6Header.put(payload.proto)
        ipv6Header.put(hlim)
        ipv6Header.put(src.getAddress())
        ipv6Header.put(dst.getAddress())
        ipv6Header.flip()

        val outputStream = ByteArrayOutputStream()
        outputStream.write(ipv6Header.array())
        outputStream.write(payload.toByteArray())
        bytes = outputStream.toByteArray()
    }

    override fun toByteArray() = bytes
}

/**
 * Class that facilitates the creation of ethernet packets.
 *
 * Example code:
 *
 * <pre>
 * {@code
 * val ra = RaPkt(routerLft = 50, reachableTime = 100, flags = "O")
 * ra.addPioOption(prefix = "2001:db8::1/64", flags = "LA")
 * val ipv6 = Ip6Pkt(src = "fe80::1", dst = "fe80::2", payload = ra)
 * val ether = EtherPkt(src = "1:2:3:4:5:6", dst = "1:1:1:1:1:1", payload = ipv6)
 * }
 * </pre>
 **/
class EtherPkt(
        dst: MacAddress,
        src: MacAddress,
        payload: L3Packet,
    ) : Packet {
    constructor(dst: String, src: String, payload: L3Packet) :
        this(MacAddress.fromString(dst), MacAddress.fromString(src), payload)

    val bytes: ByteArray

    init {
        val ethernetHeader = ByteBuffer.allocate(14)
        ethernetHeader.put(dst.toByteArray())
        ethernetHeader.put(src.toByteArray())
        ethernetHeader.putShort(payload.etherType)
        ethernetHeader.flip()

        val outputStream = ByteArrayOutputStream()
        outputStream.write(ethernetHeader.array())
        outputStream.write(payload.toByteArray())
        bytes = outputStream.toByteArray()
    }

    override fun toByteArray() = bytes
}
