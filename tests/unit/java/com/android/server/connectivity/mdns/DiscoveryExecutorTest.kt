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

package com.android.server.connectivity.mdns

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.testing.TestableLooper
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val DEFAULT_TIMEOUT = 2000L

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class DiscoveryExecutorTest {
    private val thread = HandlerThread(DiscoveryExecutorTest::class.simpleName).apply { start() }
    private val handler by lazy { Handler(thread.looper) }
    private val testableLooper by lazy { TestableLooper(thread.looper) }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testCheckAndRunOnHandlerThread() {
        val executor = DiscoveryExecutor(
                testableLooper.looper,
                MdnsFeatureFlags.newBuilder().build()
        )
        try {
            val future = CompletableFuture<Boolean>()
            executor.checkAndRunOnHandlerThread { future.complete(true) }
            testableLooper.processAllMessages()
            assertTrue(future.get(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS))
        } finally {
            testableLooper.destroy()
        }

        // Create a DiscoveryExecutor with the null defaultLooper and verify the task can execute
        // normally.
        val executor2 = DiscoveryExecutor(
                null /* defaultLooper */,
                MdnsFeatureFlags.newBuilder().build()
        )
        val future2 = CompletableFuture<Boolean>()
        executor2.checkAndRunOnHandlerThread { future2.complete(true) }
        assertTrue(future2.get(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS))
        executor2.shutDown()
    }

    private fun verifyExecute(executor: DiscoveryExecutor) {
        try {
            val future = CompletableFuture<Boolean>()
            executor.execute { future.complete(true) }
            assertFalse(future.isDone)
            testableLooper.processAllMessages()
            assertTrue(future.get(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS))
        } finally {
            testableLooper.destroy()
        }
    }

    @Test
    fun testExecute() {
        verifyExecute(DiscoveryExecutor(
                testableLooper.looper,
                MdnsFeatureFlags.newBuilder().build()
        ))
    }

    @Test
    fun testExecute_RealtimeScheduler() {
        verifyExecute(DiscoveryExecutor(
                testableLooper.looper,
                MdnsFeatureFlags.newBuilder().setIsAccurateDelayCallbackEnabled(true).build()
        ))
    }

    @Test
    fun testExecuteDelayed() {
        val executor = DiscoveryExecutor(
                testableLooper.looper,
                MdnsFeatureFlags.newBuilder().build()
        )
        try {
            // Verify the executeDelayed method
            val future = CompletableFuture<Boolean>()
            // Schedule a task with 999 ms delay
            executor.executeDelayed({ future.complete(true) }, 999L)
            testableLooper.processAllMessages()
            assertFalse(future.isDone)

            // 500 ms have elapsed but do not exceed the target time (999 ms)
            // The function should not be executed.
            testableLooper.moveTimeForward(500L)
            testableLooper.processAllMessages()
            assertFalse(future.isDone)

            // 500 ms have elapsed again and have exceeded the target time (999 ms).
            // The function should be executed.
            testableLooper.moveTimeForward(500L)
            testableLooper.processAllMessages()
            assertTrue(future.get(500L, TimeUnit.MILLISECONDS))
        } finally {
            testableLooper.destroy()
        }
    }

    @Test
    fun testExecuteDelayed_RealtimeScheduler() {
        val executor = DiscoveryExecutor(
                thread.looper,
                MdnsFeatureFlags.newBuilder().setIsAccurateDelayCallbackEnabled(true).build()
        )
        try {
            // Verify the executeDelayed method
            val future = CompletableFuture<Boolean>()
            // Schedule a task with 50ms delay
            executor.executeDelayed({ future.complete(true) }, 50L)
            assertTrue(future.get(500L, TimeUnit.MILLISECONDS))
        } finally {
            testableLooper.destroy()
        }
    }
}
