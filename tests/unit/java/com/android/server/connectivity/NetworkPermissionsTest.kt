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

package com.android.server.connectivity

import android.net.INetd
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class NetworkPermissionsTest {
    @Test
    fun test_networkTrafficPerms_correctValues() {
        assertEquals(NetworkPermissions.PERMISSION_NONE, INetd.PERMISSION_NONE) /* 0 */
        assertEquals(NetworkPermissions.PERMISSION_NETWORK, INetd.PERMISSION_NETWORK) /* 1 */
        assertEquals(NetworkPermissions.PERMISSION_SYSTEM, INetd.PERMISSION_SYSTEM) /* 2 */
        assertEquals(NetworkPermissions.TRAFFIC_PERMISSION_INTERNET, 4)
        assertEquals(NetworkPermissions.TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS, 8)
        assertEquals(NetworkPermissions.TRAFFIC_PERMISSION_UNINSTALLED, -1)
        assertEquals(NetworkPermissions.TRAFFIC_PERMISSION_SDKSANDBOX_LOCALHOST, 16)
    }

    @Test
    fun test_noOverridesInFlags() {
        val permsList = listOf(
            NetworkPermissions.PERMISSION_NONE,
            NetworkPermissions.PERMISSION_NETWORK,
            NetworkPermissions.PERMISSION_SYSTEM,
            NetworkPermissions.TRAFFIC_PERMISSION_INTERNET,
            NetworkPermissions.TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS,
            NetworkPermissions.TRAFFIC_PERMISSION_SDKSANDBOX_LOCALHOST,
            NetworkPermissions.TRAFFIC_PERMISSION_UNINSTALLED
        )
        assertFalse(hasDuplicates(permsList))
    }

    fun hasDuplicates(list: List<Int>): Boolean {
        return list.distinct().size != list.size
    }
}
