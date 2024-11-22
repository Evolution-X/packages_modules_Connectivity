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

import android.annotation.NonNull;
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

import com.android.server.net.ct.DownloadHelper.DownloadStatus;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/** Helper class to download certificate transparency log files. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyDownloader";

    private final Context mContext;
    private final DataStore mDataStore;
    private final DownloadHelper mDownloadHelper;
    private final CertificateTransparencyInstaller mInstaller;

    @NonNull private Optional<PublicKey> mPublicKey = Optional.empty();

    @VisibleForTesting
    CertificateTransparencyDownloader(
            Context context,
            DataStore dataStore,
            DownloadHelper downloadHelper,
            CertificateTransparencyInstaller installer) {
        mContext = context;
        mDataStore = dataStore;
        mDownloadHelper = downloadHelper;
        mInstaller = installer;
    }

    CertificateTransparencyDownloader(Context context, DataStore dataStore) {
        this(
                context,
                dataStore,
                new DownloadHelper(context),
                new CertificateTransparencyInstaller());
    }

    void initialize() {
        mInstaller.addCompatibilityVersion(Config.COMPATIBILITY_VERSION);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        mContext.registerReceiver(this, intentFilter, Context.RECEIVER_EXPORTED);

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader initialized successfully");
        }
    }

    void setPublicKey(String publicKey) throws GeneralSecurityException {
        try {
            mPublicKey =
                    Optional.of(
                            KeyFactory.getInstance("RSA")
                                    .generatePublic(
                                            new X509EncodedKeySpec(
                                                    Base64.getDecoder().decode(publicKey))));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid public key Base64 encoding", e);
            mPublicKey = Optional.empty();
        }
    }

    @VisibleForTesting
    void resetPublicKey() {
        mPublicKey = Optional.empty();
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
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }
        startContentDownload(mDataStore.getProperty(Config.CONTENT_URL_PENDING));
    }

    private void handleContentDownloadCompleted(long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
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
            success = mInstaller.install(Config.COMPATIBILITY_VERSION, inputStream, version);
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

    private void handleDownloadFailed(DownloadStatus status) {
        Log.e(TAG, "Content download failed with " + status);
        // TODO(378626065): Report failure via statsd.
    }

    private boolean verify(Uri file, Uri signature) throws IOException, GeneralSecurityException {
        if (!mPublicKey.isPresent()) {
            throw new InvalidKeyException("Missing public key for signature verification");
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(mPublicKey.get());
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
