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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.GuardedBy;

import com.android.net.module.util.HandlerUtils;

import java.util.ArrayList;
import java.util.concurrent.Executor;

/**
 * A utility class to generate a handler, optionally with a looper, and to run functions on the
 * newly created handler.
 */
public class DiscoveryExecutor implements Executor {
    private static final String TAG = DiscoveryExecutor.class.getSimpleName();
    @Nullable
    private final HandlerThread mHandlerThread;

    @GuardedBy("mPendingTasks")
    @Nullable
    private Handler mHandler;
    // Store pending tasks and associated delay time. Each Pair represents a pending task
    // (first) and its delay time (second).
    @GuardedBy("mPendingTasks")
    @NonNull
    private final ArrayList<Pair<Runnable, Long>> mPendingTasks = new ArrayList<>();

    @GuardedBy("mPendingTasks")
    @Nullable
    Scheduler mScheduler;
    @NonNull private final MdnsFeatureFlags mMdnsFeatureFlags;

    DiscoveryExecutor(@Nullable Looper defaultLooper, @NonNull MdnsFeatureFlags mdnsFeatureFlags) {
        mMdnsFeatureFlags = mdnsFeatureFlags;
        if (defaultLooper != null) {
            this.mHandlerThread = null;
            synchronized (mPendingTasks) {
                this.mHandler = new Handler(defaultLooper);
            }
        } else {
            this.mHandlerThread = new HandlerThread(MdnsDiscoveryManager.class.getSimpleName()) {
                @Override
                protected void onLooperPrepared() {
                    synchronized (mPendingTasks) {
                        mHandler = new Handler(getLooper());
                        for (Pair<Runnable, Long> pendingTask : mPendingTasks) {
                            executeDelayed(pendingTask.first, pendingTask.second);
                        }
                        mPendingTasks.clear();
                    }
                }
            };
            this.mHandlerThread.start();
        }
    }

    /**
     * Check if the current thread is the expected thread. If it is, run the given function.
     * Otherwise, execute it using the handler.
     */
    public void checkAndRunOnHandlerThread(@NonNull Runnable function) {
        if (this.mHandlerThread == null) {
            // Callers are expected to already be running on the handler when a defaultLooper
            // was provided
            function.run();
        } else {
            execute(function);
        }
    }

    /** Execute the given function */
    @Override
    public void execute(Runnable function) {
        executeDelayed(function, 0L /* delayMillis */);
    }

    /** Execute the given function after the specified amount of time elapses. */
    public void executeDelayed(Runnable function, long delayMillis) {
        final Handler handler;
        final Scheduler scheduler;
        synchronized (mPendingTasks) {
            if (this.mHandler == null) {
                mPendingTasks.add(Pair.create(function, delayMillis));
                return;
            } else {
                handler = this.mHandler;
                if (mMdnsFeatureFlags.mIsAccurateDelayCallbackEnabled
                        && this.mScheduler == null) {
                    this.mScheduler = SchedulerFactory.createScheduler(mHandler);
                }
                scheduler = this.mScheduler;
            }
        }
        if (scheduler != null) {
            if (delayMillis == 0L) {
                handler.post(function);
                return;
            }
            if (HandlerUtils.isRunningOnHandlerThread(handler)) {
                scheduler.postDelayed(function, delayMillis);
            } else {
                handler.post(() -> scheduler.postDelayed(function, delayMillis));
            }
        } else {
            handler.postDelayed(function, delayMillis);
        }
    }

    /** Shutdown the thread if necessary. */
    public void shutDown() {
        if (this.mHandlerThread != null) {
            this.mHandlerThread.quitSafely();
        }
        synchronized (mPendingTasks) {
            if (mScheduler != null) {
                mScheduler.close();
            }
        }
    }

    /**
     * Ensures that the current running thread is the same as the handler thread.
     */
    public void ensureRunningOnHandlerThread() {
        synchronized (mPendingTasks) {
            HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        }
    }

    /**
     * Runs the specified task synchronously for dump method.
     */
    public void runWithScissorsForDumpIfReady(@NonNull Runnable function) {
        final Handler handler;
        synchronized (mPendingTasks) {
            if (this.mHandler == null) {
                Log.d(TAG, "The handler is not ready. Ignore the DiscoveryManager dump");
                return;
            } else {
                handler = this.mHandler;
            }
        }
        HandlerUtils.runWithScissorsForDump(handler, function, 10_000);
    }
}
