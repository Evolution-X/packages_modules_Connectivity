/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.testutils

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.INetworkStatsService
import android.net.INetworkStatsSession
import android.net.NetworkStats
import android.net.NetworkTemplate
import android.net.NetworkTemplate.MATCH_MOBILE
import android.text.TextUtils
import com.android.modules.utils.build.SdkLevel
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito

@JvmOverloads
fun orderInsensitiveEquals(
    leftStats: NetworkStats,
    rightStats: NetworkStats,
    compareTime: Boolean = false
): Boolean {
    if (leftStats == rightStats) return true
    if (compareTime && leftStats.elapsedRealtime != rightStats.elapsedRealtime) {
        return false
    }

    // While operations such as add/subtract will preserve empty entries. This will make
    // the result be hard to verify during test. Remove them before comparing since they
    // are not really affect correctness.
    // TODO (b/152827872): Remove empty entries after addition/subtraction.
    val leftTrimmedEmpty = leftStats.removeEmptyEntries()
    val rightTrimmedEmpty = rightStats.removeEmptyEntries()

    if (leftTrimmedEmpty.size() != rightTrimmedEmpty.size()) return false
    val left = NetworkStats.Entry()
    val right = NetworkStats.Entry()
    // Order insensitive compare.
    for (i in 0 until leftTrimmedEmpty.size()) {
        leftTrimmedEmpty.getValues(i, left)
        val j: Int = rightTrimmedEmpty.findIndexHinted(left.iface, left.uid, left.set, left.tag,
                left.metered, left.roaming, left.defaultNetwork, i)
        if (j == -1) return false
        rightTrimmedEmpty.getValues(j, right)
        if (SdkLevel.isAtLeastT()) {
            if (left != right) return false
        } else {
            if (!checkEntryEquals(left, right)) return false
        }
    }
    return true
}

/**
 * Assert that the two {@link NetworkStats.Entry} are equals.
 */
fun assertEntryEquals(left: NetworkStats.Entry, right: NetworkStats.Entry) {
    assertTrue(checkEntryEquals(left, right))
}

// TODO: Make all callers use NetworkStats.Entry#equals once S- downstreams
//  are no longer supported. Because NetworkStats is mainlined on T+ and
//  NetworkStats.Entry#equals in S- does not support null iface.
fun checkEntryEquals(left: NetworkStats.Entry, right: NetworkStats.Entry): Boolean {
    return TextUtils.equals(left.iface, right.iface) &&
            left.uid == right.uid &&
            left.set == right.set &&
            left.tag == right.tag &&
            left.metered == right.metered &&
            left.roaming == right.roaming &&
            left.defaultNetwork == right.defaultNetwork &&
            left.rxBytes == right.rxBytes &&
            left.rxPackets == right.rxPackets &&
            left.txBytes == right.txBytes &&
            left.txPackets == right.txPackets &&
            left.operations == right.operations
}

/**
 * Assert that two {@link NetworkStats} are equals, assuming the order of the records are not
 * necessarily the same.
 *
 * @note {@code elapsedRealtime} is not compared by default, given that in test cases that is not
 *       usually used.
 */
@JvmOverloads
fun assertNetworkStatsEquals(
    expected: NetworkStats,
    actual: NetworkStats,
    compareTime: Boolean = false
) {
    assertTrue(orderInsensitiveEquals(expected, actual, compareTime),
            "expected: $expected but was: $actual")
}

/**
 * Assert that after being parceled then unparceled, {@link NetworkStats} is equal to the original
 * object.
 */
fun assertParcelingIsLossless(stats: NetworkStats) {
    assertParcelingIsLossless(stats) { a, b -> orderInsensitiveEquals(a, b) }
}

/**
 * Make a {@link android.app.usage.NetworkStats} instance from
 * a {@link android.net.NetworkStats} instance.
 */
// It's not possible to directly create a mocked `NetworkStats` instance
// because of limitations with `NetworkStats#getNextBucket`.
// As a workaround for testing, create a mock by controlling the return values
// from the mocked service that provides the `NetworkStats` data.
// Notes:
//   1. The order of records in the final `NetworkStats` object might change or
//      some records might be merged if there are items with duplicate keys.
//   2. The interface and operations fields will be empty since there is
//      no such field in the {@link android.app.usage.NetworkStats}.
fun makePublicStatsFromAndroidNetStats(androidNetStats: NetworkStats):
        android.app.usage.NetworkStats {
    val mockService = Mockito.mock(INetworkStatsService::class.java)
    val manager = NetworkStatsManager(Mockito.mock(Context::class.java), mockService)
    val mockStatsSession = Mockito.mock(INetworkStatsSession::class.java)

    Mockito.doReturn(mockStatsSession).`when`(mockService)
            .openSessionForUsageStats(anyInt(), any())
    Mockito.doReturn(androidNetStats).`when`(mockStatsSession).getSummaryForAllUid(
            any(NetworkTemplate::class.java), anyLong(), anyLong(), anyBoolean())
    return manager.querySummary(
            NetworkTemplate.Builder(MATCH_MOBILE).build(),
            Long.MIN_VALUE, Long.MAX_VALUE
    )
}
