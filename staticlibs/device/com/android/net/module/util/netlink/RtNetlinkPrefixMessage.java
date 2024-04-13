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

import android.net.IpPrefix;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.net.Inet6Address;
import java.nio.ByteBuffer;

/**
 * A NetlinkMessage subclass for rtnetlink address messages.
 *
 * RtNetlinkPrefixMessage.parse() must be called with a ByteBuffer that contains exactly one
 * netlink message.
 *
 * see also:
 *
 *     include/uapi/linux/rtnetlink.h
 *
 * @hide
 */
public class RtNetlinkPrefixMessage extends NetlinkMessage {
    public static final short PREFIX_ADDRESS       = 1;
    public static final short PREFIX_CACHEINFO     = 2;

    @NonNull
    private StructPrefixMsg mPrefixmsg;
    @NonNull
    private IpPrefix mPrefix;
    private long mPreferredLifetime;
    private long mValidLifetime;

    @VisibleForTesting
    public RtNetlinkPrefixMessage(@NonNull final StructNlMsgHdr header,
            @NonNull final StructPrefixMsg prefixmsg,
            @NonNull final IpPrefix prefix,
            long preferred, long valid) {
        super(header);
        mPrefixmsg = prefixmsg;
        mPrefix = prefix;
        mPreferredLifetime = preferred;
        mValidLifetime = valid;
    }

    private RtNetlinkPrefixMessage(@NonNull StructNlMsgHdr header) {
        this(header, null, null, 0 /* preferredLifetime */, 0 /* validLifetime */);
    }

    @NonNull
    public StructPrefixMsg getPrefixMsg() {
        return mPrefixmsg;
    }

    @NonNull
    public IpPrefix getPrefix() {
        return mPrefix;
    }

    public long getPreferredLifetime() {
        return mPreferredLifetime;
    }

    public long getValidLifetime() {
        return mValidLifetime;
    }

    /**
     * Parse rtnetlink prefix message from {@link ByteBuffer}. This method must be called with a
     * ByteBuffer that contains exactly one netlink message.
     *
     * RTM_NEWPREFIX Message Format:
     *  +----------+- - -+-------------+- - -+---------------------+-----------------------+
     *  | nlmsghdr | Pad |  prefixmsg  | Pad | PREFIX_ADDRESS attr | PREFIX_CACHEINFO attr |
     *  +----------+- - -+-------------+- - -+---------------------+-----------------------+
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes.
     */
    @Nullable
    public static RtNetlinkPrefixMessage parse(@NonNull final StructNlMsgHdr header,
            @NonNull final ByteBuffer byteBuffer) {
        try {
            final RtNetlinkPrefixMessage msg = new RtNetlinkPrefixMessage(header);
            msg.mPrefixmsg = StructPrefixMsg.parse(byteBuffer);

            // PREFIX_ADDRESS
            final int baseOffset = byteBuffer.position();
            StructNlAttr nlAttr = StructNlAttr.findNextAttrOfType(PREFIX_ADDRESS, byteBuffer);
            if (nlAttr == null) return null;
            final Inet6Address addr = (Inet6Address) nlAttr.getValueAsInetAddress();
            if (addr == null) return null;
            msg.mPrefix = new IpPrefix(addr, msg.mPrefixmsg.prefix_len);

            // PREFIX_CACHEINFO
            byteBuffer.position(baseOffset);
            nlAttr = StructNlAttr.findNextAttrOfType(PREFIX_CACHEINFO, byteBuffer);
            if (nlAttr == null) return null;
            final ByteBuffer buffer = nlAttr.getValueAsByteBuffer();
            if (buffer == null) return null;
            final StructPrefixCacheInfo cacheinfo = StructPrefixCacheInfo.parse(buffer);
            msg.mPreferredLifetime = cacheinfo.preferred_time;
            msg.mValidLifetime = cacheinfo.valid_time;

            return msg;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Write a rtnetlink prefix message to {@link ByteBuffer}.
     */
    @VisibleForTesting
    protected void pack(ByteBuffer byteBuffer) {
        getHeader().pack(byteBuffer);
        mPrefixmsg.pack(byteBuffer);

        // PREFIX_ADDRESS attribute
        final StructNlAttr prefixAddress =
                new StructNlAttr(PREFIX_ADDRESS, mPrefix.getRawAddress());
        prefixAddress.pack(byteBuffer);

        // PREFIX_CACHEINFO attribute
        final StructPrefixCacheInfo cacheinfo =
                new StructPrefixCacheInfo(mPreferredLifetime, mValidLifetime);
        final StructNlAttr prefixCacheinfo =
                new StructNlAttr(PREFIX_CACHEINFO, cacheinfo.writeToBytes());
        prefixCacheinfo.pack(byteBuffer);
    }

    @Override
    public String toString() {
        return "RtNetlinkPrefixMessage{ "
                + "nlmsghdr{" + mHeader.toString(OsConstants.NETLINK_ROUTE) + "}, "
                + "prefixmsg{" + mPrefixmsg.toString() + "}, "
                + "IP Prefix{" + mPrefix + "}, "
                + "preferred lifetime{" + mPreferredLifetime + "}, "
                + "valid lifetime{" + mValidLifetime + "} "
                + "}";
    }
}
