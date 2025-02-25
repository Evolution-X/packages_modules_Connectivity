/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.testutils

private const val POLLING_INTERVAL_MS: Int = 100

/** Calls condition() until it returns true or timeout occurs. */
fun pollingCheck(timeout_ms: Int, condition: () -> Boolean): Boolean {
    var polling_time = 0
    do {
        Thread.sleep(POLLING_INTERVAL_MS.toLong())
        polling_time += POLLING_INTERVAL_MS
        if (condition()) return true
    } while (polling_time < timeout_ms)
    return false
}
