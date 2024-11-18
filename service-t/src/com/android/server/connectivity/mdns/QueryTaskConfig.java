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
import static com.android.server.connectivity.mdns.MdnsSearchOptions.PASSIVE_QUERY_MODE;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A configuration for the PeriodicalQueryTask that contains parameters to build a query packet.
 * Call to getConfigForNextRun returns a config that can be used to build the next query task.
 */
public class QueryTaskConfig {

    private static final int INITIAL_TIME_BETWEEN_BURSTS_MS =
            (int) MdnsConfigs.initialTimeBetweenBurstsMs();
    private static final int MAX_TIME_BETWEEN_ACTIVE_PASSIVE_BURSTS_MS =
            (int) MdnsConfigs.timeBetweenBurstsMs();
    private static final int QUERIES_PER_BURST = (int) MdnsConfigs.queriesPerBurst();
    private static final int TIME_BETWEEN_QUERIES_IN_BURST_MS =
            (int) MdnsConfigs.timeBetweenQueriesInBurstMs();
    private static final int QUERIES_PER_BURST_PASSIVE_MODE =
            (int) MdnsConfigs.queriesPerBurstPassive();
    private static final int UNSIGNED_SHORT_MAX_VALUE = 65536;
    @VisibleForTesting
    // RFC 6762 5.2: The interval between the first two queries MUST be at least one second.
    static final int INITIAL_AGGRESSIVE_TIME_BETWEEN_BURSTS_MS = 1000;
    @VisibleForTesting
    // Basically this tries to send one query per typical DTIM interval 100ms, to maximize the
    // chances that a query will be received if devices are using a DTIM multiplier (in which case
    // they only listen once every [multiplier] DTIM intervals).
    static final int TIME_BETWEEN_RETRANSMISSION_QUERIES_IN_BURST_MS = 100;
    static final int MAX_TIME_BETWEEN_AGGRESSIVE_BURSTS_MS = 60000;
    private final boolean alwaysAskForUnicastResponse =
            MdnsConfigs.alwaysAskForUnicastResponseInEachBurst();
    @VisibleForTesting
    final int transactionId;
    @VisibleForTesting
    final boolean expectUnicastResponse;
    private final int queryIndex;
    private final int queryMode;

    QueryTaskConfig(int queryMode, int queryIndex, int transactionId,
            boolean expectUnicastResponse) {
        this.queryMode = queryMode;
        this.transactionId = transactionId;
        this.queryIndex = queryIndex;
        this.expectUnicastResponse = expectUnicastResponse;
    }

    QueryTaskConfig(int queryMode) {
        this(queryMode, 0, 1, true);
    }

    private static int getBurstIndex(int queryIndex, int queryMode) {
        if (queryMode == PASSIVE_QUERY_MODE && queryIndex >= QUERIES_PER_BURST) {
            // In passive mode, after the first burst of QUERIES_PER_BURST queries, subsequent
            // bursts have QUERIES_PER_BURST_PASSIVE_MODE queries.
            final int queryIndexAfterFirstBurst = queryIndex - QUERIES_PER_BURST;
            return 1 + (queryIndexAfterFirstBurst / QUERIES_PER_BURST_PASSIVE_MODE);
        } else {
            return queryIndex / QUERIES_PER_BURST;
        }
    }

    private static int getQueryIndexInBurst(int queryIndex, int queryMode) {
        if (queryMode == PASSIVE_QUERY_MODE && queryIndex >= QUERIES_PER_BURST) {
            final int queryIndexAfterFirstBurst = queryIndex - QUERIES_PER_BURST;
            return queryIndexAfterFirstBurst % QUERIES_PER_BURST_PASSIVE_MODE;
        } else {
            return queryIndex % QUERIES_PER_BURST;
        }
    }

    private static boolean isFirstBurst(int queryIndex, int queryMode) {
        return getBurstIndex(queryIndex, queryMode) == 0;
    }

    private static boolean isFirstQueryInBurst(int queryIndex, int queryMode) {
        return getQueryIndexInBurst(queryIndex, queryMode) == 0;
    }

    // TODO: move delay calculations to MdnsQueryScheduler
    long getDelayBeforeTaskWithoutBackoff() {
        return getDelayBeforeTaskWithoutBackoff(queryIndex, queryMode);
    }

    private static long getDelayBeforeTaskWithoutBackoff(int queryIndex, int queryMode) {
        final int burstIndex = getBurstIndex(queryIndex, queryMode);
        final int queryIndexInBurst = getQueryIndexInBurst(queryIndex, queryMode);
        if (queryIndexInBurst == 0) {
            return getTimeToBurstMs(burstIndex, queryMode);
        } else if (queryIndexInBurst == 1 && queryMode == AGGRESSIVE_QUERY_MODE) {
            // In aggressive mode, the first 2 queries are sent without delay.
            return 0;
        }
        return queryMode == AGGRESSIVE_QUERY_MODE
                ? TIME_BETWEEN_RETRANSMISSION_QUERIES_IN_BURST_MS
                : TIME_BETWEEN_QUERIES_IN_BURST_MS;
    }

    private boolean getExpectUnicastResponse(int queryIndex, int queryMode) {
        if (queryMode == AGGRESSIVE_QUERY_MODE) {
            if (isFirstQueryInBurst(queryIndex, queryMode)) {
                return true;
            }
        }
        return alwaysAskForUnicastResponse;
    }

    /**
     * Shifts a value left by the specified number of bits, coercing to at most maxValue.
     *
     * <p>This allows calculating min(value*2^shift, maxValue) without overflow.
     */
    private static int boundedLeftShift(int value, int shift, int maxValue) {
        // There must be at least one leading zero for positive values, so the maximum left shift
        // without overflow is the number of leading zeros minus one.
        final int maxShift = Integer.numberOfLeadingZeros(value) - 1;
        if (shift > maxShift) {
            // The shift would overflow positive integers, so is greater than maxValue.
            return maxValue;
        }
        return Math.min(value << shift, maxValue);
    }

    private static int getTimeToBurstMs(int burstIndex, int queryMode) {
        if (burstIndex == 0) {
            // No delay before the first burst
            return 0;
        }
        switch (queryMode) {
            case PASSIVE_QUERY_MODE:
                return MAX_TIME_BETWEEN_ACTIVE_PASSIVE_BURSTS_MS;
            case AGGRESSIVE_QUERY_MODE:
                return boundedLeftShift(INITIAL_AGGRESSIVE_TIME_BETWEEN_BURSTS_MS,
                        burstIndex - 1,
                        MAX_TIME_BETWEEN_AGGRESSIVE_BURSTS_MS);
            default: // ACTIVE_QUERY_MODE
                return boundedLeftShift(INITIAL_TIME_BETWEEN_BURSTS_MS,
                        burstIndex - 1,
                        MAX_TIME_BETWEEN_ACTIVE_PASSIVE_BURSTS_MS);
        }
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

        return new QueryTaskConfig(queryMode, newQueryIndex, newTransactionId,
                getExpectUnicastResponse(newQueryIndex, queryMode));
    }

    /**
     * Determine if the query backoff should be used.
     */
    public boolean shouldUseQueryBackoff(int numOfQueriesBeforeBackoff) {
        // Don't enable backoff mode during the burst or in the first burst
        if (!isFirstQueryInBurst(queryIndex, queryMode) || isFirstBurst(queryIndex, queryMode)) {
            return false;
        }
        return queryIndex > numOfQueriesBeforeBackoff;
    }
}
