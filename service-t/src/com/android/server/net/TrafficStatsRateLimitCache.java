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

package com.android.server.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkStats;

import com.android.net.module.util.LruCacheWithExpiry;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A thread-safe cache for storing and retrieving {@link NetworkStats.Entry} objects,
 * with an adjustable expiry duration to manage data freshness.
 *
 * @deprecated Use {@link LruCacheWithExpiry} instead.
 */
// TODO: Remove this when service side rate limit cache solution is removed.
class TrafficStatsRateLimitCache extends
        LruCacheWithExpiry<TrafficStatsRateLimitCache.TrafficStatsCacheKey, NetworkStats.Entry> {

    /**
     * Constructs a new {@link TrafficStatsRateLimitCache} with the specified expiry duration.
     *
     * @param clock The {@link Clock} to use for determining timestamps.
     * @param expiryDurationMs The expiry duration in milliseconds.
     * @param maxSize Maximum number of entries.
     */
    TrafficStatsRateLimitCache(@NonNull Clock clock, long expiryDurationMs, int maxSize) {
        super(()-> clock.millis(), expiryDurationMs, maxSize, it -> !it.isEmpty());
    }

    public static class TrafficStatsCacheKey {
        @Nullable
        private final String mIface;
        private final int mUid;

        TrafficStatsCacheKey(@Nullable String iface, int uid) {
            this.mIface = iface;
            this.mUid = uid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TrafficStatsCacheKey)) return false;
            TrafficStatsCacheKey that = (TrafficStatsCacheKey) o;
            return mUid == that.mUid && Objects.equals(mIface, that.mIface);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIface, mUid);
        }
    }

    /**
     * Retrieves a {@link NetworkStats.Entry} from the cache, associated with the given key.
     *
     * @param iface The interface name to include in the cache key. Null if not applicable.
     * @param uid The UID to include in the cache key. {@code UID_ALL} if not applicable.
     * @return The cached {@link NetworkStats.Entry}, or null if not found or expired.
     */
    @Nullable
    NetworkStats.Entry get(String iface, int uid) {
        return super.get(new TrafficStatsCacheKey(iface, uid));
    }

    /**
     * Retrieves a {@link NetworkStats.Entry} from the cache, associated with the given key.
     * If the entry is not found in the cache or has expired, computes it using the provided
     * {@code supplier} and stores the result in the cache.
     *
     * @param iface The interface name to include in the cache key. {@code IFACE_ALL}
     *              if not applicable.
     * @param uid The UID to include in the cache key. {@code UID_ALL} if not applicable.
     * @param supplier The {@link Supplier} to compute the {@link NetworkStats.Entry} if not found.
     * @return The cached or computed {@link NetworkStats.Entry}, or null if not found, expired,
     *         or if the {@code supplier} returns null.
     */
    @Nullable
    NetworkStats.Entry getOrCompute(String iface, int uid,
            @NonNull Supplier<NetworkStats.Entry> supplier) {
        return super.getOrCompute(new TrafficStatsCacheKey(iface, uid), supplier);
    }

    /**
     * Stores a {@link NetworkStats.Entry} in the cache, associated with the given key.
     *
     * @param iface The interface name to include in the cache key. Null if not applicable.
     * @param uid   The UID to include in the cache key. {@code UID_ALL} if not applicable.
     * @param entry The {@link NetworkStats.Entry} to store in the cache.
     */
    void put(String iface, int uid, @NonNull final NetworkStats.Entry entry) {
        super.put(new TrafficStatsCacheKey(iface, uid), entry);
    }

}
