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
// ktlint does not allow annotating function argument literals inline. Disable the specific rule
// since this negatively affects readability.
@file:Suppress("ktlint:standard:comment-wrapping")

package com.android.server.ethernet

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkProvider
import android.net.NetworkProvider.NetworkOfferCallback
import android.os.Build
import android.os.Handler
import android.os.test.TestLooper
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val IFACE = "eth0"
private val CAPS = NetworkCapabilities.Builder().build()

@SmallTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class EthernetInterfaceStateMachineTest {
    private lateinit var looper: TestLooper
    private lateinit var handler: Handler
    private lateinit var ifaceState: EthernetInterfaceStateMachine

    @Mock private lateinit var context: Context
    @Mock private lateinit var provider: NetworkProvider
    @Mock private lateinit var deps: EthernetNetworkFactory.Dependencies

    // There seems to be no (obvious) way to force execution of @Before and @Test annotation on the
    // same thread. Since SyncStateMachine requires all interactions to be called from the same
    // thread that is provided at construction time (in this case, the thread that TestLooper() is
    // called on), setUp() must be called directly from the @Test method.
    // TODO: find a way to fix this in the test runner.
    fun setUp() {
        looper = TestLooper()
        handler = Handler(looper.looper)
        MockitoAnnotations.initMocks(this)

        ifaceState = EthernetInterfaceStateMachine(IFACE, handler, context, CAPS, provider, deps)
    }

    @Test
    fun testUpdateLinkState_networkOfferRegisteredAndRetracted() {
        setUp()

        ifaceState.updateLinkState(/* up= */ true)

        // link comes up: validate the NetworkOffer is registered and capture callback object.
        val inOrder = inOrder(provider)
        val networkOfferCb = ArgumentCaptor.forClass(NetworkOfferCallback::class.java).also {
            inOrder.verify(provider).registerNetworkOffer(any(), any(), any(), it.capture())
        }.value

        ifaceState.updateLinkState(/* up */ false)

        // link goes down: validate the NetworkOffer is retracted
        inOrder.verify(provider).unregisterNetworkOffer(eq(networkOfferCb))
    }
}
