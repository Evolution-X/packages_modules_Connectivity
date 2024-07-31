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

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

/** Helper class to download certificate transparency log files. */
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyDownloader";

    private final Context mContext;
    private final DataStore mDataStore;
    private final DownloadHelper mDownloadHelper;

    @VisibleForTesting
    CertificateTransparencyDownloader(
            Context context, DataStore dataStore, DownloadHelper downloadHelper) {
        mContext = context;
        mDataStore = dataStore;
        mDownloadHelper = downloadHelper;
    }

    CertificateTransparencyDownloader(Context context, DataStore dataStore) {
        this(context, dataStore, new DownloadHelper(context));
    }

    void registerReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        mContext.registerReceiver(this, intentFilter);

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

        startContentDownload(mDataStore.getProperty(Config.CONTENT_URL));
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

        // TODO: 1. verify file signature, 2. validate file content, 3. install log file.
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
