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

package com.android.server.ethernet

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkProvider
import android.os.Build
import android.os.Handler
import android.os.test.TestLooper
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

private const val IFACE = "eth0"
private val CAPS = NetworkCapabilities.Builder().build()

@SmallTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class EthernetInterfaceStateMachineTest {
    private val looper = TestLooper()
    private lateinit var ifaceState: EthernetInterfaceStateMachine

    @Mock private lateinit var context: Context
    @Mock private lateinit var provider: NetworkProvider
    @Mock private lateinit var deps: EthernetNetworkFactory.Dependencies

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val handler = Handler(looper.looper)
        ifaceState = EthernetInterfaceStateMachine(IFACE, handler, context, CAPS, provider, deps)
    }

    // TODO: actually test something.
    @Test
    fun doNothing() {
        assertTrue(true)
    }
}
