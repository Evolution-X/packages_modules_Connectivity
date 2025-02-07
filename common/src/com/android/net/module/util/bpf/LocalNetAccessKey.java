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

package com.android.net.module.util.bpf;

import com.android.net.module.util.InetAddressUtils;
import com.android.net.module.util.Struct;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

public class LocalNetAccessKey extends Struct {

    @Field(order = 0, type = Type.U32)
    public final long lpmBitlen;
    @Field(order = 1, type = Type.U32)
    public final long ifIndex;
    @Field(order = 2, type = Type.Ipv6Address)
    public final Inet6Address remoteAddress;
    @Field(order = 3, type = Type.U16)
    public final int protocol;
    @Field(order = 4, type = Type.UBE16)
    public final int remotePort;

    public LocalNetAccessKey(long lpmBitlen, long ifIndex, InetAddress remoteAddress, int protocol,
            int remotePort) {
        this.lpmBitlen = lpmBitlen;
        this.ifIndex = ifIndex;
        this.protocol = protocol;
        this.remotePort = remotePort;

        if (remoteAddress instanceof Inet4Address) {
            this.remoteAddress = InetAddressUtils.v4MappedV6Address((Inet4Address) remoteAddress);
        } else {
            this.remoteAddress = (Inet6Address) remoteAddress;
        }
    }

    public LocalNetAccessKey(long lpmBitlen, long ifIndex, Inet6Address remoteAddress, int protocol,
            int remotePort) {
        this.lpmBitlen = lpmBitlen;
        this.ifIndex = ifIndex;
        this.remoteAddress = remoteAddress;
        this.protocol = protocol;
        this.remotePort = remotePort;
    }

    @Override
    public String toString() {
        return "LocalNetAccessKey{"
                + "lpmBitlen=" + lpmBitlen
                + ", ifIndex=" + ifIndex
                + ", remoteAddress=" + remoteAddress
                + ", protocol=" + protocol
                + ", remotePort=" + remotePort
                + "}";
    }
}
