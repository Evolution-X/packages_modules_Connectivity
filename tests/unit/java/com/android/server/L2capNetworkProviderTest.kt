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

package com.android.server

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
import android.net.ConnectivityManager
import android.net.NetworkProvider
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

const val TAG = "L2capNetworkProviderTest"

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class L2capNetworkProviderTest {
    @Mock private lateinit var context: Context
    @Mock private lateinit var deps: L2capNetworkProvider.Dependencies
    @Mock private lateinit var provider: NetworkProvider
    @Mock private lateinit var cm: ConnectivityManager
    @Mock private lateinit var pm: PackageManager

    private val handlerThread = HandlerThread("$TAG handler thread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doReturn(provider).`when`(deps).getNetworkProvider(any(), any())
        doReturn(cm).`when`(context).getSystemService(eq(ConnectivityManager::class.java))
        doReturn(pm).`when`(context).getPackageManager()
        doReturn(true).`when`(pm).hasSystemFeature(FEATURE_BLUETOOTH_LE)
    }

    @After
    fun tearDown() {
        handlerThread.quitSafely()
        handlerThread.join()
    }

    @Test
    fun testNetworkProvider_registeredWhenSupported() {
        L2capNetworkProvider(deps, context, handler)
        verify(cm).registerNetworkProvider(eq(provider))
    }

    @Test
    fun testNetworkProvider_notRegisteredWhenNotSupported() {
        doReturn(false).`when`(pm).hasSystemFeature(FEATURE_BLUETOOTH_LE)
        L2capNetworkProvider(deps, context, handler)
        verify(cm, never()).registerNetworkProvider(eq(provider))
    }
}
