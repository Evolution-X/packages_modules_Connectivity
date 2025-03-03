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

package com.android.server.net;

import android.util.Log;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class HeaderCompressionUtils {
    private static final String TAG = "L2capHeaderCompressionUtils";
    private static final int IPV6_HEADER_SIZE = 40;

    private static byte[] decodeIpv6Address(ByteBuffer buffer, int mode, boolean isMulticast)
            throws BufferUnderflowException, IOException {
        // Mode is equivalent between SAM and DAM; however, isMulticast only applies to DAM.
        final byte[] address = new byte[16];
        // If multicast bit is set, mix it in the mode, so that the lower two bits represent the
        // address mode, and the upper bit represents multicast compression.
        switch ((isMulticast ? 0b100 : 0) | mode) {
            case 0b000: // 128 bits. The full address is carried in-line.
            case 0b100:
                buffer.get(address);
                break;
            case 0b001: // 64 bits. The first 64-bits of the fe80:: address are elided.
                address[0] = (byte) 0xfe;
                address[1] = (byte) 0x80;
                buffer.get(address, 8 /*off*/, 8 /*len*/);
                break;
            case 0b010: // 16 bits. fe80::ff:fe00:XXXX, where XXXX are the bits carried in-line
                address[0] = (byte) 0xfe;
                address[1] = (byte) 0x80;
                address[11] = (byte) 0xff;
                address[12] = (byte) 0xfe;
                buffer.get(address, 14 /*off*/, 2 /*len*/);
                break;
            case 0b011: // 0 bits. The address is fully elided and derived from BLE MAC address
                // Note that on Android, the BLE MAC addresses are not exposed via the API;
                // therefore, this compression mode cannot be supported.
                throw new IOException("Address cannot be fully elided");
            case 0b101: // 48 bits. The address takes the form ffXX::00XX:XXXX:XXXX.
                address[0] = (byte) 0xff;
                address[1] = buffer.get();
                buffer.get(address, 11 /*off*/, 5 /*len*/);
                break;
            case 0b110: // 32 bits. The address takes the form ffXX::00XX:XXXX
                address[0] = (byte) 0xff;
                address[1] = buffer.get();
                buffer.get(address, 13 /*off*/, 3 /*len*/);
                break;
            case 0b111: // 8 bits. The address takes the form ff02::00XX.
                address[0] = (byte) 0xff;
                address[1] = (byte) 0x02;
                address[15] = buffer.get();
                break;
        }
        return address;
    }

    /**
     * Performs 6lowpan header decompression in place.
     *
     * Note that the passed in buffer must have enough capacity for successful decompression.
     *
     * @param bytes The buffer containing the packet.
     * @param len The size of the packet
     * @return decompressed size or zero
     * @throws BufferUnderflowException if an illegal packet is encountered.
     * @throws IOException if an unsupported option is encountered.
     */
    public static int decompress6lowpan(byte[] bytes, int len)
            throws BufferUnderflowException, IOException {
        // Note that ByteBuffer's default byte order is big endian.
        final ByteBuffer inBuffer = ByteBuffer.wrap(bytes);
        inBuffer.limit(len);

        // LOWPAN_IPHC base encoding:
        //   0   1   2   3   4   5   6   7 | 8   9  10  11  12  13  14  15
        // +---+---+---+---+---+---+---+---|---+---+---+---+---+---+---+---+
        // | 0 | 1 | 1 |  TF   |NH | HLIM  |CID|SAC|  SAM  | M |DAC|  DAM  |
        // +---+---+---+---+---+---+---+---|---+---+---+---+---+---+---+---+
        final int iphc1 = inBuffer.get() & 0xff;
        final int iphc2 = inBuffer.get() & 0xff;
        // Dispatch must start with 0b011.
        if ((iphc1 & 0xe0) != 0x60) {
            throw new IOException("LOWPAN_IPHC does not start with 011");
        }

        final int tf = (iphc1 >> 3) & 3;         // Traffic class
        final boolean nh = (iphc1 & 4) != 0;     // Next header
        final int hlim = iphc1 & 3;              // Hop limit
        final boolean cid = (iphc2 & 0x80) != 0; // Context identifier extension
        final boolean sac = (iphc2 & 0x40) != 0; // Source address compression
        final int sam = (iphc2 >> 4) & 3;        // Source address mode
        final boolean m = (iphc2 & 8) != 0;      // Multicast compression
        final boolean dac = (iphc2 & 4) != 0;    // Destination address compression
        final int dam = iphc2 & 3;               // Destination address mode

        final ByteBuffer ipv6Header = ByteBuffer.allocate(IPV6_HEADER_SIZE);

        final int trafficClass;
        final int flowLabel;
        switch (tf) {
            case 0b00: // ECN + DSCP + 4-bit Pad + Flow Label (4 bytes)
                trafficClass = inBuffer.get() & 0xff;
                flowLabel = (inBuffer.get() & 0x0f) << 16
                        | (inBuffer.get() & 0xff) << 8
                        | (inBuffer.get() & 0xff);
                break;
            case 0b01: // ECN + 2-bit Pad + Flow Label (3 bytes), DSCP is elided.
                final int firstByte = inBuffer.get() & 0xff;
                //     0     1     2     3     4     5     6     7
                // +-----+-----+-----+-----+-----+-----+-----+-----+
                // |          DS FIELD, DSCP           | ECN FIELD |
                // +-----+-----+-----+-----+-----+-----+-----+-----+
                // rfc6282 does not explicitly state what value to use for DSCP, assuming 0.
                trafficClass = firstByte >> 6;
                flowLabel = (firstByte & 0x0f) << 16
                        | (inBuffer.get() & 0xff) << 8
                        | (inBuffer.get() & 0xff);
                break;
            case 0b10: // ECN + DSCP (1 byte), Flow Label is elided.
                trafficClass = inBuffer.get() & 0xff;
                // rfc6282 does not explicitly state what value to use, assuming 0.
                flowLabel = 0;
                break;
            case 0b11: // Traffic Class and Flow Label are elided.
                // rfc6282 does not explicitly state what value to use, assuming 0.
                trafficClass = 0;
                flowLabel = 0;
                break;
            default:
                // This cannot happen. Crash if it does.
                throw new IllegalStateException("Illegal TF value");
        }

        // Write version, traffic class, and flow label
        final int versionTcFlowLabel = (6 << 28) | (trafficClass << 20) | flowLabel;
        ipv6Header.putInt(versionTcFlowLabel);

        // Payload length is still unknown. Use 0 for now.
        ipv6Header.putShort((short) 0);

        // We do not use UDP or extension header compression, therefore the next header
        // cannot be compressed.
        if (nh) throw new IOException("Next header cannot be compressed");
        // Write next header
        ipv6Header.put(inBuffer.get());

        final byte hopLimit;
        switch (hlim) {
            case 0b00: // The Hop Limit field is carried in-line.
                hopLimit = inBuffer.get();
                break;
            case 0b01: // The Hop Limit field is compressed and the hop limit is 1.
                hopLimit = 1;
                break;
            case 0b10: // The Hop Limit field is compressed and the hop limit is 64.
                hopLimit = 64;
                break;
            case 0b11: // The Hop Limit field is compressed and the hop limit is 255.
                hopLimit = (byte) 255;
                break;
            default:
                // This cannot happen. Crash if it does.
                throw new IllegalStateException("Illegal HLIM value");
        }
        ipv6Header.put(hopLimit);

        if (cid) throw new IOException("Context based compression not supported");
        if (sac) throw new IOException("Context based compression not supported");
        if (dac) throw new IOException("Context based compression not supported");

        // Write source address
        ipv6Header.put(decodeIpv6Address(inBuffer, sam, false /* isMulticast */));

        // Write destination address
        ipv6Header.put(decodeIpv6Address(inBuffer, dam, m));

        // Go back and fix up payloadLength
        final short payloadLength = (short) inBuffer.remaining();
        ipv6Header.putShort(4, payloadLength);

        // Done! Check that 40 bytes were written.
        if (ipv6Header.position() != IPV6_HEADER_SIZE) {
            // This indicates a bug in our code -> crash.
            throw new IllegalStateException("Faulty decompression wrote less than 40 bytes");
        }

        // Ensure there is enough room in the buffer
        final int packetLength = payloadLength + IPV6_HEADER_SIZE;
        if (bytes.length < packetLength) {
            throw new IOException("Decompressed packet exceeds buffer size");
        }

        // Move payload bytes back to make room for the header
        inBuffer.limit(packetLength);
        System.arraycopy(bytes, inBuffer.position(), bytes, IPV6_HEADER_SIZE, payloadLength);
        // Copy IPv6 header to the beginning of the buffer.
        inBuffer.position(0);
        ipv6Header.flip();
        inBuffer.put(ipv6Header);

        return packetLength;
    }

    /**
     * Performs 6lowpan header compression in place.
     *
     * @param bytes The buffer containing the packet.
     * @param len The size of the packet
     * @return compressed size or zero
     * @throws BufferUnderflowException if an illegal packet is encountered.
     * @throws IOException if an unsupported option is encountered.
     */
    public static int compress6lowpan(byte[] bytes, final int len)
            throws BufferUnderflowException, IOException {
        // Compression only happens on egress, i.e. the packet is read from the tun fd.
        // This means that this code can be a bit more lenient.
        if (len < 40) {
            Log.wtf(TAG, "Encountered short (<40 byte) packet");
            return 0;
        }

        // Note that ByteBuffer's default byte order is big endian.
        final ByteBuffer inBuffer = ByteBuffer.wrap(bytes);
        inBuffer.limit(len);

        // Check that the packet is an IPv6 packet
        final int versionTcFlowLabel = inBuffer.getInt() & 0xffffffff;
        if ((versionTcFlowLabel >> 28) != 6) {
            return 0;
        }

        // Check that the payload length matches the packet length - 40.
        int payloadLength = inBuffer.getShort();
        if (payloadLength != len - IPV6_HEADER_SIZE) {
            throw new IOException("Encountered packet with payload length mismatch");
        }

        // Implements rfc 6282 6lowpan header compression using iphc 0110 0000 0000 0000 (all
        // fields are carried inline).
        inBuffer.position(0);
        inBuffer.put((byte) 0x60);
        inBuffer.put((byte) 0x00);
        final byte trafficClass = (byte) ((versionTcFlowLabel >> 20) & 0xff);
        inBuffer.put(trafficClass);
        final byte flowLabelMsb = (byte) ((versionTcFlowLabel >> 16) & 0x0f);
        final short flowLabelLsb = (short) (versionTcFlowLabel & 0xffff);
        inBuffer.put(flowLabelMsb);
        // Note: the next putShort overrides the payload length. This is WAI as the payload length
        // is reconstructed via L2CAP packet length.
        inBuffer.putShort(flowLabelLsb);

        // Since the iphc (2 bytes) matches the payload length that was elided (2 bytes), the length
        // of the packet did not change.
        return len;
    }
}
