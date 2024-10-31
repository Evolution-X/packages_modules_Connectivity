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

import android.net.NetworkStats.UID_ALL
import android.net.TrafficStats.UNSUPPORTED
import android.net.netstats.StatsResult
import android.os.Build
import android.os.Process.SYSTEM_UID
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class TrafficStatsTest {
    private val binder = mock(INetworkStatsService::class.java)
    private val myUid = android.os.Process.myUid()
    private val notMyUid = myUid + 1
    private val mockSystemUidStatsResult = StatsResult(1L, 2L, 3L, 4L)
    private val mockMyUidStatsResult = StatsResult(5L, 6L, 7L, 8L)
    private val mockNotMyUidStatsResult = StatsResult(9L, 10L, 11L, 12L)
    private val unsupportedStatsResult =
            StatsResult(UNSUPPORTED.toLong(), UNSUPPORTED.toLong(),
                    UNSUPPORTED.toLong(), UNSUPPORTED.toLong())

    @Before
    fun setUp() {
        TrafficStats.setServiceForTest(binder)
        doReturn(mockSystemUidStatsResult).`when`(binder).getUidStats(SYSTEM_UID)
        doReturn(mockMyUidStatsResult).`when`(binder).getUidStats(myUid)
        doReturn(mockNotMyUidStatsResult).`when`(binder).getUidStats(notMyUid)
    }

    @After
    fun tearDown() {
        TrafficStats.setServiceForTest(null)
        TrafficStats.setMyUidForTest(UID_ALL)
    }

    private fun assertUidStats(uid: Int, stats: StatsResult) {
        assertEquals(stats.rxBytes, TrafficStats.getUidRxBytes(uid))
        assertEquals(stats.rxPackets, TrafficStats.getUidRxPackets(uid))
        assertEquals(stats.txBytes, TrafficStats.getUidTxBytes(uid))
        assertEquals(stats.txPackets, TrafficStats.getUidTxPackets(uid))
    }

    // Verify a normal caller could get a quick UNSUPPORTED result in the TrafficStats
    // without accessing the service if query stats other than itself.
    @Test
    fun testGetUidStats_appCaller() {
        assertUidStats(SYSTEM_UID, unsupportedStatsResult)
        assertUidStats(notMyUid, unsupportedStatsResult)
        verify(binder, never()).getUidStats(anyInt())
        assertUidStats(myUid, mockMyUidStatsResult)
    }

    // Verify that callers with SYSTEM_UID can access network
    // stats for other UIDs. While this behavior is not officially documented
    // in the API, it exists for compatibility with existing callers that may
    // rely on it.
    @Test
    fun testGetUidStats_systemCaller() {
        TrafficStats.setMyUidForTest(SYSTEM_UID)
        assertUidStats(SYSTEM_UID, mockSystemUidStatsResult)
        assertUidStats(myUid, mockMyUidStatsResult)
        assertUidStats(notMyUid, mockNotMyUidStatsResult)
    }
}
