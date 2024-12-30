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

/** Helper class to interface with logging to statsd. */
public class CertificateTransparencyLogger {

    public CertificateTransparencyLogger() {}

    /**
     * Logs a CTLogListUpdateFailed event to statsd, when no HTTP error status code is present.
     *
     * @param failureReason reason why the log list wasn't updated (e.g. DownloadManager failures)
     * @param failureCount number of consecutive log list update failures
     */
    public void logCTLogListUpdateFailedEvent(int failureReason, int failureCount) {
        logCTLogListUpdateFailedEvent(failureReason, failureCount, /* httpErrorStatusCode= */ 0);
    }

    /**
     * Logs a CTLogListUpdateFailed event to statsd, when an HTTP error status code is provided.
     *
     * @param failureReason reason why the log list wasn't updated (e.g. DownloadManager failures)
     * @param failureCount number of consecutive log list update failures
     * @param httpErrorStatusCode if relevant, the HTTP error status code from DownloadManager
     */
    public void logCTLogListUpdateFailedEvent(
            int failureReason, int failureCount, int httpErrorStatusCode) {
        CertificateTransparencyStatsLog.write(
                CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED,
                failureReason,
                failureCount,
                httpErrorStatusCode
        );
    }
}
