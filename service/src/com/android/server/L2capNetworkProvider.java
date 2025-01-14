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

import static android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN;
import static android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_ANY;
import static android.net.L2capNetworkSpecifier.PSM_ANY;
import static android.net.L2capNetworkSpecifier.ROLE_SERVER;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE;

import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.L2capNetworkSpecifier;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.NetworkSpecifier;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;


public class L2capNetworkProvider {
    private static final String TAG = L2capNetworkProvider.class.getSimpleName();
    private final Dependencies mDeps;
    private final Handler mHandler;
    private final NetworkProvider mProvider;
    private final BlanketReservationOffer mBlanketOffer;
    private final Map<Integer, ReservedServerOffer> mReservedServerOffers = new ArrayMap<>();

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
            final L2capNetworkSpecifier l2capNetworkSpecifier = new L2capNetworkSpecifier.Builder()
                    .setRole(ROLE_SERVER)
                    .build();
            NetworkCapabilities caps = NetworkCapabilities.Builder.withoutDefaultCapabilities()
                    .addTransportType(TRANSPORT_BLUETOOTH)
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .addCapability(NET_CAPABILITY_NOT_ROAMING)
                    .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                    .addCapability(NET_CAPABILITY_NOT_VPN)
                    .setNetworkSpecifier(l2capNetworkSpecifier)
                    .build();
            caps.setReservationId(RES_ID_MATCH_ALL_RESERVATIONS);
            CAPABILITIES = caps;
        }

        // TODO: consider moving this into L2capNetworkSpecifier as #isValidServerReservation().
        private boolean isValidL2capSpecifier(@Nullable NetworkSpecifier spec) {
            if (spec == null) return false;
            // If spec is not null, L2capNetworkSpecifier#canBeSatisfiedBy() guarantees the
            // specifier is of type L2capNetworkSpecifier.
            final L2capNetworkSpecifier l2capSpec = (L2capNetworkSpecifier) spec;

            // The ROLE_SERVER offer can be satisfied by a ROLE_ANY request.
            if (l2capSpec.getRole() != ROLE_SERVER) return false;

            // HEADER_COMPRESSION_ANY is never valid in a request.
            if (l2capSpec.getHeaderCompression() == HEADER_COMPRESSION_ANY) return false;

            // remoteAddr must be null for ROLE_SERVER requests.
            if (l2capSpec.getRemoteAddress() != null) return false;

            // reservation must allocate a PSM, so only PSM_ANY can be passed.
            if (l2capSpec.getPsm() != PSM_ANY) return false;

            return true;
        }

        @Override
        public void onNetworkNeeded(NetworkRequest request) {
            Log.d(TAG, "New reservation request: " + request);
            if (!isValidL2capSpecifier(request.getNetworkSpecifier())) {
                Log.w(TAG, "Ignoring invalid reservation request: " + request);
                return;
            }

            final NetworkCapabilities reservationCaps = request.networkCapabilities;
            final ReservedServerOffer reservedOffer = new ReservedServerOffer(reservationCaps);

            final NetworkCapabilities reservedCaps = reservedOffer.getReservedCapabilities();
            mProvider.registerNetworkOffer(SCORE, reservedCaps, mHandler::post, reservedOffer);
            mReservedServerOffers.put(request.requestId, reservedOffer);
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            if (!mReservedServerOffers.containsKey(request.requestId)) {
                return;
            }

            final ReservedServerOffer reservedOffer = mReservedServerOffers.get(request.requestId);
            // Note that the reserved offer gets torn down when the reservation goes away, even if
            // there are lingering requests.
            reservedOffer.tearDown();
            mProvider.unregisterNetworkOffer(reservedOffer);
        }
    }

    private class ReservedServerOffer implements NetworkOfferCallback {
        private final boolean mUseHeaderCompression;
        private final int mPsm;
        private final NetworkCapabilities mReservedCapabilities;

        public ReservedServerOffer(NetworkCapabilities reservationCaps) {
            // getNetworkSpecifier() is guaranteed to return a non-null L2capNetworkSpecifier.
            final L2capNetworkSpecifier reservationSpec =
                    (L2capNetworkSpecifier) reservationCaps.getNetworkSpecifier();
            mUseHeaderCompression =
                    reservationSpec.getHeaderCompression() == HEADER_COMPRESSION_6LOWPAN;

            // TODO: open BluetoothServerSocket and allocate a PSM.
            mPsm = 0x80;

            final L2capNetworkSpecifier reservedSpec = new L2capNetworkSpecifier.Builder()
                    .setRole(ROLE_SERVER)
                    .setHeaderCompression(reservationSpec.getHeaderCompression())
                    .setPsm(mPsm)
                    .build();
            mReservedCapabilities = new NetworkCapabilities.Builder(reservationCaps)
                    .setNetworkSpecifier(reservedSpec)
                    .build();
        }

        public NetworkCapabilities getReservedCapabilities() {
            return mReservedCapabilities;
        }

        @Override
        public void onNetworkNeeded(NetworkRequest request) {
            // TODO: implement
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            // TODO: implement
        }

        /**
         * Called when the reservation goes away and the reserved offer must be torn down.
         *
         * This method can be called multiple times.
         */
        public void tearDown() {
            // TODO: implement.
            // This method can be called multiple times.
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

