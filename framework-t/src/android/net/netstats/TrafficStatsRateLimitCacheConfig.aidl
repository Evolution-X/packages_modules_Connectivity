/**
 * Copyright (c) 2024, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.netstats;

/**
 * Configuration for the TrafficStats rate limit cache.
 *
 * @hide
 */
@JavaDerive(equals=true, toString=true)
@JavaOnlyImmutable
parcelable TrafficStatsRateLimitCacheConfig {

    /**
     * Whether the cache is enabled for V+ device or target Sdk V+ apps.
     */
    boolean isCacheEnabled;

    /**
     * The duration for which cache entries are valid, in milliseconds.
     */
    int expiryDurationMs;

    /**
     * The maximum number of entries to store in the cache.
     */
    int maxEntries;
}
