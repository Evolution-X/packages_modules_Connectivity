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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.testutils.tryTest
import kotlin.test.assertContentEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class TerribleErrorLogTest {
    @Test
    fun testLogTerribleError() {
        val wtfCaptures = mutableListOf<String>()
        val prevHandler = Log.setWtfHandler { tag, what, system ->
            wtfCaptures.add("$tag,${what.message}")
        }
        val statsLogCapture = mutableListOf<Pair<Int, Int>>()
        val testStatsLog = object {
            fun write(protoType: Int, errorType: Int) {
                statsLogCapture.add(protoType to errorType)
            }
        }
        tryTest {
            TerribleErrorLog.logTerribleError(testStatsLog::write, "error", 1, 2)
            assertContentEquals(listOf(1 to 2), statsLogCapture)
            assertContentEquals(listOf("TerribleErrorLog,error"), wtfCaptures)
        } cleanup {
            Log.setWtfHandler(prevHandler)
        }
    }
}
