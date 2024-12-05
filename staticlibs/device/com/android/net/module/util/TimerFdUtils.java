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

package com.android.net.module.util;

import android.os.Process;
import android.util.Log;

import java.io.IOException;

/**
 * Contains mostly timerfd functionality.
 */
public class TimerFdUtils {
    static {
        final String jniLibName = JniUtil.getJniLibraryName(TimerFdUtils.class.getPackage());
        if (jniLibName.equals("android_net_connectivity_com_android_net_module_util_jni")) {
            // This library is part of service-connectivity.jar when in the system server,
            // so libservice-connectivity.so is the library to load.
            System.loadLibrary("service-connectivity");
        } else {
            System.loadLibrary(jniLibName);
        }
    }

    private static final String TAG = TimerFdUtils.class.getSimpleName();

    /**
     * Create a timerfd.
     *
     * @throws IOException if the timerfd creation is failed.
     */
    private static native int createTimerFd() throws IOException;

    /**
     * Set given time to the timerfd.
     *
     * @param timeMs target time
     * @throws IOException if setting expiration time is failed.
     */
    private static native void setTime(int fd, long timeMs) throws IOException;

    /**
     * Create a timerfd
     */
    static int createTimerFileDescriptor() {
        try {
            return createTimerFd();
        } catch (IOException e) {
            Log.e(TAG, "createTimerFd failed", e);
            return -1;
        }
    }

    /**
     * Set expiration time to timerfd
     */
    static boolean setExpirationTime(int id, long expirationTimeMs) {
        try {
            setTime(id, expirationTimeMs);
        } catch (IOException e) {
            Log.e(TAG, "setExpirationTime failed", e);
            return false;
        }
        return true;
    }
}
