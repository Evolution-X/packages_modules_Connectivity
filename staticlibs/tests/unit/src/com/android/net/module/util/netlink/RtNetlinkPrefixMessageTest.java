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

package com.android.net.module.util.netlink;

import static android.system.OsConstants.NETLINK_ROUTE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.IpPrefix;
import android.system.OsConstants;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RtNetlinkPrefixMessageTest {
    private static final IpPrefix TEST_PREFIX = new IpPrefix("2001:db8:1:1::/64");

    // An example of the full RTM_NEWPREFIX message.
    private static final String RTM_NEWPREFIX_HEX =
            "3C000000340000000000000000000000"            // struct nlmsghr
            + "0A0000002F00000003400300"                  // struct prefixmsg
            + "1400010020010DB8000100010000000000000000"  // PREFIX_ADDRESS
            + "0C000200803A0900008D2700";                 // PREFIX_CACHEINFO

    private ByteBuffer toByteBuffer(final String hexString) {
        return ByteBuffer.wrap(HexDump.hexStringToByteArray(hexString));
    }

    @Test
    public void testParseRtmNewPrefix() {
        final ByteBuffer byteBuffer = toByteBuffer(RTM_NEWPREFIX_HEX);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer, NETLINK_ROUTE);
        assertNotNull(msg);
        assertTrue(msg instanceof RtNetlinkPrefixMessage);
        final RtNetlinkPrefixMessage prefixmsg = (RtNetlinkPrefixMessage) msg;

        final StructNlMsgHdr hdr = prefixmsg.getHeader();
        assertNotNull(hdr);
        assertEquals(60, hdr.nlmsg_len);
        assertEquals(NetlinkConstants.RTM_NEWPREFIX, hdr.nlmsg_type);
        assertEquals(0, hdr.nlmsg_flags);
        assertEquals(0, hdr.nlmsg_seq);
        assertEquals(0, hdr.nlmsg_pid);

        final StructPrefixMsg prefixmsgHdr = prefixmsg.getPrefixMsg();
        assertNotNull(prefixmsgHdr);
        assertEquals((byte) OsConstants.AF_INET6, prefixmsgHdr.prefix_family);
        assertEquals(3, prefixmsgHdr.prefix_type);
        assertEquals(64, prefixmsgHdr.prefix_len);
        assertEquals(0x03, prefixmsgHdr.prefix_flags);
        assertEquals(0x2F, prefixmsgHdr.prefix_ifindex);

        assertEquals(prefixmsg.getPrefix(), TEST_PREFIX);
        assertEquals(604800L, prefixmsg.getPreferredLifetime());
        assertEquals(2592000L, prefixmsg.getValidLifetime());
    }

    @Test
    public void testPackRtmNewPrefix() {
        final ByteBuffer byteBuffer = toByteBuffer(RTM_NEWPREFIX_HEX);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer, NETLINK_ROUTE);
        assertNotNull(msg);
        assertTrue(msg instanceof RtNetlinkPrefixMessage);
        final RtNetlinkPrefixMessage prefixmsg = (RtNetlinkPrefixMessage) msg;

        final ByteBuffer packBuffer = ByteBuffer.allocate(60);
        packBuffer.order(ByteOrder.LITTLE_ENDIAN);
        prefixmsg.pack(packBuffer);
        assertEquals(RTM_NEWPREFIX_HEX, HexDump.toHexString(packBuffer.array()));
    }

    private static final String RTM_NEWPREFIX_WITHOUT_PREFIX_ADDRESS_HEX =
            "24000000340000000000000000000000"            // struct nlmsghr
            + "0A0000002F00000003400300"                  // struct prefixmsg
            + "0C000200803A0900008D2700";                 // PREFIX_CACHEINFO

    @Test
    public void testParseRtmNewPrefix_withoutPrefixAddressAttribute() {
        final ByteBuffer byteBuffer = toByteBuffer(RTM_NEWPREFIX_WITHOUT_PREFIX_ADDRESS_HEX);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer, NETLINK_ROUTE);
        assertNull(msg);
    }

    private static final String RTM_NEWPREFIX_WITHOUT_PREFIX_CACHEINFO_HEX =
            "30000000340000000000000000000000"             // struct nlmsghr
            + "0A0000002F00000003400300"                   // struct prefixmsg
            + "140001002A0079E10ABCF6050000000000000000";  // PREFIX_ADDRESS

    @Test
    public void testParseRtmNewPrefix_withoutPrefixCacheinfoAttribute() {
        final ByteBuffer byteBuffer = toByteBuffer(RTM_NEWPREFIX_WITHOUT_PREFIX_CACHEINFO_HEX);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer, NETLINK_ROUTE);
        assertNull(msg);
    }

    private static final String RTM_NEWPREFIX_TRUNCATED_PREFIX_ADDRESS_HEX =
            "3C000000340000000000000000000000"            // struct nlmsghr
            + "0A0000002F00000003400300"                  // struct prefixmsg
            + "140001002A0079E10ABCF605000000000000"      // PREFIX_ADDRESS (truncated)
            + "0C000200803A0900008D2700";                 // PREFIX_CACHEINFO

    @Test
    public void testParseRtmNewPrefix_truncatedPrefixAddressAttribute() {
        final ByteBuffer byteBuffer = toByteBuffer(RTM_NEWPREFIX_TRUNCATED_PREFIX_ADDRESS_HEX);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer, NETLINK_ROUTE);
        assertNull(msg);
    }

    private static final String RTM_NEWPREFIX_TRUNCATED_PREFIX_CACHEINFO_HEX =
            "3C000000340000000000000000000000"            // struct nlmsghr
            + "0A0000002F00000003400300"                  // struct prefixmsg
            + "140001002A0079E10ABCF6050000000000000000"  // PREFIX_ADDRESS
            + "0C000200803A0900008D";                     // PREFIX_CACHEINFO (truncated)

    @Test
    public void testParseRtmNewPrefix_truncatedPrefixCacheinfoAttribute() {
        final ByteBuffer byteBuffer = toByteBuffer(RTM_NEWPREFIX_TRUNCATED_PREFIX_CACHEINFO_HEX);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer, NETLINK_ROUTE);
        assertNull(msg);
    }

    @Test
    public void testToString() {
        final ByteBuffer byteBuffer = toByteBuffer(RTM_NEWPREFIX_HEX);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        final NetlinkMessage msg = NetlinkMessage.parse(byteBuffer, NETLINK_ROUTE);
        assertNotNull(msg);
        assertTrue(msg instanceof RtNetlinkPrefixMessage);
        final RtNetlinkPrefixMessage prefixmsg = (RtNetlinkPrefixMessage) msg;
        final String expected = "RtNetlinkPrefixMessage{ "
                + "nlmsghdr{StructNlMsgHdr{ nlmsg_len{60}, nlmsg_type{52(RTM_NEWPREFIX)}, "
                + "nlmsg_flags{0()}, nlmsg_seq{0}, nlmsg_pid{0} }}, "
                + "prefixmsg{prefix_family: 10, prefix_ifindex: 47, prefix_type: 3, "
                + "prefix_len: 64, prefix_flags: 3}, "
                + "IP Prefix{2001:db8:1:1::/64}, "
                + "preferred lifetime{604800}, valid lifetime{2592000} }";
        assertEquals(expected, prefixmsg.toString());
    }
}
