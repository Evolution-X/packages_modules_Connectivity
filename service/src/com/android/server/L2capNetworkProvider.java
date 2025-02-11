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
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE;
import static android.system.OsConstants.F_GETFL;
import static android.system.OsConstants.F_SETFL;
import static android.system.OsConstants.O_NONBLOCK;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.L2capNetworkSpecifier;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.NetworkSpecifier;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.ServiceConnectivityJni;
import com.android.server.net.L2capNetwork;

import java.io.IOException;
import java.util.Set;


public class L2capNetworkProvider {
    private static final String TAG = L2capNetworkProvider.class.getSimpleName();
    private static final NetworkCapabilities COMMON_CAPABILITIES =
            // TODO: add NET_CAPABILITY_NOT_RESTRICTED and check that getRequestorUid() has
            // BLUETOOTH_CONNECT permission.
            NetworkCapabilities.Builder.withoutDefaultCapabilities()
                    .addTransportType(TRANSPORT_BLUETOOTH)
                    // TODO: remove NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED.
                    .addCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
                    .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .addCapability(NET_CAPABILITY_NOT_ROAMING)
                    .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                    .addCapability(NET_CAPABILITY_NOT_VPN)
                    .build();
    private final Dependencies mDeps;
    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final NetworkProvider mProvider;
    private final BlanketReservationOffer mBlanketOffer;
    private final Set<ReservedServerOffer> mReservedServerOffers = new ArraySet<>();
    // mBluetoothManager guaranteed non-null when read on handler thread after start() is called
    @Nullable
    private BluetoothManager mBluetoothManager;

    // Note: IFNAMSIZ is 16.
    private static final String TUN_IFNAME = "l2cap-tun";
    private static int sTunIndex = 0;

    /**
     * The blanket reservation offer is used to create an L2CAP server network, i.e. a network
     * based on a BluetoothServerSocket.
     *
     * Note that NetworkCapabilities matching semantics will cause onNetworkNeeded to be called for
     * requests that do not have a NetworkSpecifier set.
     */
    private class BlanketReservationOffer implements NetworkOfferCallback {
        // Set as transport primary to ensure that the BlanketReservationOffer always outscores the
        // ReservedServerOffer, because as soon as the BlanketReservationOffer receives an
        // onNetworkUnneeded() callback, it will tear down the associated reserved offer.
        public static final NetworkScore SCORE = new NetworkScore.Builder()
                .setTransportPrimary(true)
                .build();
        // Note the missing NET_CAPABILITY_NOT_RESTRICTED marking the network as restricted.
        public static final NetworkCapabilities CAPABILITIES;
        static {
            // Below capabilities will match any reservation request with an L2capNetworkSpecifier
            // that specifies ROLE_SERVER or without a NetworkSpecifier.
            final L2capNetworkSpecifier l2capNetworkSpecifier = new L2capNetworkSpecifier.Builder()
                    .setRole(ROLE_SERVER)
                    .build();
            NetworkCapabilities caps = new NetworkCapabilities.Builder(COMMON_CAPABILITIES)
                    .setNetworkSpecifier(l2capNetworkSpecifier)
                    .build();
            // TODO: add #setReservationId() to NetworkCapabilities.Builder
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

            final ReservedServerOffer reservedOffer = createReservedServerOffer(request);
            if (reservedOffer == null) {
                // Something went wrong when creating the offer. Send onUnavailable() to the app.
                Log.e(TAG, "Failed to create L2cap server offer");
                mProvider.declareNetworkRequestUnfulfillable(request);
                return;
            }

            final NetworkCapabilities reservedCaps = reservedOffer.getReservedCapabilities();
            mProvider.registerNetworkOffer(SCORE, reservedCaps, mHandler::post, reservedOffer);
            mReservedServerOffers.add(reservedOffer);
        }

        @Nullable
        private ReservedServerOffer createReservedServerOffer(NetworkRequest reservation) {
            final BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
            if (bluetoothAdapter == null) {
                Log.w(TAG, "Failed to get BluetoothAdapter");
                return null;
            }
            final BluetoothServerSocket serverSocket;
            try {
                serverSocket = bluetoothAdapter.listenUsingInsecureL2capChannel();
            } catch (IOException e) {
                Log.w(TAG, "Failed to open BluetoothServerSocket");
                return null;
            }

            // Create the reserved capabilities partially from the reservation itself (non-reserved
            // parts of the L2capNetworkSpecifier), the COMMON_CAPABILITIES, and the reserved data
            // (BLE L2CAP PSM from the BluetoothServerSocket).
            final NetworkCapabilities reservationNc = reservation.networkCapabilities;
            final L2capNetworkSpecifier reservationSpec =
                    (L2capNetworkSpecifier) reservationNc.getNetworkSpecifier();
            // Note: the RemoteAddress is unspecified for server networks.
            final L2capNetworkSpecifier reservedSpec = new L2capNetworkSpecifier.Builder()
                    .setRole(ROLE_SERVER)
                    .setHeaderCompression(reservationSpec.getHeaderCompression())
                    .setPsm(serverSocket.getPsm())
                    .build();
            NetworkCapabilities reservedNc =
                    new NetworkCapabilities.Builder(COMMON_CAPABILITIES)
                            .setNetworkSpecifier(reservedSpec)
                            .build();
            reservedNc.setReservationId(reservationNc.getReservationId());
            return new ReservedServerOffer(reservedNc, serverSocket);
        }

        @Nullable
        private ReservedServerOffer getReservedOfferForRequest(NetworkRequest request) {
            final int rId = request.networkCapabilities.getReservationId();
            for (ReservedServerOffer offer : mReservedServerOffers) {
                // Comparing by reservationId is more explicit then using canBeSatisfiedBy() or the
                // requestId.
                if (offer.getReservedCapabilities().getReservationId() != rId) continue;
                return offer;
            }
            return null;
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            final ReservedServerOffer reservedOffer = getReservedOfferForRequest(request);
            if (reservedOffer == null) return;

            // Note that the reserved offer gets torn down when the reservation goes away, even if
            // there are active (non-reservation) requests for said offer.
            destroyAndUnregisterReservedOffer(reservedOffer);
        }
    }

    private void destroyAndUnregisterReservedOffer(ReservedServerOffer reservedOffer) {
        // Ensure the offer still exists if this was posted on the handler.
        if (!mReservedServerOffers.contains(reservedOffer)) return;
        mReservedServerOffers.remove(reservedOffer);

        reservedOffer.tearDown();
        mProvider.unregisterNetworkOffer(reservedOffer);
    }

    @Nullable
    private static ParcelFileDescriptor createTunInterface(String ifname) {
        final ParcelFileDescriptor fd;
        try {
            fd = ParcelFileDescriptor.adoptFd(
                    ServiceConnectivityJni.createTunTap(
                            true /*isTun*/, true /*hasCarrier*/, true /*setIffMulticast*/, ifname));
            ServiceConnectivityJni.bringUpInterface(ifname);
            // TODO: consider adding a parameter to createTunTap() (or the Builder that should
            // be added) to configure i/o blocking.
            final int flags = Os.fcntlInt(fd.getFileDescriptor(), F_GETFL, 0);
            Os.fcntlInt(fd.getFileDescriptor(), F_SETFL, flags & ~O_NONBLOCK);
        } catch (Exception e) {
            // Note: createTunTap currently throws an IllegalStateException on failure.
            // TODO: native functions should throw ErrnoException.
            Log.e(TAG, "Failed to create tun interface", e);
            return null;
        }
        return fd;
    }

    @Nullable
    private L2capNetwork createL2capNetwork(BluetoothSocket socket, NetworkCapabilities caps,
            L2capNetwork.ICallback cb) {
        final String ifname = TUN_IFNAME + String.valueOf(sTunIndex++);
        final ParcelFileDescriptor tunFd = createTunInterface(ifname);
        if (tunFd == null) {
            return null;
        }

        return new L2capNetwork(mHandler, mContext, mProvider, ifname, socket, tunFd, caps, cb);
    }


    private class ReservedServerOffer implements NetworkOfferCallback {
        private final NetworkCapabilities mReservedCapabilities;
        private final BluetoothServerSocket mServerSocket;

        public ReservedServerOffer(NetworkCapabilities reservedCapabilities,
                BluetoothServerSocket serverSocket) {
            mReservedCapabilities = reservedCapabilities;
            // TODO: ServerSocket will be managed by an AcceptThread.
            mServerSocket = serverSocket;
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
            try {
                mServerSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close BluetoothServerSocket", e);
            }
        }
    }

    @VisibleForTesting
    public static class Dependencies {
        /** Get NetworkProvider */
        public NetworkProvider getNetworkProvider(Context context, Looper looper) {
            return new NetworkProvider(context, looper, TAG);
        }

        /** Get the HandlerThread for L2capNetworkProvider to run on */
        public HandlerThread getHandlerThread() {
            final HandlerThread thread = new HandlerThread("L2capNetworkProviderThread");
            thread.start();
            return thread;
        }
    }

    public L2capNetworkProvider(Context context) {
        this(new Dependencies(), context);
    }

    @VisibleForTesting
    public L2capNetworkProvider(Dependencies deps, Context context) {
        mDeps = deps;
        mContext = context;
        mHandlerThread = mDeps.getHandlerThread();
        mHandler = new Handler(mHandlerThread.getLooper());
        mProvider = mDeps.getNetworkProvider(context, mHandlerThread.getLooper());
        mBlanketOffer = new BlanketReservationOffer();
    }

    /**
     * Start L2capNetworkProvider.
     *
     * Called on CS Handler thread.
     */
    public void start() {
        mHandler.post(() -> {
            final PackageManager pm = mContext.getPackageManager();
            if (!pm.hasSystemFeature(FEATURE_BLUETOOTH_LE)) {
                return;
            }
            mBluetoothManager = mContext.getSystemService(BluetoothManager.class);
            if (mBluetoothManager == null) {
                // Can this ever happen?
                Log.wtf(TAG, "BluetoothManager not found");
                return;
            }
            mContext.getSystemService(ConnectivityManager.class).registerNetworkProvider(mProvider);
            mProvider.registerNetworkOffer(BlanketReservationOffer.SCORE,
                    BlanketReservationOffer.CAPABILITIES, mHandler::post, mBlanketOffer);
        });
    }
}
