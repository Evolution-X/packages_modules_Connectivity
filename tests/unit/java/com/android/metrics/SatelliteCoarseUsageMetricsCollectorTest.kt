/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.metrics

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.android.metrics.SatelliteCoarseUsageMetricsCollector.MyStatsEntry
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import kotlin.test.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * Unit tests for [SatelliteCoarseUsageMetricsCollector].
 * These tests mock Android system services and the custom Dependencies class
 * to verify the behavior of the collector during satellite network events,
 * using MockK for mocking and argument capturing.
 */
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class SatelliteCoarseUsageMetricsCollectorTest {
    companion object {
        const val THREAD_BLOCK_TIMEOUT_MS = 1000L
    }

    private val mockCm = mock(ConnectivityManager::class.java)
    private val mockNsm = mock(NetworkStatsManager::class.java)
    private val mockCtx = mock(Context::class.java).also {
        doReturn(mockCm).`when`(it).getSystemService(ConnectivityManager::class.java)
        doReturn(mockNsm).`when`(it).getSystemService(NetworkStatsManager::class.java)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val mockDeps = mock(SatelliteCoarseUsageMetricsCollector.Dependencies::class.java)
            .also {
                doReturn(handler).`when`(it).backgroundThreadHandler
            }
    private val collector = SatelliteCoarseUsageMetricsCollector(mockCtx, mockDeps)

    // Return the network callback for listening network changes.
    private fun startMonitoring(): ConnectivityManager.NetworkCallback {
        val cbCaptor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        collector.startMonitoring()
        handler.waitForIdle(THREAD_BLOCK_TIMEOUT_MS)
        verify(mockCm).registerNetworkCallback(any(), cbCaptor.capture(), any())
        return cbCaptor.value.also {
            assertNotNull(it)
        }
    }

    private fun mockGetSummary(rxBytes: Long, txBytes: Long) {
        val statsEntry = MyStatsEntry(rxBytes, txBytes)
        doReturn(statsEntry).`when`(mockDeps).getSummary(any(), anyLong())
    }

    private fun assertReportedUsage(rxBytes: Long, txBytes: Long) {
        val reportedUsageCaptor = ArgumentCaptor.forClass(MyStatsEntry::class.java)
        verify(mockDeps).reportUsage(reportedUsageCaptor.capture())

        val reportedUsage = reportedUsageCaptor.value
        assertEquals(rxBytes, reportedUsage.rxBytes)
        assertEquals(txBytes, reportedUsage.txBytes)
    }

    @Test
    fun testSingleNetwork() {
        val mockNet = mock(Network::class.java)
        mockGetSummary(100, 50)
        val cb = startMonitoring()
        mockGetSummary(300, 150)
        cb.onLost(mockNet)
        assertReportedUsage(200, 100)
    }

    // Test multiple satellite networks. This could happen when telephony creates
    // satellite networks with different types of services (e.g., MMS, internet).
    @Test
    fun testMultipleNetworks() {
        // Mock multiple networks, ensuring they are identified as distinct.
        val mockNet1 = Network(1)
        val mockNet2 = Network(2)

        // Simulate baseline.
        mockGetSummary(100, 50)
        val cb = startMonitoring()

        // Simulate onAvailable events, verify nothing happens.
        cb.onAvailable(mockNet1)
        cb.onAvailable(mockNet2)
        verify(mockDeps, never()).reportUsage(any())

        // Simulate first network lost (not the last), simulate no change at all,
        // verify reportUsage is invoked with zero bytes.
        cb.onLost(mockNet1)
        assertReportedUsage(0L, 0L)

        // Simulate second network lost (the last one).
        clearInvocations(mockDeps)
        mockGetSummary(300, 150)
        cb.onLost(mockNet2)
        assertReportedUsage(200L, 100L)
    }

    @Test
    fun testMyStatsEntryPlusSubtract() {
        val entry1 = MyStatsEntry(10, 20)
        val entry2 = MyStatsEntry(5, 10)
        val result = entry1.plus(entry2)
        assertEquals(15L, result.rxBytes)
        assertEquals(30L, result.txBytes)
        entry1.subtract(entry2)
        assertEquals(5L, entry1.rxBytes)
        assertEquals(10L, entry1.txBytes)
    }

    @Test
    fun testMyStatsEntryMinusAdd() {
        val entry1 = MyStatsEntry(100, 200)
        val entry2 = MyStatsEntry(10, 20)
        val result = entry1.minus(entry2)
        assertEquals(90L, result.rxBytes)
        assertEquals(180L, result.txBytes)
        entry1.add(entry2)
        assertEquals(110L, entry1.rxBytes)
        assertEquals(220L, entry1.txBytes)
    }
}
