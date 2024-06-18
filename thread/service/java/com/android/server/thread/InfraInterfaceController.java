/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import static android.system.OsConstants.IPPROTO_IPV6;
import static android.system.OsConstants.IPPROTO_RAW;
import static android.system.OsConstants.IPV6_CHECKSUM;
import static android.system.OsConstants.IPV6_MULTICAST_HOPS;
import static android.system.OsConstants.IPV6_RECVHOPLIMIT;
import static android.system.OsConstants.IPV6_RECVPKTINFO;
import static android.system.OsConstants.IPV6_UNICAST_HOPS;

import android.net.util.SocketUtils;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import java.io.FileDescriptor;
import java.io.IOException;

/** Controller for the infrastructure network interface. */
public class InfraInterfaceController {
    private static final String TAG = "InfraIfController";

    private static final int ENABLE = 1;
    private static final int IPV6_CHECKSUM_OFFSET = 2;
    private static final int HOP_LIMIT = 255;

    static {
        System.loadLibrary("service-thread-jni");
    }

    /**
     * Creates a socket on the infrastructure network interface for sending/receiving ICMPv6
     * Neighbor Discovery messages.
     *
     * @param infraInterfaceName the infrastructure network interface name.
     * @return an ICMPv6 socket file descriptor on the Infrastructure network interface.
     * @throws IOException when fails to create the socket.
     */
    public ParcelFileDescriptor createIcmp6Socket(String infraInterfaceName) throws IOException {
        ParcelFileDescriptor parcelFd =
                ParcelFileDescriptor.adoptFd(nativeCreateFilteredIcmp6Socket());
        FileDescriptor fd = parcelFd.getFileDescriptor();
        try {
            Os.setsockoptInt(fd, IPPROTO_RAW, IPV6_CHECKSUM, IPV6_CHECKSUM_OFFSET);
            Os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_RECVPKTINFO, ENABLE);
            Os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_RECVHOPLIMIT, ENABLE);
            Os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, HOP_LIMIT);
            Os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, HOP_LIMIT);
            SocketUtils.bindSocketToInterface(fd, infraInterfaceName);
        } catch (ErrnoException e) {
            throw new IOException("Failed to setsockopt for the ICMPv6 socket", e);
        }
        return parcelFd;
    }

    private static native int nativeCreateFilteredIcmp6Socket() throws IOException;
}
