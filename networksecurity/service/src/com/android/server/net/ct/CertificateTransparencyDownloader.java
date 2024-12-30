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

import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_DEVICE_OFFLINE;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_DOWNLOAD_CANNOT_RESUME;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_HTTP_ERROR;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_NO_DISK_SPACE;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_VERIFICATION;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_TOO_MANY_REDIRECTS;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_UNKNOWN;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_VERSION_ALREADY_EXISTS;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__PENDING_WAITING_FOR_WIFI;

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
import java.security.InvalidKeyException;

/** Helper class to download certificate transparency log files. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyDownloader";

    private final Context mContext;
    private final DataStore mDataStore;
    private final DownloadHelper mDownloadHelper;
    private final SignatureVerifier mSignatureVerifier;
    private final CertificateTransparencyInstaller mInstaller;
    private final CertificateTransparencyLogger mLogger;

    private boolean started = false;

    CertificateTransparencyDownloader(
            Context context,
            DataStore dataStore,
            DownloadHelper downloadHelper,
            SignatureVerifier signatureVerifier,
            CertificateTransparencyInstaller installer,
            CertificateTransparencyLogger logger) {
        mContext = context;
        mSignatureVerifier = signatureVerifier;
        mDataStore = dataStore;
        mDownloadHelper = downloadHelper;
        mInstaller = installer;
        mLogger = logger;
    }

    void start() {
        if (started) {
            return;
        }
        mInstaller.addCompatibilityVersion(Config.COMPATIBILITY_VERSION);
        mContext.registerReceiver(
                this,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED);
        started = true;

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader started.");
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        mContext.unregisterReceiver(this);
        mInstaller.removeCompatibilityVersion(Config.COMPATIBILITY_VERSION);
        started = false;

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader stopped.");
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
        boolean failureLogged = false;

        try {
            success = mSignatureVerifier.verify(contentUri, metadataUri);
        } catch (MissingPublicKeyException e) {
            if (updateFailureCount()) {
                failureLogged = true;
                mLogger.logCTLogListUpdateFailedEvent(
                        CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_NOT_FOUND,
                        mDataStore.getPropertyInt(
                            Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0)
                );
            }
        } catch (InvalidKeyException e) {
            if (updateFailureCount()) {
                failureLogged = true;
                mLogger.logCTLogListUpdateFailedEvent(
                        CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_VERIFICATION,
                        mDataStore.getPropertyInt(
                            Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0)
                );
            }
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "Could not verify new log list", e);
        }

        if (!success) {
            Log.w(TAG, "Log list did not pass verification");

            // Avoid logging failure twice
            if (!failureLogged && updateFailureCount()) {
                mLogger.logCTLogListUpdateFailedEvent(
                        CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_VERIFICATION,
                        mDataStore.getPropertyInt(
                            Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0));
            }
            return;
        }

        String version = null;
        try (InputStream inputStream = mContext.getContentResolver().openInputStream(contentUri)) {
            version =
                    new JSONObject(new String(inputStream.readAllBytes(), UTF_8))
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
            mDataStore.setPropertyInt(Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* value= */ 0);
            mDataStore.store();
        } else {
            if (updateFailureCount()) {
                mLogger.logCTLogListUpdateFailedEvent(
                        CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_VERSION_ALREADY_EXISTS,
                        mDataStore.getPropertyInt(
                                Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0));
            }
        }
    }

    private void handleDownloadFailed(DownloadStatus status) {
        Log.e(TAG, "Download failed with " + status);

        if (updateFailureCount()) {
            int failureCount =
                    mDataStore.getPropertyInt(
                            Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0);

            // HTTP Error
            if (400 <= status.reason() && status.reason() <= 600) {
                mLogger.logCTLogListUpdateFailedEvent(
                        CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_HTTP_ERROR,
                        failureCount,
                        status.reason());
            } else {
                // TODO(b/384935059): handle blocked domain logging
                // TODO(b/384936292): add additionalchecks for pending wifi status
                mLogger.logCTLogListUpdateFailedEvent(
                        downloadStatusToFailureReason(status.reason()), failureCount);
            }
        }
    }

    /** Converts DownloadStatus reason into failure reason to log. */
    private int downloadStatusToFailureReason(int downloadStatusReason) {
        switch (downloadStatusReason) {
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_DEVICE_OFFLINE;
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_HTTP_ERROR;
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_TOO_MANY_REDIRECTS;
            case DownloadManager.ERROR_CANNOT_RESUME:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_DOWNLOAD_CANNOT_RESUME;
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_NO_DISK_SPACE;
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__PENDING_WAITING_FOR_WIFI;
            default:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_UNKNOWN;
        }
    }

    /**
     * Updates the data store with the current number of consecutive log list update failures.
     *
     * @return whether the failure count exceeds the threshold and should be logged.
     */
    private boolean updateFailureCount() {
        int failure_count =
                mDataStore.getPropertyInt(
                        Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0);
        int new_failure_count = failure_count + 1;

        mDataStore.setPropertyInt(Config.LOG_LIST_UPDATE_FAILURE_COUNT, new_failure_count);
        mDataStore.store();

        boolean shouldReport = new_failure_count >= Config.LOG_LIST_UPDATE_FAILURE_THRESHOLD;
        if (shouldReport) {
            Log.d(TAG, "Log list update failure count exceeds threshold: " + new_failure_count);
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
