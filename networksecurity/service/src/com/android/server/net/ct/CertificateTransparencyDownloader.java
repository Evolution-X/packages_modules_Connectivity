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

import android.annotation.RequiresApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Helper class to download certificate transparency log files. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyDownloader";

    // TODO: move key to a DeviceConfig flag.
    private static final byte[] PUBLIC_KEY_BYTES =
            Base64.getDecoder()
                    .decode(
                            "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAsu0BHGnQ++W2CTdyZyxv"
                                + "HHRALOZPlnu/VMVgo2m+JZ8MNbAOH2cgXb8mvOj8flsX/qPMuKIaauO+PwROMjiq"
                                + "fUpcFm80Kl7i97ZQyBDYKm3MkEYYpGN+skAR2OebX9G2DfDqFY8+jUpOOWtBNr3L"
                                + "rmVcwx+FcFdMjGDlrZ5JRmoJ/SeGKiORkbbu9eY1Wd0uVhz/xI5bQb0OgII7hEj+"
                                + "i/IPbJqOHgB8xQ5zWAJJ0DmG+FM6o7gk403v6W3S8qRYiR84c50KppGwe4YqSMkF"
                                + "bLDleGQWLoaDSpEWtESisb4JiLaY4H+Kk0EyAhPSb+49JfUozYl+lf7iFN3qRq/S"
                                + "IXXTh6z0S7Qa8EYDhKGCrpI03/+qprwy+my6fpWHi6aUIk4holUCmWvFxZDfixox"
                                + "K0RlqbFDl2JXMBquwlQpm8u5wrsic1ksIv9z8x9zh4PJqNpCah0ciemI3YGRQqSe"
                                + "/mRRXBiSn9YQBUPcaeqCYan+snGADFwHuXCd9xIAdFBolw9R9HTedHGUfVXPJDiF"
                                + "4VusfX6BRR/qaadB+bqEArF/TzuDUr6FvOR4o8lUUxgLuZ/7HO+bHnaPFKYHHSm+"
                                + "+z1lVDhhYuSZ8ax3T0C3FZpb7HMjZtpEorSV5ElKJEJwrhrBCMOD8L01EoSPrGlS"
                                + "1w22i9uGHMn/uGQKo28u7AsCAwEAAQ==");

    private final Context mContext;
    private final DataStore mDataStore;
    private final DownloadHelper mDownloadHelper;
    private final CertificateTransparencyInstaller mInstaller;
    private final byte[] mPublicKey;

    @VisibleForTesting
    CertificateTransparencyDownloader(
            Context context,
            DataStore dataStore,
            DownloadHelper downloadHelper,
            CertificateTransparencyInstaller installer,
            byte[] publicKey) {
        mContext = context;
        mDataStore = dataStore;
        mDownloadHelper = downloadHelper;
        mInstaller = installer;
        mPublicKey = publicKey;
    }

    CertificateTransparencyDownloader(Context context, DataStore dataStore) {
        this(
                context,
                dataStore,
                new DownloadHelper(context),
                new CertificateTransparencyInstaller(),
                PUBLIC_KEY_BYTES);
    }

    void registerReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        mContext.registerReceiver(this, intentFilter, Context.RECEIVER_EXPORTED);

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader initialized successfully");
        }
    }

    void startMetadataDownload(String metadataUrl) {
        long downloadId = download(metadataUrl);
        if (downloadId == -1) {
            Log.e(TAG, "Metadata download request failed for " + metadataUrl);
            return;
        }
        mDataStore.setPropertyLong(Config.METADATA_URL_KEY, downloadId);
        mDataStore.store();
    }

    void startContentDownload(String contentUrl) {
        long downloadId = download(contentUrl);
        if (downloadId == -1) {
            Log.e(TAG, "Content download request failed for " + contentUrl);
            return;
        }
        mDataStore.setPropertyLong(Config.CONTENT_URL_KEY, downloadId);
        mDataStore.store();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            Log.w(TAG, "Received unexpected broadcast with action " + action);
            return;
        }

        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (completedId == -1) {
            Log.e(TAG, "Invalid completed download Id");
            return;
        }

        if (isMetadataDownloadId(completedId)) {
            handleMetadataDownloadCompleted(completedId);
            return;
        }

        if (isContentDownloadId(completedId)) {
            handleContentDownloadCompleted(completedId);
            return;
        }

        Log.e(TAG, "Download id " + completedId + " is neither metadata nor content.");
    }

    private void handleMetadataDownloadCompleted(long downloadId) {
        if (!mDownloadHelper.isSuccessful(downloadId)) {
            Log.w(TAG, "Metadata download failed.");
            // TODO: re-attempt download
            return;
        }

        startContentDownload(mDataStore.getProperty(Config.CONTENT_URL_PENDING));
    }

    private void handleContentDownloadCompleted(long downloadId) {
        if (!mDownloadHelper.isSuccessful(downloadId)) {
            Log.w(TAG, "Content download failed.");
            // TODO: re-attempt download
            return;
        }

        Uri contentUri = getContentDownloadUri();
        Uri metadataUri = getMetadataDownloadUri();
        if (contentUri == null || metadataUri == null) {
            Log.e(TAG, "Invalid URIs");
            return;
        }

        boolean success = false;
        try {
            success = verify(contentUri, metadataUri);
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "Could not verify new log list", e);
        }
        if (!success) {
            Log.w(TAG, "Log list did not pass verification");
            return;
        }

        // TODO: validate file content.

        String version = mDataStore.getProperty(Config.VERSION_PENDING);
        String contentUrl = mDataStore.getProperty(Config.CONTENT_URL_PENDING);
        String metadataUrl = mDataStore.getProperty(Config.METADATA_URL_PENDING);
        try (InputStream inputStream = mContext.getContentResolver().openInputStream(contentUri)) {
            success = mInstaller.install(inputStream, version);
        } catch (IOException e) {
            Log.e(TAG, "Could not install new content", e);
            return;
        }

        if (success) {
            // Update information about the stored version on successful install.
            mDataStore.setProperty(Config.VERSION, version);
            mDataStore.setProperty(Config.CONTENT_URL, contentUrl);
            mDataStore.setProperty(Config.METADATA_URL, metadataUrl);
            mDataStore.store();
        }
    }

    private boolean verify(Uri file, Uri signature) throws IOException, GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        verifier.initVerify(keyFactory.generatePublic(new X509EncodedKeySpec(mPublicKey)));
        ContentResolver contentResolver = mContext.getContentResolver();

        try (InputStream fileStream = contentResolver.openInputStream(file);
                InputStream signatureStream = contentResolver.openInputStream(signature)) {
            verifier.update(fileStream.readAllBytes());
            return verifier.verify(signatureStream.readAllBytes());
        }
    }

    private long download(String url) {
        try {
            return mDownloadHelper.startDownload(url);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Download request failed", e);
            return -1;
        }
    }

    @VisibleForTesting
    boolean isMetadataDownloadId(long downloadId) {
        return mDataStore.getPropertyLong(Config.METADATA_URL_KEY, -1) == downloadId;
    }

    @VisibleForTesting
    boolean isContentDownloadId(long downloadId) {
        return mDataStore.getPropertyLong(Config.CONTENT_URL_KEY, -1) == downloadId;
    }

    private Uri getMetadataDownloadUri() {
        return mDownloadHelper.getUri(mDataStore.getPropertyLong(Config.METADATA_URL_KEY, -1));
    }

    private Uri getContentDownloadUri() {
        return mDownloadHelper.getUri(mDataStore.getPropertyLong(Config.CONTENT_URL_KEY, -1));
    }
}
