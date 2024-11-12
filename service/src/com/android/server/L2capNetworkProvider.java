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

import static android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkProvider;
import android.os.Handler;
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;


public class L2capNetworkProvider {
    private static final String TAG = L2capNetworkProvider.class.getSimpleName();
    private final Dependencies mDeps;
    private final Handler mHandler;
    private final NetworkProvider mProvider;
    private final boolean mIsSupported;

    @VisibleForTesting
    public static class Dependencies {
        /** Get NetworkProvider */
        public NetworkProvider getNetworkProvider(Context context, Looper looper) {
            return new NetworkProvider(context, looper, TAG);
        }
    }

    public L2capNetworkProvider(Context context, Handler handler) {
        this(new Dependencies(), context, handler);
    }

    @VisibleForTesting
    public L2capNetworkProvider(Dependencies deps, Context context, Handler handler) {
        mDeps = deps;
        mHandler = handler;
        mProvider = mDeps.getNetworkProvider(context, handler.getLooper());

        mIsSupported = context.getPackageManager().hasSystemFeature(FEATURE_BLUETOOTH_LE);
        if (mIsSupported) {
            context.getSystemService(ConnectivityManager.class).registerNetworkProvider(mProvider);
        }
    }

}

