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

package com.android.net.module.util;

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.util.CloseGuard;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * Represents a Timer file descriptor object used for scheduling tasks with precise delays.
 * Compared to {@link Handler#postDelayed}, this class offers enhanced accuracy for delayed
 * callbacks by accounting for periods when the device is in deep sleep.
 *
 *  <p> This class is designed for use exclusively from the handler thread.
 *
 * **Usage Examples:**
 *
 * ** Scheduling recurring tasks with the same TimerFileDescriptor **
 *
 * ```java
 * // Create a TimerFileDescriptor
 * final TimerFileDescriptor timerFd = new TimerFileDescriptor(handler);
 *
 * // Schedule a new task with a delay.
 * timerFd.setDelayedTask(() -> taskToExecute(), delayTime);
 *
 * // Once the delay has elapsed, and the task is running, schedule another task.
 * timerFd.setDelayedTask(() -> anotherTaskToExecute(), anotherDelayTime);
 *
 * // Remember to close the TimerFileDescriptor after all tasks have finished running.
 * timerFd.close();
 * ```
 */
public class TimerFileDescriptor {
    private static final String TAG = TimerFileDescriptor.class.getSimpleName();
    // EVENT_ERROR may be generated even if not specified, as per its javadoc.
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private final CloseGuard mGuard = new CloseGuard();
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MessageQueue mQueue;
    @NonNull
    private final ParcelFileDescriptor mParcelFileDescriptor;
    private final int mFdInt;
    @Nullable
    private ITask mTask;

    /**
     * An interface for defining tasks that can be executed using a {@link Handler}.
     */
    public interface ITask {
        /**
         * Executes the task using the provided {@link Handler}.
         *
         * @param handler The {@link Handler} to use for executing the task.
         */
        void post(Handler handler);
    }

    /**
     * A task that sends a {@link Message} using a {@link Handler}.
     */
    public static class MessageTask implements ITask {
        private final Message mMessage;

        public MessageTask(Message message) {
            mMessage = message;
        }

        /**
         * Sends the {@link Message} using the provided {@link Handler}.
         *
         * @param handler The {@link Handler} to use for sending the message.
         */
        @Override
        public void post(Handler handler) {
            handler.sendMessage(mMessage);
        }
    }

    /**
     * A task that posts a {@link Runnable} to a {@link Handler}.
     */
    public static class RunnableTask implements ITask {
        private final Runnable mRunnable;

        public RunnableTask(Runnable runnable) {
            mRunnable = runnable;
        }

        /**
         * Posts the {@link Runnable} to the provided {@link Handler}.
         *
         * @param handler The {@link Handler} to use for posting the runnable.
         */
        @Override
        public void post(Handler handler) {
            handler.post(mRunnable);
        }
    }

    /**
     * TimerFileDescriptor constructor
     *
     * Note: The constructor is currently safe to call on another thread because it only sets final
     * members and registers the event to be called on the handler.
     */
    public TimerFileDescriptor(@NonNull Handler handler) {
        mFdInt = TimerFdUtils.createTimerFileDescriptor();
        mParcelFileDescriptor = ParcelFileDescriptor.adoptFd(mFdInt);
        mHandler = handler;
        mQueue = handler.getLooper().getQueue();
        registerFdEventListener();

        mGuard.open("close");
    }

    /**
     * Set a task to be executed after a specified delay.
     *
     * <p> A task can only be scheduled once at a time. Cancel previous scheduled task before the
     *     new task is scheduled.
     *
     * @param task the task to be executed
     * @param delayMs the delay time in milliseconds
     * @throws IllegalArgumentException if try to replace the current scheduled task
     * @throws IllegalArgumentException if the delay time is less than 0
     */
    public void setDelayedTask(@NonNull ITask task, long delayMs) {
        ensureRunningOnCorrectThread();
        if (mTask != null) {
            throw new IllegalArgumentException("task is already scheduled");
        }
        if (delayMs <= 0L) {
            task.post(mHandler);
            return;
        }

        if (TimerFdUtils.setExpirationTime(mFdInt, delayMs)) {
            mTask = task;
        }
    }

    /**
     * Cancel the scheduled task.
     */
    public void cancelTask() {
        ensureRunningOnCorrectThread();
        if (mTask == null) return;

        TimerFdUtils.setExpirationTime(mFdInt, 0 /* delayMs */);
        mTask = null;
    }

    /**
     * Check if there is a scheduled task.
     */
    public boolean hasDelayedTask() {
        ensureRunningOnCorrectThread();
        return mTask != null;
    }

    /**
     * Close the TimerFileDescriptor. This implementation closes the underlying
     * OS resources allocated to represent this stream.
     */
    public void close() {
        ensureRunningOnCorrectThread();
        unregisterAndDestroyFd();
    }

    private void registerFdEventListener() {
        mQueue.addOnFileDescriptorEventListener(
                mParcelFileDescriptor.getFileDescriptor(),
                FD_EVENTS,
                (fd, events) -> {
                    if (!isRunning()) {
                        return 0;
                    }
                    if ((events & EVENT_INPUT) != 0) {
                        handleExpiration();
                    }
                    return FD_EVENTS;
                });
    }

    private boolean isRunning() {
        return mParcelFileDescriptor.getFileDescriptor().valid();
    }

    private void handleExpiration() {
        // Execute the task
        if (mTask != null) {
            mTask.post(mHandler);
            mTask = null;
        }
    }

    private void unregisterAndDestroyFd() {
        if (mGuard != null) {
            mGuard.close();
        }

        mQueue.removeOnFileDescriptorEventListener(mParcelFileDescriptor.getFileDescriptor());
        try {
            mParcelFileDescriptor.close();
        } catch (IOException exception) {
            Log.e(TAG, "close ParcelFileDescriptor failed. ", exception);
        }
    }

    private void ensureRunningOnCorrectThread() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException(
                    "Not running on Handler thread: " + Thread.currentThread().getName());
        }
    }

    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() throws Throwable {
        if (mGuard != null) {
            mGuard.warnIfOpen();
        }
        super.finalize();
    }
}
