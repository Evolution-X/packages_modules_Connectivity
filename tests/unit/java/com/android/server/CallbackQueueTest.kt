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

package com.android.server

import android.net.ConnectivityManager
import android.net.ConnectivityManager.CALLBACK_AVAILABLE
import android.net.ConnectivityManager.CALLBACK_CAP_CHANGED
import android.net.ConnectivityManager.CALLBACK_IP_CHANGED
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_NETID_1 = 123

// Maximum 16 bits unsigned value
private const val TEST_NETID_2 = 0xffff

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class CallbackQueueTest {
    @Test
    fun testAddCallback() {
        val cbs = listOf(
            TEST_NETID_1 to CALLBACK_AVAILABLE,
            TEST_NETID_2 to CALLBACK_AVAILABLE,
            TEST_NETID_1 to CALLBACK_CAP_CHANGED,
            TEST_NETID_1 to CALLBACK_CAP_CHANGED
        )
        val queue = CallbackQueue(intArrayOf()).apply {
            cbs.forEach { addCallback(it.first, it.second) }
        }

        assertQueueEquals(cbs, queue)
    }

    @Test
    fun testHasCallback() {
        val queue = CallbackQueue(intArrayOf()).apply {
            addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
            addCallback(TEST_NETID_2, CALLBACK_AVAILABLE)
            addCallback(TEST_NETID_1, CALLBACK_CAP_CHANGED)
            addCallback(TEST_NETID_1, CALLBACK_CAP_CHANGED)
        }

        assertTrue(queue.hasCallback(TEST_NETID_1, CALLBACK_AVAILABLE))
        assertTrue(queue.hasCallback(TEST_NETID_2, CALLBACK_AVAILABLE))
        assertTrue(queue.hasCallback(TEST_NETID_1, CALLBACK_CAP_CHANGED))

        assertFalse(queue.hasCallback(TEST_NETID_2, CALLBACK_CAP_CHANGED))
        assertFalse(queue.hasCallback(1234, CALLBACK_AVAILABLE))
        assertFalse(queue.hasCallback(TEST_NETID_1, 5678))
        assertFalse(queue.hasCallback(1234, 5678))
    }

    @Test
    fun testRemoveCallbacks() {
        val queue = CallbackQueue(intArrayOf()).apply {
            assertFalse(removeCallbacks(TEST_NETID_1, CALLBACK_AVAILABLE))
            addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
            addCallback(TEST_NETID_1, CALLBACK_CAP_CHANGED)
            addCallback(TEST_NETID_2, CALLBACK_AVAILABLE)
            addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
            assertTrue(removeCallbacks(TEST_NETID_1, CALLBACK_AVAILABLE))
        }
        assertQueueEquals(listOf(
            TEST_NETID_1 to CALLBACK_CAP_CHANGED,
            TEST_NETID_2 to CALLBACK_AVAILABLE
        ), queue)
    }

    @Test
    fun testRemoveCallbacksForNetId() {
        val queue = CallbackQueue(intArrayOf()).apply {
            assertFalse(removeCallbacksForNetId(TEST_NETID_2))
            addCallback(TEST_NETID_2, CALLBACK_AVAILABLE)
            assertFalse(removeCallbacksForNetId(TEST_NETID_1))
            addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
            addCallback(TEST_NETID_1, CALLBACK_CAP_CHANGED)
            addCallback(TEST_NETID_2, CALLBACK_CAP_CHANGED)
            addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
            addCallback(TEST_NETID_2, CALLBACK_IP_CHANGED)
            assertTrue(removeCallbacksForNetId(TEST_NETID_2))
        }
        assertQueueEquals(listOf(
            TEST_NETID_1 to CALLBACK_AVAILABLE,
            TEST_NETID_1 to CALLBACK_CAP_CHANGED,
            TEST_NETID_1 to CALLBACK_AVAILABLE,
        ), queue)
    }

    @Test
    fun testConstructorFromExistingArray() {
        val queue1 = CallbackQueue(intArrayOf()).apply {
            addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
            addCallback(TEST_NETID_2, CALLBACK_AVAILABLE)
        }
        val queue2 = CallbackQueue(queue1.minimizedBackingArray)
        assertQueueEquals(listOf(
            TEST_NETID_1 to CALLBACK_AVAILABLE,
            TEST_NETID_2 to CALLBACK_AVAILABLE
        ), queue2)
    }

    @Test
    fun testToString() {
        assertEquals("[]", CallbackQueue(intArrayOf()).toString())
        assertEquals(
            "[CALLBACK_AVAILABLE($TEST_NETID_1)]",
            CallbackQueue(intArrayOf()).apply {
                addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
            }.toString()
        )
        assertEquals(
            "[CALLBACK_AVAILABLE($TEST_NETID_1),CALLBACK_CAP_CHANGED($TEST_NETID_2)]",
            CallbackQueue(intArrayOf()).apply {
                addCallback(TEST_NETID_1, CALLBACK_AVAILABLE)
                addCallback(TEST_NETID_2, CALLBACK_CAP_CHANGED)
            }.toString()
        )
    }

    @Test
    fun testMaxNetId() {
        // CallbackQueue assumes netIds are at most 16 bits
        assertTrue(NetIdManager.MAX_NET_ID <= 0xffff)
    }

    @Test
    fun testMaxCallbackId() {
        // CallbackQueue assumes callback IDs are at most 16 bits.
        val constants = ConnectivityManager::class.java.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                    it.name.startsWith("CALLBACK_")
        }
        constants.forEach {
            it.isAccessible = true
            assertTrue(it.get(null) as Int <= 0xffff)
        }
    }
}

private fun assertQueueEquals(expected: List<Pair<Int, Int>>, actual: CallbackQueue) {
    assertEquals(
        expected.size,
        actual.length(),
        "Size mismatch between expected: $expected and actual: $actual"
    )

    var nextIndex = 0
    actual.forEach { netId, cbId ->
        val (expNetId, expCbId) = expected[nextIndex]
        val msg = "$actual does not match $expected at index $nextIndex"
        assertEquals(expNetId, netId, msg)
        assertEquals(expCbId, cbId, msg)
        nextIndex++
    }
    // Ensure forEach iterations and size are consistent
    assertEquals(expected.size, nextIndex)
}
