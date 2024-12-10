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

package android.net

import android.net.TrafficStats.UNSUPPORTED
import android.net.netstats.StatsResult
import android.net.netstats.TrafficStatsRateLimitCacheConfig
import android.os.Build
import com.android.server.net.NetworkStatsService.TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule.FeatureFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.HashMap
import java.util.function.LongSupplier

const val TEST_EXPIRY_DURATION_MS = 1000
const val TEST_IFACE = "wlan0"

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class TrafficStatsTest {
    private val binder = mock(INetworkStatsService::class.java)
    private val myUid = android.os.Process.myUid()
    private val mockMyUidStatsResult = StatsResult(5L, 6L, 7L, 8L)
    private val mockIfaceStatsResult = StatsResult(7L, 3L, 10L, 21L)
    private val mockTotalStatsResult = StatsResult(8L, 1L, 5L, 2L)
    private val secondUidStatsResult = StatsResult(3L, 7L, 10L, 5L)
    private val secondIfaceStatsResult = StatsResult(9L, 8L, 7L, 6L)
    private val secondTotalStatsResult = StatsResult(4L, 3L, 2L, 1L)
    private val emptyStatsResult = StatsResult(0L, 0L, 0L, 0L)
    private val unsupportedStatsResult =
            StatsResult(UNSUPPORTED.toLong(), UNSUPPORTED.toLong(),
                    UNSUPPORTED.toLong(), UNSUPPORTED.toLong())

    private val cacheDisabledConfig = TrafficStatsRateLimitCacheConfig.Builder()
            .setIsCacheEnabled(false)
            .setExpiryDurationMs(0)
            .setMaxEntries(0)
            .build()
    private val cacheEnabledConfig = TrafficStatsRateLimitCacheConfig.Builder()
            .setIsCacheEnabled(true)
            .setExpiryDurationMs(TEST_EXPIRY_DURATION_MS)
            .setMaxEntries(100)
            .build()
    private val mTestTimeSupplier = TestTimeSupplier()

    private val featureFlags = HashMap<String, Boolean>()

    // This will set feature flags from @FeatureFlag annotations
    // into the map before setUp() runs.
    @get:Rule
    val setFeatureFlagsRule = SetFeatureFlagsRule(
            { name, enabled -> featureFlags.put(name, enabled == true) },
            { name -> featureFlags.getOrDefault(name, false) }
    )

    class TestTimeSupplier : LongSupplier {
        private var currentTimeMillis = 0L

        override fun getAsLong() = currentTimeMillis

        fun advanceTime(millis: Int) {
            currentTimeMillis += millis
        }
    }

    @Before
    fun setUp() {
        TrafficStats.setServiceForTest(binder)
        TrafficStats.setTimeSupplierForTest(mTestTimeSupplier)
        mockStats(mockMyUidStatsResult, mockIfaceStatsResult, mockTotalStatsResult)
        if (featureFlags.getOrDefault(TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG, false)) {
            doReturn(cacheEnabledConfig).`when`(binder).getRateLimitCacheConfig()
        } else {
            doReturn(cacheDisabledConfig).`when`(binder).getRateLimitCacheConfig()
        }
        TrafficStats.reinitRateLimitCacheForTest()
    }

    @After
    fun tearDown() {
        TrafficStats.setServiceForTest(null)
        TrafficStats.setTimeSupplierForTest(null)
        TrafficStats.reinitRateLimitCacheForTest()
    }

    private fun assertUidStats(uid: Int, stats: StatsResult) {
        assertEquals(stats.rxBytes, TrafficStats.getUidRxBytes(uid))
        assertEquals(stats.rxPackets, TrafficStats.getUidRxPackets(uid))
        assertEquals(stats.txBytes, TrafficStats.getUidTxBytes(uid))
        assertEquals(stats.txPackets, TrafficStats.getUidTxPackets(uid))
    }

    private fun assertIfaceStats(iface: String, stats: StatsResult) {
        assertEquals(stats.rxBytes, TrafficStats.getRxBytes(iface))
        assertEquals(stats.rxPackets, TrafficStats.getRxPackets(iface))
        assertEquals(stats.txBytes, TrafficStats.getTxBytes(iface))
        assertEquals(stats.txPackets, TrafficStats.getTxPackets(iface))
    }

    private fun assertTotalStats(stats: StatsResult) {
        assertEquals(stats.rxBytes, TrafficStats.getTotalRxBytes())
        assertEquals(stats.rxPackets, TrafficStats.getTotalRxPackets())
        assertEquals(stats.txBytes, TrafficStats.getTotalTxBytes())
        assertEquals(stats.txPackets, TrafficStats.getTotalTxPackets())
    }

    private fun mockStats(uidStats: StatsResult?, ifaceStats: StatsResult?,
                          totalStats: StatsResult?) {
        doReturn(uidStats).`when`(binder).getUidStats(myUid)
        doReturn(ifaceStats).`when`(binder).getIfaceStats(TEST_IFACE)
        doReturn(totalStats).`when`(binder).getTotalStats()
    }

    private fun assertStats(uidStats: StatsResult, ifaceStats: StatsResult,
                            totalStats: StatsResult) {
        assertUidStats(myUid, uidStats)
        assertIfaceStats(TEST_IFACE, ifaceStats)
        assertTotalStats(totalStats)
    }

    private fun assertStatsFetchInvocations(wantedInvocations: Int) {
        verify(binder, times(wantedInvocations)).getUidStats(myUid)
        verify(binder, times(wantedInvocations)).getIfaceStats(TEST_IFACE)
        verify(binder, times(wantedInvocations)).getTotalStats()
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG)
    @Test
    fun testRateLimitCacheExpiry_cacheEnabled() {
        // Initial fetch, verify binder calls.
        assertStats(mockMyUidStatsResult, mockIfaceStatsResult, mockTotalStatsResult)
        assertStatsFetchInvocations(1)

        // Advance time within expiry, verify cached values used.
        clearInvocations(binder)
        mockStats(secondUidStatsResult, secondIfaceStatsResult, secondTotalStatsResult)
        mTestTimeSupplier.advanceTime(1)
        assertStats(mockMyUidStatsResult, mockIfaceStatsResult, mockTotalStatsResult)
        assertStatsFetchInvocations(0)

        // Advance time to expire cache, verify new values fetched.
        clearInvocations(binder)
        mTestTimeSupplier.advanceTime(TEST_EXPIRY_DURATION_MS)
        assertStats(secondUidStatsResult, secondIfaceStatsResult, secondTotalStatsResult)
        assertStatsFetchInvocations(1)
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG, enabled = false)
    @Test
    fun testRateLimitCacheExpiry_cacheDisabled() {
        // Initial fetch, verify binder calls.
        assertStats(mockMyUidStatsResult, mockIfaceStatsResult, mockTotalStatsResult)
        assertStatsFetchInvocations(4)

        // Advance time within expiry, verify new values fetched.
        clearInvocations(binder)
        mockStats(secondUidStatsResult, secondIfaceStatsResult, secondTotalStatsResult)
        mTestTimeSupplier.advanceTime(1)
        assertStats(secondUidStatsResult, secondIfaceStatsResult, secondTotalStatsResult)
        assertStatsFetchInvocations(4)
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG)
    @Test
    fun testInvalidStatsNotCached_cacheEnabled() {
        doTestInvalidStatsNotCached()
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG, enabled = false)
    @Test
    fun testInvalidStatsNotCached_cacheDisabled() {
        doTestInvalidStatsNotCached()
    }

    private fun doTestInvalidStatsNotCached() {
        // Mock null stats, this usually happens when the query is not valid,
        // e.g. query uid stats of other application.
        mockStats(null, null, null)
        assertStats(unsupportedStatsResult, unsupportedStatsResult, unsupportedStatsResult)
        assertStatsFetchInvocations(4)

        // Verify null stats is not cached, and mock empty stats. This usually
        // happens when queries with non-existent interface names.
        clearInvocations(binder)
        mockStats(emptyStatsResult, emptyStatsResult, emptyStatsResult)
        assertStats(emptyStatsResult, emptyStatsResult, emptyStatsResult)
        assertStatsFetchInvocations(4)

        // Verify empty result is also not cached.
        clearInvocations(binder)
        assertStats(emptyStatsResult, emptyStatsResult, emptyStatsResult)
        assertStatsFetchInvocations(4)
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG)
    @Test
    fun testClearRateLimitCaches_cacheEnabled() {
        doTestClearRateLimitCaches(true)
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG, enabled = false)
    @Test
    fun testClearRateLimitCaches_cacheDisabled() {
        doTestClearRateLimitCaches(false)
    }

    private fun doTestClearRateLimitCaches(cacheEnabled: Boolean) {
        // Initial fetch, verify binder calls.
        assertStats(mockMyUidStatsResult, mockIfaceStatsResult, mockTotalStatsResult)
        assertStatsFetchInvocations(if (cacheEnabled) 1 else 4)

        // Verify cached values are used.
        clearInvocations(binder)
        assertStats(mockMyUidStatsResult, mockIfaceStatsResult, mockTotalStatsResult)
        assertStatsFetchInvocations(if (cacheEnabled) 0 else 4)

        // Clear caches, verify fetching from the service.
        clearInvocations(binder)
        TrafficStats.clearRateLimitCaches()
        mockStats(secondUidStatsResult, secondIfaceStatsResult, secondTotalStatsResult)
        assertStats(secondUidStatsResult, secondIfaceStatsResult, secondTotalStatsResult)
        assertStatsFetchInvocations(if (cacheEnabled) 1 else 4)
    }
}
