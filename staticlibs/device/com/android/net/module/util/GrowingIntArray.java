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

import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.StringJoiner;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * A growing array of primitive ints.
 *
 * <p>This is similar to ArrayList&lt;Integer&gt;, but avoids the cost of boxing (each Integer costs
 * 16 bytes) and creation / garbage collection of individual Integer objects.
 *
 * <p>This class does not use any heuristic for growing capacity, so every call to
 * {@link #add(int)} may reallocate the backing array. Callers should use
 * {@link #ensureHasCapacity(int)} to minimize this behavior when they plan to add several values.
 */
public class GrowingIntArray {
    private int[] mValues;
    private int mLength;

    /**
     * Create an empty GrowingIntArray with the given capacity.
     */
    public GrowingIntArray(int initialCapacity) {
        mValues = new int[initialCapacity];
        mLength = 0;
    }

    /**
     * Create a GrowingIntArray with an initial array of values.
     *
     * <p>The array will be used as-is and may be modified, so callers must stop using it after
     * calling this constructor.
     */
    protected GrowingIntArray(int[] initialValues) {
        mValues = initialValues;
        mLength = initialValues.length;
    }

    /**
     * Add a value to the array.
     */
    public void add(int value) {
        ensureHasCapacity(1);
        mValues[mLength] = value;
        mLength++;
    }

    /**
     * Get the current number of values in the array.
     */
    public int length() {
        return mLength;
    }

    /**
     * Get the value at a given index.
     *
     * @throws ArrayIndexOutOfBoundsException if the index is out of bounds.
     */
    public int get(int index) {
        if (index < 0 || index >= mLength) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return mValues[index];
    }

    /**
     * Iterate over all values in the array.
     */
    public void forEach(@NonNull IntConsumer consumer) {
        for (int i = 0; i < mLength; i++) {
            consumer.accept(mValues[i]);
        }
    }

    /**
     * Remove all values matching a predicate.
     *
     * @return true if at least one value was removed.
     */
    public boolean removeValues(@NonNull IntPredicate predicate) {
        int newQueueLength = 0;
        for (int i = 0; i < mLength; i++) {
            final int cb = mValues[i];
            if (!predicate.test(cb)) {
                mValues[newQueueLength] = cb;
                newQueueLength++;
            }
        }
        if (mLength != newQueueLength) {
            mLength = newQueueLength;
            return true;
        }
        return false;
    }

    /**
     * Indicates whether the array contains the given value.
     */
    public boolean contains(int value) {
        for (int i = 0; i < mLength; i++) {
            if (mValues[i] == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove all values from the array.
     */
    public void clear() {
        mLength = 0;
    }

    /**
     * Ensure at least the given number of values can be added to the array without reallocating.
     *
     * @param capacity The minimum number of additional values the array must be able to hold.
     */
    public void ensureHasCapacity(int capacity) {
        if (mValues.length >= mLength + capacity) {
            return;
        }
        mValues = Arrays.copyOf(mValues, mLength + capacity);
    }

    @VisibleForTesting
    int getBackingArrayLength() {
        return mValues.length;
    }

    /**
     * Shrink the array backing this class to the minimum required length.
     */
    public void shrinkToLength() {
        if (mValues.length != mLength) {
            mValues = Arrays.copyOf(mValues, mLength);
        }
    }

    /**
     * Get values as array by shrinking the internal array to length and returning it.
     *
     * <p>This avoids reallocations if the array is already the correct length, but callers should
     * stop using this instance of {@link GrowingIntArray} if they use the array returned by this
     * method.
     */
    public int[] getMinimizedBackingArray() {
        shrinkToLength();
        return mValues;
    }

    /**
     * Get the String representation of an item in the array, for use by {@link #toString()}.
     */
    protected String valueToString(int item) {
        return String.valueOf(item);
    }

    @NonNull
    @Override
    public String toString() {
        final StringJoiner joiner = new StringJoiner(",", "[", "]");
        forEach(item -> joiner.add(valueToString(item)));
        return joiner.toString();
    }
}
