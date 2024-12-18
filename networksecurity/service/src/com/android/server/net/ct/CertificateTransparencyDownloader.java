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

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.RequiresApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.server.net.ct.DownloadHelper.DownloadStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

/** Helper class to download certificate transparency log files. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyDownloader";

    private final Context mContext;
    private final DataStore mDataStore;
    private final DownloadHelper mDownloadHelper;
    private final SignatureVerifier mSignatureVerifier;
    private final CertificateTransparencyInstaller mInstaller;

    CertificateTransparencyDownloader(
            Context context,
            DataStore dataStore,
            DownloadHelper downloadHelper,
            SignatureVerifier signatureVerifier,
            CertificateTransparencyInstaller installer) {
        mContext = context;
        mSignatureVerifier = signatureVerifier;
        mDataStore = dataStore;
        mDownloadHelper = downloadHelper;
        mInstaller = installer;
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

    long startPublicKeyDownload() {
        long downloadId = download(mDataStore.getProperty(Config.PUBLIC_KEY_URL));
        if (downloadId != -1) {
            mDataStore.setPropertyLong(Config.PUBLIC_KEY_DOWNLOAD_ID, downloadId);
            mDataStore.store();
        }
        return downloadId;
    }

    long startMetadataDownload() {
        long downloadId = download(mDataStore.getProperty(Config.METADATA_URL));
        if (downloadId != -1) {
            mDataStore.setPropertyLong(Config.METADATA_DOWNLOAD_ID, downloadId);
            mDataStore.store();
        }
        return downloadId;
    }

    long startContentDownload() {
        long downloadId = download(mDataStore.getProperty(Config.CONTENT_URL));
        if (downloadId != -1) {
            mDataStore.setPropertyLong(Config.CONTENT_DOWNLOAD_ID, downloadId);
            mDataStore.store();
        }
        return downloadId;
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

        if (isPublicKeyDownloadId(completedId)) {
            handlePublicKeyDownloadCompleted(completedId);
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

        Log.i(TAG, "Download id " + completedId + " is not recognized.");
    }

    private void handlePublicKeyDownloadCompleted(long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }

        Uri publicKeyUri = getPublicKeyDownloadUri();
        if (publicKeyUri == null) {
            Log.e(TAG, "Invalid public key URI");
            return;
        }

        try {
            mSignatureVerifier.setPublicKeyFrom(publicKeyUri);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            Log.e(TAG, "Error setting the public Key", e);
            return;
        }

        if (startMetadataDownload() == -1) {
            Log.e(TAG, "Metadata download not started.");
        } else if (Config.DEBUG) {
            Log.d(TAG, "Metadata download started successfully.");
        }
    }

    private void handleMetadataDownloadCompleted(long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }
        if (startContentDownload() == -1) {
            Log.e(TAG, "Content download not started.");
        } else if (Config.DEBUG) {
            Log.d(TAG, "Content download started successfully.");
        }
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
            success = mSignatureVerifier.verify(contentUri, metadataUri);
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "Could not verify new log list", e);
        }
        if (!success) {
            Log.w(TAG, "Log list did not pass verification");
            return;
        }

        String version = null;
        try (InputStream inputStream = mContext.getContentResolver().openInputStream(contentUri)) {
            version = new JSONObject(new String(inputStream.readAllBytes(), UTF_8))
                    .getString("version");
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Could not extract version from log list", e);
            return;
        }

        try (InputStream inputStream = mContext.getContentResolver().openInputStream(contentUri)) {
            success = mInstaller.install(Config.COMPATIBILITY_VERSION, inputStream, version);
        } catch (IOException e) {
            Log.e(TAG, "Could not install new content", e);
            return;
        }

        if (success) {
            // Update information about the stored version on successful install.
            mDataStore.setProperty(Config.VERSION, version);

            // Reset the number of consecutive log list failure updates back to zero.
            mDataStore.setPropertyLong(Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* value= */ 0L);
            mDataStore.store();
        } else {
            if (updateFailureCount()) {
                // TODO(378626065): Report FAILURE_VERSION_ALREADY_EXISTS failure via statsd.
            }
        }
    }

    private void handleDownloadFailed(DownloadStatus status) {
        Log.e(TAG, "Download failed with " + status);

        if (updateFailureCount()) {
            // TODO(378626065): Report download failure via statsd.
        }
    }

    /**
     * Updates the data store with the current number of consecutive log list update failures.
     *
     * @return whether the failure count exceeds the threshold and should be logged.
     */
    private boolean updateFailureCount() {
        long failure_count = mDataStore.getPropertyLong(
                Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0L);
        long new_failure_count = failure_count + 1L;

        mDataStore.setPropertyLong(Config.LOG_LIST_UPDATE_FAILURE_COUNT, new_failure_count);
        mDataStore.store();

        boolean shouldReport = new_failure_count >= Config.LOG_LIST_UPDATE_FAILURE_THRESHOLD;
        if (shouldReport) {
            Log.e(TAG,
                    "Log list update failure count exceeds threshold: " + new_failure_count);
        }
        return shouldReport;
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
    long getPublicKeyDownloadId() {
        return mDataStore.getPropertyLong(Config.PUBLIC_KEY_DOWNLOAD_ID, -1);
    }

    @VisibleForTesting
    long getMetadataDownloadId() {
        return mDataStore.getPropertyLong(Config.METADATA_DOWNLOAD_ID, -1);
    }

    @VisibleForTesting
    long getContentDownloadId() {
        return mDataStore.getPropertyLong(Config.CONTENT_DOWNLOAD_ID, -1);
    }

    @VisibleForTesting
    boolean hasPublicKeyDownloadId() {
        return getPublicKeyDownloadId() != -1;
    }

    @VisibleForTesting
    boolean hasMetadataDownloadId() {
        return getMetadataDownloadId() != -1;
    }

    @VisibleForTesting
    boolean hasContentDownloadId() {
        return getContentDownloadId() != -1;
    }

    @VisibleForTesting
    boolean isPublicKeyDownloadId(long downloadId) {
        return getPublicKeyDownloadId() == downloadId;
    }

    @VisibleForTesting
    boolean isMetadataDownloadId(long downloadId) {
        return getMetadataDownloadId() == downloadId;
    }

    @VisibleForTesting
    boolean isContentDownloadId(long downloadId) {
        return getContentDownloadId() == downloadId;
    }

    private Uri getPublicKeyDownloadUri() {
        return mDownloadHelper.getUri(getPublicKeyDownloadId());
    }

    private Uri getMetadataDownloadUri() {
        return mDownloadHelper.getUri(getMetadataDownloadId());
    }

    private Uri getContentDownloadUri() {
        return mDownloadHelper.getUri(getContentDownloadId());
    }
}
