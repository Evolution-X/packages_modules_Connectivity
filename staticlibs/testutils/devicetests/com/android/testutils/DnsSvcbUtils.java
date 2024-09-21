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

package com.android.testutils;

import static android.net.DnsResolver.CLASS_IN;

import static com.android.net.module.util.DnsPacket.TYPE_SVCB;
import static com.android.net.module.util.DnsPacketUtils.DnsRecordParser.domainNameToLabels;
import static com.android.net.module.util.NetworkStackConstants.IPV4_ADDR_LEN;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_LEN;

import static org.junit.Assert.fail;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.net.InetAddresses;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DnsSvcbUtils {
    private static final Pattern SVC_PARAM_PATTERN = Pattern.compile("([a-z0-9-]+)=?(.*)");

    /**
     * Returns a DNS SVCB response with given hostname `hostname` and given SVCB records
     * `records`. Each record must contain the service priority, the target name, and the service
     * parameters.
     *     E.g. "1 doh.google alpn=h2,h3 port=443 ipv4hint=192.0.2.1 dohpath=/dns-query{?dns}"
     */
    @NonNull
    public static byte[] makeSvcbResponse(String hostname, String[] records) throws IOException {
        if (records == null) throw new NullPointerException();
        if (!hostname.startsWith("_dns.")) throw new UnsupportedOperationException();
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        // Write DNS header.
        os.write(shortsToByteArray(
                0x1234,         /* Transaction ID */
                0x8100,         /* Flags */
                1,              /* qdcount */
                records.length, /* ancount */
                0,              /* nscount */
                0               /* arcount */
        ));
        // Write Question.
        // - domainNameToLabels() doesn't support the hostname starting with "_", so divide
        //   the writing into two steps.
        os.write(new byte[] { 0x04, '_', 'd', 'n', 's' });
        os.write(domainNameToLabels(hostname.substring(5)));
        os.write(shortsToByteArray(TYPE_SVCB, CLASS_IN));
        // Write Answer section.
        for (String r : records) {
            os.write(makeSvcbRecord(r));
        }
        return os.toByteArray();
    }

    @NonNull
    private static byte[] makeSvcbRecord(String representation) throws IOException {
        if (representation == null) return new byte[0];
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(shortsToByteArray(
                0xc00c, /* Pointer to qname in question section */
                TYPE_SVCB,
                CLASS_IN,
                0, 16, /* TTL = 16 */
                0 /* Data Length = 0 */

        ));
        final String[] strings = representation.split(" +");
        // SvcPriority and TargetName are mandatory in the representation.
        if (strings.length < 3) {
            fail("Invalid SVCB representation: " + representation);
        }
        // Write SvcPriority, TargetName, and SvcParams.
        os.write(shortsToByteArray(Short.parseShort(strings[0])));
        os.write(domainNameToLabels(strings[1]));
        for (int i = 2; i < strings.length; i++) {
            try {
                os.write(svcParamToByteArray(strings[i]));
            } catch (UnsupportedEncodingException e) {
                throw new IOException(e);
            }
        }
        // Update rdata length.
        final byte[] out = os.toByteArray();
        ByteBuffer.wrap(out).putShort(10, (short) (out.length - 12));
        return out;
    }

    @NonNull
    private static byte[] svcParamToByteArray(String svcParam) throws IOException {
        final Matcher matcher = SVC_PARAM_PATTERN.matcher(svcParam);
        if (!matcher.matches() || matcher.groupCount() != 2) {
            fail("Invalid SvcParam: " + svcParam);
        }
        final String svcParamkey = matcher.group(1);
        final String svcParamValue = matcher.group(2);
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(svcParamKeyToBytes(svcParamkey));
        switch (svcParamkey) {
            case "mandatory":
                final String[] keys = svcParamValue.split(",");
                os.write(shortsToByteArray(keys.length));
                for (String v : keys) {
                    os.write(svcParamKeyToBytes(v));
                }
                break;
            case "alpn":
                os.write(shortsToByteArray((svcParamValue.length() + 1)));
                for (String v : svcParamValue.split(",")) {
                    os.write(v.length());
                    // TODO: support percent-encoding per RFC 7838.
                    os.write(v.getBytes(US_ASCII));
                }
                break;
            case "no-default-alpn":
                os.write(shortsToByteArray(0));
                break;
            case "port":
                os.write(shortsToByteArray(2));
                os.write(shortsToByteArray(Short.parseShort(svcParamValue)));
                break;
            case "ipv4hint":
                final String[] v4Addrs = svcParamValue.split(",");
                os.write(shortsToByteArray((v4Addrs.length * IPV4_ADDR_LEN)));
                for (String v : v4Addrs) {
                    os.write(InetAddresses.parseNumericAddress(v).getAddress());
                }
                break;
            case "ech":
                os.write(shortsToByteArray(svcParamValue.length()));
                os.write(svcParamValue.getBytes(US_ASCII));  // base64 encoded
                break;
            case "ipv6hint":
                final String[] v6Addrs = svcParamValue.split(",");
                os.write(shortsToByteArray((v6Addrs.length * IPV6_ADDR_LEN)));
                for (String v : v6Addrs) {
                    os.write(InetAddresses.parseNumericAddress(v).getAddress());
                }
                break;
            case "dohpath":
                os.write(shortsToByteArray(svcParamValue.length()));
                // TODO: support percent-encoding, since this is a URI template.
                os.write(svcParamValue.getBytes(US_ASCII));
                break;
            default:
                os.write(shortsToByteArray(svcParamValue.length()));
                os.write(svcParamValue.getBytes(US_ASCII));
                break;
        }
        return os.toByteArray();
    }

    @NonNull
    private static byte[] svcParamKeyToBytes(String key) {
        switch (key) {
            case "mandatory": return shortsToByteArray(0);
            case "alpn": return shortsToByteArray(1);
            case "no-default-alpn": return shortsToByteArray(2);
            case "port": return shortsToByteArray(3);
            case "ipv4hint": return shortsToByteArray(4);
            case "ech": return shortsToByteArray(5);
            case "ipv6hint": return shortsToByteArray(6);
            case "dohpath": return shortsToByteArray(7);
            default:
                if (!key.startsWith("key")) fail("Invalid SvcParamKey " + key);
                return shortsToByteArray(Short.parseShort(key.substring(3)));
        }
    }

    @NonNull
    private static byte[] shortsToByteArray(int... values) {
        final ByteBuffer out = ByteBuffer.allocate(values.length * 2);
        for (int value: values) {
            if (value < 0 || value > 0xffff) {
                throw new AssertionError("not an unsigned short: " + value);
            }
            out.putShort((short) value);
        }
        return out.array();
    }
}
