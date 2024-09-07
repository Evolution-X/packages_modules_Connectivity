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

package com.android.server.thread;

import com.android.net.module.util.SharedLog;

/**
 * The Logger for Thread network.
 *
 * <p>Each class should log with its own tag using the logger of
 * ThreadNetworkLogger.forSubComponent(TAG).
 */
public final class ThreadNetworkLogger {
    private static final String TAG = "ThreadNetwork";
    private static final SharedLog mLog = new SharedLog(TAG);

    public static SharedLog forSubComponent(String subComponent) {
        return mLog.forSubComponent(subComponent);
    }

    // Disable instantiation
    private ThreadNetworkLogger() {}
}
