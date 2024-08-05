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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class GrowingIntArrayTest {
    @Test
    fun testAddAndGet() {
        val array = GrowingIntArray(1)
        array.add(-1)
        array.add(0)
        array.add(2)

        assertEquals(-1, array.get(0))
        assertEquals(0, array.get(1))
        assertEquals(2, array.get(2))
        assertEquals(3, array.length())
    }

    @Test
    fun testForEach() {
        val array = GrowingIntArray(10)
        array.add(-1)
        array.add(0)
        array.add(2)

        val actual = mutableListOf<Int>()
        array.forEach { actual.add(it) }

        val expected = listOf(-1, 0, 2)
        assertEquals(expected, actual)
    }

    @Test
    fun testForEach_EmptyArray() {
        val array = GrowingIntArray(10)
        array.forEach {
            fail("This should not be called")
        }
    }

    @Test
    fun testRemoveValues() {
        val array = GrowingIntArray(10)
        array.add(-1)
        array.add(0)
        array.add(2)

        array.removeValues { it <= 0 }
        assertEquals(1, array.length())
        assertEquals(2, array.get(0))
    }

    @Test
    fun testContains() {
        val array = GrowingIntArray(10)
        array.add(-1)
        array.add(2)

        assertTrue(array.contains(-1))
        assertTrue(array.contains(2))

        assertFalse(array.contains(0))
        assertFalse(array.contains(3))
    }

    @Test
    fun testClear() {
        val array = GrowingIntArray(10)
        array.add(-1)
        array.add(2)
        array.clear()

        assertEquals(0, array.length())
    }

    @Test
    fun testEnsureHasCapacity() {
        val array = GrowingIntArray(0)
        array.add(42)
        array.ensureHasCapacity(2)

        assertEquals(3, array.backingArrayLength)
    }

    @Test
    fun testGetMinimizedBackingArray() {
        val array = GrowingIntArray(10)
        array.add(-1)
        array.add(2)

        assertContentEquals(intArrayOf(-1, 2), array.minimizedBackingArray)
    }

    @Test
    fun testToString() {
        assertEquals("[]", GrowingIntArray(10).toString())
        assertEquals("[1,2,3]", GrowingIntArray(3).apply {
            add(1)
            add(2)
            add(3)
        }.toString())
    }
}
