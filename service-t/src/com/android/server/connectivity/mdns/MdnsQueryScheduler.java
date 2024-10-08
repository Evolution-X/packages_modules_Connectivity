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

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * The query scheduler class for calculating next query tasks parameters.
 * <p>
 * The class is not thread-safe and needs to be used on a consistent thread.
 */
public class MdnsQueryScheduler {

    @VisibleForTesting
    // RFC 6762 5.2: The interval between the first two queries MUST be at least one second.
    static final int INITIAL_AGGRESSIVE_TIME_BETWEEN_BURSTS_MS = 1000;
    private static final int INITIAL_TIME_BETWEEN_BURSTS_MS =
            (int) MdnsConfigs.initialTimeBetweenBurstsMs();
    private static final int MAX_TIME_BETWEEN_ACTIVE_PASSIVE_BURSTS_MS =
            (int) MdnsConfigs.timeBetweenBurstsMs();
    private static final int QUERIES_PER_BURST = (int) MdnsConfigs.queriesPerBurst();
    private static final int TIME_BETWEEN_QUERIES_IN_BURST_MS =
            (int) MdnsConfigs.timeBetweenQueriesInBurstMs();
    private static final int QUERIES_PER_BURST_PASSIVE_MODE =
            (int) MdnsConfigs.queriesPerBurstPassive();
    @VisibleForTesting
    // Basically this tries to send one query per typical DTIM interval 100ms, to maximize the
    // chances that a query will be received if devices are using a DTIM multiplier (in which case
    // they only listen once every [multiplier] DTIM intervals).
    static final int TIME_BETWEEN_RETRANSMISSION_QUERIES_IN_BURST_MS = 100;
    static final int MAX_TIME_BETWEEN_AGGRESSIVE_BURSTS_MS = 60000;

    /**
     * The argument for tracking the query tasks status.
     */
    public static class ScheduledQueryTaskArgs {
        public final QueryTaskConfig config;
        public final long timeToRun;
        public final long minTtlExpirationTimeWhenScheduled;
        public final long sessionId;

        ScheduledQueryTaskArgs(@NonNull QueryTaskConfig config, long timeToRun,
                long minTtlExpirationTimeWhenScheduled, long sessionId) {
            this.config = config;
            this.timeToRun = timeToRun;
            this.minTtlExpirationTimeWhenScheduled = minTtlExpirationTimeWhenScheduled;
            this.sessionId = sessionId;
        }
    }

    @Nullable
    private ScheduledQueryTaskArgs mLastScheduledQueryTaskArgs;

    public MdnsQueryScheduler() {
    }

    /**
     * Cancel the scheduled run. The method needed to be called when the scheduled task need to
     * be canceled and rescheduling is not need.
     */
    public void cancelScheduledRun() {
        mLastScheduledQueryTaskArgs = null;
    }

    /**
     * Calculates ScheduledQueryTaskArgs for rescheduling the current task. Returns null if the
     * rescheduling is not necessary.
     */
    @Nullable
    public ScheduledQueryTaskArgs maybeRescheduleCurrentRun(
            long now,
            long minRemainingTtl,
            long lastSentTime,
            long sessionId,
            int numOfQueriesBeforeBackoff) {
        if (mLastScheduledQueryTaskArgs == null) {
            return null;
        }
        final QueryTaskConfig lastConfig = mLastScheduledQueryTaskArgs.config;
        if (!shouldUseQueryBackoff(lastConfig.queryIndex, lastConfig.queryMode,
                numOfQueriesBeforeBackoff)) {
            return null;
        }

        final long timeToRun = calculateTimeToRun(mLastScheduledQueryTaskArgs,
                lastConfig.queryIndex, lastConfig.queryMode, now, minRemainingTtl, lastSentTime,
                numOfQueriesBeforeBackoff, false /* forceEnableBackoff */);

        if (timeToRun <= mLastScheduledQueryTaskArgs.timeToRun) {
            return null;
        }

        mLastScheduledQueryTaskArgs = new ScheduledQueryTaskArgs(lastConfig,
                timeToRun,
                minRemainingTtl + now,
                sessionId);
        return mLastScheduledQueryTaskArgs;
    }

    /**
     *  Calculates the ScheduledQueryTaskArgs for the next run.
     */
    @NonNull
    public ScheduledQueryTaskArgs scheduleNextRun(
            @NonNull QueryTaskConfig currentConfig,
            long minRemainingTtl,
            long now,
            long lastSentTime,
            long sessionId,
            int queryMode,
            int numOfQueriesBeforeBackoff,
            boolean forceEnableBackoff) {
        final int newQueryIndex = currentConfig.getConfigForNextRun(queryMode).queryIndex;
        long timeToRun;
        if (mLastScheduledQueryTaskArgs == null && !forceEnableBackoff) {
            timeToRun = now + getDelayBeforeTaskWithoutBackoff(
                    newQueryIndex, queryMode);
        } else {
            timeToRun = calculateTimeToRun(mLastScheduledQueryTaskArgs, newQueryIndex,
                    queryMode, now, minRemainingTtl, lastSentTime,
                    numOfQueriesBeforeBackoff, forceEnableBackoff);
        }
        mLastScheduledQueryTaskArgs = new ScheduledQueryTaskArgs(
                currentConfig.getConfigForNextRun(queryMode),
                timeToRun, minRemainingTtl + now,
                sessionId);
        return mLastScheduledQueryTaskArgs;
    }

    /**
     *  Calculates the ScheduledQueryTaskArgs for the initial run.
     */
    public ScheduledQueryTaskArgs scheduleFirstRun(@NonNull QueryTaskConfig taskConfig,
            long now, long minRemainingTtl, long currentSessionId) {
        mLastScheduledQueryTaskArgs = new ScheduledQueryTaskArgs(taskConfig, now /* timeToRun */,
                now + minRemainingTtl/* minTtlExpirationTimeWhenScheduled */,
                currentSessionId);
        return mLastScheduledQueryTaskArgs;
    }

    private static long calculateTimeToRun(@Nullable ScheduledQueryTaskArgs taskArgs,
            int queryIndex, int queryMode, long now, long minRemainingTtl, long lastSentTime,
            int numOfQueriesBeforeBackoff, boolean forceEnableBackoff) {
        final long baseDelayInMs = getDelayBeforeTaskWithoutBackoff(queryIndex, queryMode);
        if (!(forceEnableBackoff
                || shouldUseQueryBackoff(queryIndex, queryMode, numOfQueriesBeforeBackoff))) {
            return lastSentTime + baseDelayInMs;
        }
        if (minRemainingTtl <= 0) {
            // There's no service, or there is an expired service. In any case, schedule for the
            // minimum time, which is the base delay.
            return lastSentTime + baseDelayInMs;
        }
        // If the next TTL expiration time hasn't changed, then use previous calculated timeToRun.
        if (lastSentTime < now && taskArgs != null
                && taskArgs.minTtlExpirationTimeWhenScheduled == now + minRemainingTtl) {
            // Use the original scheduling time if the TTL has not changed, to avoid continuously
            // rescheduling to 80% of the remaining TTL as time passes
            return taskArgs.timeToRun;
        }
        return Math.max(now + (long) (0.8 * minRemainingTtl), lastSentTime + baseDelayInMs);
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

    static boolean isFirstQueryInBurst(int queryIndex, int queryMode) {
        return getQueryIndexInBurst(queryIndex, queryMode) == 0;
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
     * Determine if the query backoff should be used.
     */
    public static boolean shouldUseQueryBackoff(int queryIndex, int queryMode,
            int numOfQueriesBeforeBackoff) {
        // Don't enable backoff mode during the burst or in the first burst
        if (!isFirstQueryInBurst(queryIndex, queryMode) || isFirstBurst(queryIndex, queryMode)) {
            return false;
        }
        return queryIndex > numOfQueriesBeforeBackoff;
    }
}
