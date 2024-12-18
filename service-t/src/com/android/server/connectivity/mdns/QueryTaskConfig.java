/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.MdnsSearchOptions.AGGRESSIVE_QUERY_MODE;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A configuration for the PeriodicalQueryTask that contains parameters to build a query packet.
 * Call to getConfigForNextRun returns a config that can be used to build the next query task.
 */
public class QueryTaskConfig {
    private static final int UNSIGNED_SHORT_MAX_VALUE = 65536;
    private final boolean alwaysAskForUnicastResponse =
            MdnsConfigs.alwaysAskForUnicastResponseInEachBurst();
    @VisibleForTesting
    final int transactionId;
    @VisibleForTesting
    final boolean expectUnicastResponse;
    final int queryIndex;
    final int queryMode;

    QueryTaskConfig(int queryMode, int queryIndex, int transactionId) {
        this.queryMode = queryMode;
        this.transactionId = transactionId;
        this.queryIndex = queryIndex;
        this.expectUnicastResponse = getExpectUnicastResponse();
    }

    QueryTaskConfig(int queryMode) {
        this(queryMode, 0, 1);
    }

    /**
     * Get new QueryTaskConfig for next run.
     */
    public QueryTaskConfig getConfigForNextRun(int queryMode) {
        final int newQueryIndex = queryIndex + 1;
        int newTransactionId = transactionId + 1;
        if (newTransactionId > UNSIGNED_SHORT_MAX_VALUE) {
            newTransactionId = 1;
        }

        return new QueryTaskConfig(queryMode, newQueryIndex, newTransactionId);
    }

    private boolean getExpectUnicastResponse() {
        if (queryMode == AGGRESSIVE_QUERY_MODE) {
            if (MdnsQueryScheduler.isFirstQueryInBurst(queryIndex, queryMode)) {
                return true;
            }
        }
        return queryIndex == 0 || alwaysAskForUnicastResponse;
    }
}
