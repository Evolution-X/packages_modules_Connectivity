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

import static android.security.Flags.certificateTransparencyConfiguration;

import static com.android.net.ct.flags.Flags.certificateTransparencyJob;
import static com.android.net.ct.flags.Flags.certificateTransparencyService;

import android.annotation.RequiresApi;
import android.content.Context;
import android.net.ct.ICertificateTransparencyManager;
import android.os.Build;
import android.provider.DeviceConfig;

import com.android.server.SystemService;

/** Implementation of the Certificate Transparency service. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class CertificateTransparencyService extends ICertificateTransparencyManager.Stub {

    private final CertificateTransparencyFlagsListener mFlagsListener;
    private final CertificateTransparencyJob mCertificateTransparencyJob;

    /**
     * @return true if the CertificateTransparency service is enabled.
     */
    public static boolean enabled(Context context) {
        return DeviceConfig.getBoolean(
                        Config.NAMESPACE_NETWORK_SECURITY,
                        Config.FLAG_SERVICE_ENABLED,
                        /* defaultValue= */ false)
                && certificateTransparencyService()
                && certificateTransparencyConfiguration();
    }

    /** Creates a new {@link CertificateTransparencyService} object. */
    public CertificateTransparencyService(Context context) {
        DataStore dataStore = new DataStore(Config.PREFERENCES_FILE);
        DownloadHelper downloadHelper = new DownloadHelper(context);
        SignatureVerifier signatureVerifier = new SignatureVerifier(context);
        CertificateTransparencyDownloader downloader =
                new CertificateTransparencyDownloader(
                        context,
                        dataStore,
                        downloadHelper,
                        signatureVerifier,
                        new CertificateTransparencyInstaller());
        mFlagsListener =
                new CertificateTransparencyFlagsListener(dataStore, signatureVerifier, downloader);
        mCertificateTransparencyJob =
                new CertificateTransparencyJob(context, dataStore, downloader);
    }

    /**
     * Called by {@link com.android.server.ConnectivityServiceInitializer}.
     *
     * @see com.android.server.SystemService#onBootPhase
     */
    public void onBootPhase(int phase) {
        switch (phase) {
            case SystemService.PHASE_BOOT_COMPLETED:
                if (certificateTransparencyJob()) {
                    mCertificateTransparencyJob.initialize();
                } else {
                    mFlagsListener.initialize();
                }
                break;
            default:
        }
    }
}
