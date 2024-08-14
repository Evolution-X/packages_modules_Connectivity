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

import static android.provider.DeviceConfig.NAMESPACE_TETHERING;

import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;

import java.util.concurrent.Executors;

/** Listener class for the Certificate Transparency Phenotype flags. */
class CertificateTransparencyFlagsListener implements DeviceConfig.OnPropertiesChangedListener {

    private static final String TAG = "CertificateTransparency";

    private static final String VERSION = "version";
    private static final String CONTENT_URL = "content_url";
    private static final String METADATA_URL = "metadata_url";

    CertificateTransparencyFlagsListener(Context context) {}

    void initialize() {
        DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE_TETHERING, Executors.newSingleThreadExecutor(), this);
        // TODO: handle property changes triggering on boot before registering this listener.
    }

    @Override
    public void onPropertiesChanged(Properties properties) {
        if (!SdkLevel.isAtLeastV() || !NAMESPACE_TETHERING.equals(properties.getNamespace())) {
            return;
        }

        String newVersion = DeviceConfig.getString(NAMESPACE_TETHERING, VERSION, "");
        String newContentUrl = DeviceConfig.getString(NAMESPACE_TETHERING, CONTENT_URL, "");
        String newMetadataUrl = DeviceConfig.getString(NAMESPACE_TETHERING, METADATA_URL, "");
        if (TextUtils.isEmpty(newVersion)
                || TextUtils.isEmpty(newContentUrl)
                || TextUtils.isEmpty(newMetadataUrl)) {
            return;
        }

        Log.d(TAG, "newVersion=" + newVersion);
        Log.d(TAG, "newContentUrl=" + newContentUrl);
        Log.d(TAG, "newMetadataUrl=" + newMetadataUrl);
        // TODO: start download of URLs.
    }
}
