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

import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_DEVICE_OFFLINE;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_DOWNLOAD_CANNOT_RESUME;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_HTTP_ERROR;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_NO_DISK_SPACE;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_TOO_MANY_REDIRECTS;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_UNKNOWN;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__PENDING_WAITING_FOR_WIFI;

import android.app.DownloadManager;

/** Implementation for logging to statsd for Certificate Transparency. */
class CertificateTransparencyLoggerImpl implements CertificateTransparencyLogger {

    @Override
    public void logCTLogListUpdateFailedEventWithDownloadStatus(
            int downloadStatus, int failureCount) {
        logCTLogListUpdateFailedEvent(downloadStatusToFailureReason(downloadStatus), failureCount);
    }

    @Override
    public void logCTLogListUpdateFailedEvent(int failureReason, int failureCount) {
        logCTLogListUpdateFailedEvent(failureReason, failureCount, /* httpErrorStatusCode= */ 0);
    }

    @Override
    public void logCTLogListUpdateFailedEvent(
            int failureReason, int failureCount, int httpErrorStatusCode) {
        CertificateTransparencyStatsLog.write(
                CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED,
                failureReason,
                failureCount,
                httpErrorStatusCode
        );
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

}
