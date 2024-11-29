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

    // CT directory
    static final String CT_ROOT_DIRECTORY_PATH = "/data/misc/keychain/ct/";
    static final String COMPATIBILITY_VERSION = "v1";

    // Phenotype flags
    static final String NAMESPACE_NETWORK_SECURITY = "network_security";
    private static final String FLAGS_PREFIX = "CertificateTransparencyLogList__";
    static final String FLAG_SERVICE_ENABLED = FLAGS_PREFIX + "service_enabled";
    static final String FLAG_CONTENT_URL = FLAGS_PREFIX + "content_url";
    static final String FLAG_METADATA_URL = FLAGS_PREFIX + "metadata_url";
    static final String FLAG_VERSION = FLAGS_PREFIX + "version";
    static final String FLAG_PUBLIC_KEY = FLAGS_PREFIX + "public_key";

    // properties
    static final String VERSION = "version";
    static final String CONTENT_URL = "content_url";
    static final String CONTENT_DOWNLOAD_ID = "content_download_id";
    static final String METADATA_URL = "metadata_url";
    static final String METADATA_DOWNLOAD_ID = "metadata_download_id";
    static final String PUBLIC_KEY_URL = "public_key_url";
    static final String PUBLIC_KEY_DOWNLOAD_ID = "public_key_download_id";

    // URLs
    static final String URL_PREFIX = "https://www.gstatic.com/android/certificate_transparency/";
    static final String URL_LOG_LIST = URL_PREFIX + "log_list.json";
    static final String URL_SIGNATURE = URL_PREFIX + "log_list.sig";
    static final String URL_PUBLIC_KEY = URL_PREFIX + "log_list.pub";
}
