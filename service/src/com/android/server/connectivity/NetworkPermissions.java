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

package com.android.server.connectivity;

import android.net.INetd;

/**
 * A wrapper class for managing network and traffic permissions.
 *
 * This class encapsulates permissions represented as a bitmask, as defined in INetd.aidl
 * and used within PermissionMonitor.java.  It distinguishes between two types of permissions:
 *
 * 1. Network Permissions: These permissions, declared in INetd.aidl, are used
 *    by the Android platform's network daemon (system/netd) to control network
 *    management
 *
 * 2. Traffic Permissions: These permissions are used internally by PermissionMonitor.java and
 *    BpfNetMaps.java to manage fine-grained network traffic filtering and control.
 *
 * This wrapper ensures that no new permission definitions, here or in aidl, conflict with any
 * existing permissions. This prevents unintended interactions or overrides.
 *
 * @hide
 */
public class NetworkPermissions {

    /*
     * Below are network permissions declared in INetd.aidl and used by the platform. Using these is
     * equivalent to using the values in android.net.INetd.
     */
    public static final int PERMISSION_NONE = INetd.PERMISSION_NONE; /* 0 */
    public static final int PERMISSION_NETWORK = INetd.PERMISSION_NETWORK; /* 1 */
    public static final int PERMISSION_SYSTEM = INetd.PERMISSION_SYSTEM; /* 2 */

    /*
     * Below are traffic permissions used by PermissionMonitor and BpfNetMaps.
     */

    /**
     * PERMISSION_UNINSTALLED is used when an app is uninstalled from the device. All internet
     * related permissions need to be cleaned.
     */
    public static final int TRAFFIC_PERMISSION_UNINSTALLED = -1;

    /**
     * PERMISSION_INTERNET indicates that the app can create AF_INET and AF_INET6 sockets.
     */
    public static final int TRAFFIC_PERMISSION_INTERNET = 4;

    /**
     * PERMISSION_UPDATE_DEVICE_STATS is used for system UIDs and privileged apps
     * that have the UPDATE_DEVICE_STATS permission.
     */
    public static final int TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS = 8;

    /**
     * TRAFFIC_PERMISSION_SDKSANDBOX_LOCALHOST indicates if an SdkSandbox UID will be allowed
     * to connect to localhost. For non SdkSandbox UIDs this bit is a no-op.
     */
    public static final int TRAFFIC_PERMISSION_SDKSANDBOX_LOCALHOST = 16;
}
