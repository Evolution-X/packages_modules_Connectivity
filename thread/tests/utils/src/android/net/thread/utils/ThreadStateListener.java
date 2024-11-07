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

package android.net.thread.utils;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.annotation.Nullable;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.StateCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.ArrayTrackRecord;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A listener for sequential Thread state updates.
 *
 * <p>This is a wrapper around {@link ThreadNetworkController#registerStateCallback} to make
 * synchronized access to Thread state updates easier.
 */
@VisibleForTesting
public final class ThreadStateListener {
    private static final List<ThreadStateListener> sListeners = new ArrayList<>();
    private final ArrayTrackRecord<Integer> mDeviceRoleUpdates = new ArrayTrackRecord<>();
    private final ArrayTrackRecord<Integer>.ReadHead mReadHead = mDeviceRoleUpdates.newReadHead();
    private final ThreadNetworkController mController;
    private final StateCallback mCallback =
            new ThreadNetworkController.StateCallback() {
                @Override
                public void onDeviceRoleChanged(int newRole) {
                    mDeviceRoleUpdates.add(newRole);
                }
                // Add more state update trackers here
            };

    /** Creates a new {@link ThreadStateListener} object and starts listening for state updates. */
    public static ThreadStateListener startListener(ThreadNetworkController controller) {
        var listener = new ThreadStateListener(controller);
        sListeners.add(listener);
        listener.start();
        return listener;
    }

    /** Stops all listeners created by {@link #startListener}. */
    public static void stopAllListeners() {
        for (var listener : sListeners) {
            listener.stop();
        }
        sListeners.clear();
    }

    private ThreadStateListener(ThreadNetworkController controller) {
        mController = controller;
    }

    private void start() {
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> mController.registerStateCallback(directExecutor(), mCallback));
    }

    private void stop() {
        runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(mCallback));
    }

    /**
     * Polls for any role in {@code roles} starting after call to {@link #startListener}.
     *
     * <p>Returns the matched device role or {@code null} if timeout.
     */
    @Nullable
    public Integer pollForAnyRoleOf(List<Integer> roles, Duration timeout) {
        return mReadHead.poll(timeout.toMillis(), newRole -> (roles.contains(newRole)));
    }
}
