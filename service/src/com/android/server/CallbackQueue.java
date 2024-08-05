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

package com.android.server;

import android.annotation.NonNull;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;

import com.android.net.module.util.GrowingIntArray;

/**
 * A utility class to add/remove {@link NetworkCallback}s from a queue.
 *
 * <p>This is intended to be used as a temporary builder to create/modify callbacks stored in an int
 * array for memory efficiency.
 *
 * <p>Intended usage:
 * <pre>
 *     final CallbackQueue queue = new CallbackQueue(storedCallbacks);
 *     queue.forEach(netId, callbackId -> { [...] });
 *     queue.addCallback(netId, callbackId);
 *     [...]
 *     storedCallbacks = queue.getMinimizedBackingArray();
 * </pre>
 *
 * <p>This class is not thread-safe.
 */
public class CallbackQueue extends GrowingIntArray {
    public CallbackQueue(int[] initialCallbacks) {
        super(initialCallbacks);
    }

    /**
     * Get a callback int from netId and callbackId.
     *
     * <p>The first 16 bits of each int is the netId; the last 16 bits are the callback index.
     */
    private static int getCallbackInt(int netId, int callbackId) {
        return (netId << 16) | (callbackId & 0xffff);
    }

    private static int getNetId(int callbackInt) {
        return callbackInt >>> 16;
    }

    private static int getCallbackId(int callbackInt) {
        return callbackInt & 0xffff;
    }

    /**
     * A consumer interface for {@link #forEach(CallbackConsumer)}.
     *
     * <p>This is similar to a BiConsumer&lt;Integer, Integer&gt;, but avoids the boxing cost.
     */
    public interface CallbackConsumer {
        /**
         * Method called on each callback in the queue.
         */
        void accept(int netId, int callbackId);
    }

    /**
     * Iterate over all callbacks in the queue.
     */
    public void forEach(@NonNull CallbackConsumer consumer) {
        forEach(value -> {
            final int netId = getNetId(value);
            final int callbackId = getCallbackId(value);
            consumer.accept(netId, callbackId);
        });
    }

    /**
     * Indicates whether the queue contains a callback for the given (netId, callbackId).
     */
    public boolean hasCallback(int netId, int callbackId) {
        return contains(getCallbackInt(netId, callbackId));
    }

    /**
     * Remove all callbacks for the given netId.
     *
     * @return true if at least one callback was removed.
     */
    public boolean removeCallbacksForNetId(int netId) {
        return removeValues(cb -> getNetId(cb) == netId);
    }

    /**
     * Remove all callbacks for the given netId and callbackId.
     * @return true if at least one callback was removed.
     */
    public boolean removeCallbacks(int netId, int callbackId) {
        final int cbInt = getCallbackInt(netId, callbackId);
        return removeValues(cb -> cb == cbInt);
    }

    /**
     * Add a callback at the end of the queue.
     */
    public void addCallback(int netId, int callbackId) {
        add(getCallbackInt(netId, callbackId));
    }

    @Override
    protected String valueToString(int item) {
        final int callbackId = getCallbackId(item);
        final int netId = getNetId(item);
        return ConnectivityManager.getCallbackName(callbackId) + "(" + netId + ")";
    }
}
