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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.os.Handler;
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;


public class L2capNetworkProvider {
    private static final String TAG = L2capNetworkProvider.class.getSimpleName();
    private final Dependencies mDeps;
    private final Handler mHandler;
    private final NetworkProvider mProvider;
    private final BlanketReservationOffer mBlanketOffer;

    /**
     * The blanket reservation offer is used to create an L2CAP server network, i.e. a network
     * based on a BluetoothServerSocket.
     *
     * Note that NetworkCapabilities matching semantics will cause onNetworkNeeded to be called for
     * requests that do not have a NetworkSpecifier set.
     */
    private class BlanketReservationOffer implements NetworkOfferCallback {
        // TODO: ensure that once the incoming request is satisfied, the blanket offer does not get
        // unneeded. This means the blanket offer must always outscore the reserved offer. This
        // might require setting the blanket offer as setTransportPrimary().
        public static final NetworkScore SCORE = new NetworkScore.Builder().build();
        // Note the missing NET_CAPABILITY_NOT_RESTRICTED marking the network as restricted.
        public static final NetworkCapabilities CAPABILITIES;
        static {
            NetworkCapabilities caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                    .addTransportType(TRANSPORT_BLUETOOTH)
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .addCapability(NET_CAPABILITY_NOT_ROAMING)
                    .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                    .addCapability(NET_CAPABILITY_NOT_VPN)
                    .build();
            caps.setReservationId(RES_ID_MATCH_ALL_RESERVATIONS);
            CAPABILITIES = caps;
        }

        @Override
        public void onNetworkNeeded(NetworkRequest request) {
            // TODO: implement
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            // TODO: implement
        }
    }

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
        mBlanketOffer = new BlanketReservationOffer();

        final boolean isBleSupported =
                context.getPackageManager().hasSystemFeature(FEATURE_BLUETOOTH_LE);
        if (isBleSupported) {
            context.getSystemService(ConnectivityManager.class).registerNetworkProvider(mProvider);
            mProvider.registerNetworkOffer(BlanketReservationOffer.SCORE,
                    BlanketReservationOffer.CAPABILITIES, mHandler::post, mBlanketOffer);
        }
    }
}

