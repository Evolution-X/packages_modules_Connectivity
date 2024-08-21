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
package com.android.server.net.ct;

import android.content.ApexEnvironment;

import com.android.net.module.util.DeviceConfigUtils;

import java.io.File;

/** Class holding the constants used by the CT feature. */
final class Config {

    static final boolean DEBUG = false;

    // preferences file
    private static final File DEVICE_PROTECTED_DATA_DIR =
            ApexEnvironment.getApexEnvironment(DeviceConfigUtils.TETHERING_MODULE_NAME)
                    .getDeviceProtectedDataDir();
    private static final String PREFERENCES_FILE_NAME = "ct.preferences";
    static final File PREFERENCES_FILE = new File(DEVICE_PROTECTED_DATA_DIR, PREFERENCES_FILE_NAME);

    // flags and properties names
    static final String VERSION = "version";
    static final String CONTENT_URL = "content_url";
    static final String CONTENT_URL_KEY = "content_url_key";
    static final String METADATA_URL = "metadata_url";
    static final String METADATA_URL_KEY = "metadata_url_key";
}
