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
import android.os.Looper
import android.os.Message
import androidx.test.filters.SmallTest
import com.android.net.module.util.TimerFileDescriptor.ITask
import com.android.net.module.util.TimerFileDescriptor.MessageTask
import com.android.net.module.util.TimerFileDescriptor.RunnableTask
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

private const val MSG_TEST = 1

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class TimerFileDescriptorTest {
    private class TestHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            val cv = msg.obj as ConditionVariable
            cv.open()
        }
    }
    private val thread = HandlerThread(TimerFileDescriptorTest::class.simpleName).apply { start() }
    private val handler by lazy { TestHandler(thread.looper) }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    private fun assertDelayedTaskPost(
            timerFd: TimerFileDescriptor,
            task: ITask,
            cv: ConditionVariable
    ) {
        val delayTime = 10L
        val startTime1 = Instant.now()
        handler.post { timerFd.setDelayedTask(task, delayTime) }
        assertTrue(cv.block(100L /* timeoutMs*/))
        assertTrue(Duration.between(startTime1, Instant.now()).toMillis() >= delayTime)
    }

    @Test
    fun testSetDelayedTask() {
        val timerFd = TimerFileDescriptor(handler)
        tryTest {
            // Verify the delayed task is executed with the self-implemented ITask
            val cv1 = ConditionVariable()
            assertDelayedTaskPost(timerFd, { cv1.open() }, cv1)

            // Verify the delayed task is executed with the RunnableTask
            val cv2 = ConditionVariable()
            assertDelayedTaskPost(timerFd, RunnableTask{ cv2.open() }, cv2)

            // Verify the delayed task is executed with the MessageTask
            val cv3 = ConditionVariable()
            assertDelayedTaskPost(timerFd, MessageTask(handler.obtainMessage(MSG_TEST, cv3)), cv3)
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
