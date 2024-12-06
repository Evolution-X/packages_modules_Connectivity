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

import android.os.Build
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.tryTest
import com.android.testutils.visibleOnHandlerThread
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class TimerFileDescriptorTest {
    private val thread = HandlerThread(TimerFileDescriptorTest::class.simpleName).apply { start() }
    private val handler by lazy { Handler(thread.looper) }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testSetDelayedTask() {
        val delayTime = 10L
        val timerFd = TimerFileDescriptor(handler)
        val cv = ConditionVariable()
        val startTime = Instant.now()
        tryTest {
            handler.post { timerFd.setDelayedTask({ cv.open() }, delayTime) }
            assertTrue(cv.block(100L /* timeoutMs*/))
            // Verify that the delay time has actually passed.
            val duration = Duration.between(startTime, Instant.now())
            assertTrue(duration.toMillis() >= delayTime)
        } cleanup {
            visibleOnHandlerThread(handler) { timerFd.close() }
        }
    }

    @Test
    fun testCancelTask() {
        // The task is posted and canceled within the same handler loop, so the short delay used
        // here won't cause flakes.
        val delayTime = 10L
        val timerFd = TimerFileDescriptor(handler)
        val cv = ConditionVariable()
        tryTest {
            handler.post {
                timerFd.setDelayedTask({ cv.open() }, delayTime)
                assertTrue(timerFd.hasDelayedTask())
                timerFd.cancelTask()
                assertFalse(timerFd.hasDelayedTask())
            }
            assertFalse(cv.block(20L /* timeoutMs*/))
        } cleanup {
            visibleOnHandlerThread(handler) { timerFd.close() }
        }
    }
}
