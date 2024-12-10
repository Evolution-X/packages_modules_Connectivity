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

package com.android.net.module.util

import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.function.LongSupplier

@RunWith(DevSdkIgnoreRunner::class)
class LruCacheWithExpiryTest {

    companion object {
        private const val CACHE_SIZE = 2
        private const val EXPIRY_DURATION_MS = 1000L
    }

    private val timeSupplier = object : LongSupplier {
        private var currentTimeMillis = 0L
        override fun getAsLong(): Long = currentTimeMillis
        fun advanceTime(millis: Long) { currentTimeMillis += millis }
    }

    private val cache = LruCacheWithExpiry<Int, String>(
            timeSupplier, EXPIRY_DURATION_MS, CACHE_SIZE) { true }

    @Test
    fun testPutIfAbsent_keyNotPresent() {
        val value = cache.putIfAbsent(1, "value1")
        assertNull(value)
        assertEquals("value1", cache.get(1))
    }

    @Test
    fun testPutIfAbsent_keyPresent() {
        cache.put(1, "value1")
        val value = cache.putIfAbsent(1, "value2")
        assertEquals("value1", value)
        assertEquals("value1", cache.get(1))
    }

    @Test
    fun testPutIfAbsent_keyPresentButExpired() {
        cache.put(1, "value1")
        // Advance time to expire the entry
        timeSupplier.advanceTime(EXPIRY_DURATION_MS + 1)
        val value = cache.putIfAbsent(1, "value2")
        assertNull(value)
        assertEquals("value2", cache.get(1))
    }

    @Test
    fun testPutIfAbsent_maxSizeReached() {
        cache.put(1, "value1")
        cache.put(2, "value2")
        cache.putIfAbsent(3, "value3") // This should evict the least recently used entry (1)
        assertNull(cache.get(1))
        assertEquals("value2", cache.get(2))
        assertEquals("value3", cache.get(3))
    }
}
