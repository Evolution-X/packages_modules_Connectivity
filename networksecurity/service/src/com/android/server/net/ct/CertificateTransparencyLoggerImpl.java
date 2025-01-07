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

/** Implementation for logging to statsd for Certificate Transparency. */
class CertificateTransparencyLoggerImpl implements CertificateTransparencyLogger {

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

}
