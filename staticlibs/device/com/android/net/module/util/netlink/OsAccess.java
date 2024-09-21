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

import android.system.Os;

import androidx.annotation.NonNull;

/**
 * This class wraps the static methods of {@link android.system.Os} for mocking and testing.
 */
public class OsAccess {
    /**
     * Constant indicating that the {@code if_nametoindex()} function could not find the network
     * interface index corresponding to the given interface name.
     */
    public static int INVALID_INTERFACE_INDEX = 0;

    /** Wraps {@link Os#if_nametoindex(String)}. */
    public int if_nametoindex(@NonNull String name) {
        return Os.if_nametoindex(name);
    }
}
