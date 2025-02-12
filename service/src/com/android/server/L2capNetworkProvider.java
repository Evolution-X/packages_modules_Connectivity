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
import static android.net.L2capNetworkSpecifier.ROLE_CLIENT;
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
import android.bluetooth.BluetoothDevice;
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
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.ServiceConnectivityJni;
import com.android.server.net.L2capNetwork;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ClientOffer mClientOffer;
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

    private static void closeBluetoothSocket(BluetoothSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close BluetoothSocket", e);
        }
    }

    private class ReservedServerOffer implements NetworkOfferCallback {
        private final NetworkCapabilities mReservedCapabilities;
        private final AcceptThread mAcceptThread;
        // This set should almost always contain at most one network. This is because all L2CAP
        // server networks created by the same reserved offer are indistinguishable from each other,
        // so that ConnectivityService will tear down all but the first. However, temporarily, there
        // can be more than one network.
        private final Set<L2capNetwork> mL2capNetworks = new ArraySet<>();

        private class AcceptThread extends Thread {
            private static final int TIMEOUT_MS = 500;
            private final BluetoothServerSocket mServerSocket;
            private volatile boolean mIsRunning = true;

            public AcceptThread(BluetoothServerSocket serverSocket) {
                mServerSocket = serverSocket;
            }

            private void postDestroyAndUnregisterReservedOffer() {
                mHandler.post(() -> {
                    destroyAndUnregisterReservedOffer(ReservedServerOffer.this);
                });
            }

            private void postCreateServerNetwork(BluetoothSocket connectedSocket) {
                mHandler.post(() -> {
                    final boolean success = createServerNetwork(connectedSocket);
                    if (!success) closeBluetoothSocket(connectedSocket);
                });
            }

            public void run() {
                while (mIsRunning) {
                    final BluetoothSocket connectedSocket;
                    try {
                        connectedSocket = mServerSocket.accept();
                    } catch (IOException e) {
                        // BluetoothServerSocket was closed().
                        if (!mIsRunning) return;

                        // Else, BluetoothServerSocket encountered exception.
                        Log.e(TAG, "BluetoothServerSocket#accept failed", e);
                        postDestroyAndUnregisterReservedOffer();
                        return; // stop running immediately on error
                    }
                    postCreateServerNetwork(connectedSocket);
                }
            }

            public void tearDown() {
                mIsRunning = false;
                try {
                    // BluetoothServerSocket.close() is thread-safe.
                    mServerSocket.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to close BluetoothServerSocket", e);
                }
                try {
                    join();
                } catch (InterruptedException e) {
                    // join() interrupted during tearDown(). Do nothing.
                }
            }
        }

        private boolean createServerNetwork(BluetoothSocket socket) {
            // It is possible the offer went away.
            if (!mReservedServerOffers.contains(this)) return false;

            if (!socket.isConnected()) {
                Log.wtf(TAG, "BluetoothSocket must be connected");
                return false;
            }

            final L2capNetwork network = createL2capNetwork(socket, mReservedCapabilities,
                    new L2capNetwork.ICallback() {
                @Override
                public void onError(L2capNetwork network) {
                    destroyAndUnregisterReservedOffer(ReservedServerOffer.this);
                }
                @Override
                public void onNetworkUnwanted(L2capNetwork network) {
                    // Leave reservation in place.
                    final boolean networkExists = mL2capNetworks.remove(network);
                    if (!networkExists) return; // already torn down.
                    network.tearDown();
                }
            });

            if (network == null) {
                Log.e(TAG, "Failed to create L2capNetwork");
                return false;
            }

            mL2capNetworks.add(network);
            return true;
        }

        public ReservedServerOffer(NetworkCapabilities reservedCapabilities,
                BluetoothServerSocket serverSocket) {
            mReservedCapabilities = reservedCapabilities;
            mAcceptThread = new AcceptThread(serverSocket);
            mAcceptThread.start();
        }

        public NetworkCapabilities getReservedCapabilities() {
            return mReservedCapabilities;
        }

        @Override
        public void onNetworkNeeded(NetworkRequest request) {
            // UNUSED: the lifetime of the reserved network is controlled by the blanket offer.
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            // UNUSED: the lifetime of the reserved network is controlled by the blanket offer.
        }

        /** Called when the reservation goes away and the reserved offer must be torn down. */
        public void tearDown() {
            mAcceptThread.tearDown();
            for (L2capNetwork network : mL2capNetworks) {
                network.tearDown();
            }
        }
    }

    private class ClientOffer implements NetworkOfferCallback {
        public static final NetworkScore SCORE = new NetworkScore.Builder().build();
        public static final NetworkCapabilities CAPABILITIES;
        static {
            // Below capabilities will match any request with an L2capNetworkSpecifier
            // that specifies ROLE_CLIENT or without a NetworkSpecifier.
            final L2capNetworkSpecifier l2capNetworkSpecifier = new L2capNetworkSpecifier.Builder()
                    .setRole(ROLE_CLIENT)
                    .build();
            CAPABILITIES = new NetworkCapabilities.Builder(COMMON_CAPABILITIES)
                    .setNetworkSpecifier(l2capNetworkSpecifier)
                    .build();
        }

        private final Map<L2capNetworkSpecifier, ClientRequestInfo> mClientNetworkRequests =
                new ArrayMap<>();

        /**
         * State object to store information for client NetworkRequests.
         */
        private static class ClientRequestInfo {
            public final L2capNetworkSpecifier specifier;
            public final List<NetworkRequest> requests = new ArrayList<>();
            // TODO: add support for retries.
            public final ConnectThread connectThread;
            @Nullable
            public L2capNetwork network;

            public ClientRequestInfo(NetworkRequest request, ConnectThread connectThread) {
                this.specifier = (L2capNetworkSpecifier) request.getNetworkSpecifier();
                this.requests.add(request);
                this.connectThread = connectThread;
            }
        }

        // TODO: consider using ExecutorService
        private class ConnectThread extends Thread {
            private final L2capNetworkSpecifier mSpecifier;
            private final BluetoothSocket mSocket;
            private volatile boolean mIsAborted = false;

            public ConnectThread(L2capNetworkSpecifier specifier, BluetoothSocket socket) {
                mSpecifier = specifier;
                mSocket = socket;
            }

            public void run() {
                try {
                    mSocket.connect();
                    mHandler.post(() -> {
                        final boolean success = createClientNetwork(mSpecifier, mSocket);
                        if (!success) closeBluetoothSocket(mSocket);
                    });
                } catch (IOException e) {
                    Log.e(TAG, "Failed to connect", e);
                    if (mIsAborted) return;

                    closeBluetoothSocket(mSocket);
                    mHandler.post(() -> {
                        declareAllNetworkRequestsUnfulfillable(mSpecifier);
                    });
                }
            }

            public void abort() {
                mIsAborted = true;
                // Closing the BluetoothSocket is the only way to unblock connect() because it calls
                // shutdown on the underlying (connected) SOCK_SEQPACKET.
                // It is safe to call BluetoothSocket#close() multiple times.
                closeBluetoothSocket(mSocket);
                try {
                    join();
                } catch (InterruptedException e) {
                    Log.i(TAG, "Interrupted while joining ConnectThread", e);
                }
            }
        }

        private boolean createClientNetwork(L2capNetworkSpecifier specifier,
                BluetoothSocket socket) {
            // Check whether request still exists
            final ClientRequestInfo cri = mClientNetworkRequests.get(specifier);
            if (cri == null) return false;

            final NetworkCapabilities caps = new NetworkCapabilities.Builder(CAPABILITIES)
                    .setNetworkSpecifier(specifier)
                    .build();

            final L2capNetwork network = createL2capNetwork(socket, caps,
                    new L2capNetwork.ICallback() {
                // TODO: do not send onUnavailable() after the network has become available. The
                // right thing to do here is to tearDown the network (if it still exists, because
                // note that the request might have already been removed in the meantime, so
                // `network` cannot be used directly.
                @Override
                public void onError(L2capNetwork network) {
                    declareAllNetworkRequestsUnfulfillable(specifier);
                }
                @Override
                public void onNetworkUnwanted(L2capNetwork network) {
                    declareAllNetworkRequestsUnfulfillable(specifier);
                }
            });
            if (network == null) return false;

            cri.network = network;
            return true;
        }

        private boolean isValidL2capSpecifier(@Nullable NetworkSpecifier spec) {
            if (spec == null) return false;

            // If not null, guaranteed to be L2capNetworkSepcifier.
            final L2capNetworkSpecifier l2capSpec = (L2capNetworkSpecifier) spec;

            // The ROLE_CLIENT offer can be satisfied by a ROLE_ANY request.
            if (l2capSpec.getRole() != ROLE_CLIENT) return false;

            // HEADER_COMPRESSION_ANY is never valid in a request.
            if (l2capSpec.getHeaderCompression() == HEADER_COMPRESSION_ANY) return false;

            // remoteAddr must not be null for ROLE_CLIENT requests.
            if (l2capSpec.getRemoteAddress() == null) return false;

            // Client network requests require a PSM to be specified.
            // Ensure the PSM is within the valid range of dynamic BLE L2CAP values.
            if (l2capSpec.getPsm() < 0x80) return false;
            if (l2capSpec.getPsm() > 0xFF) return false;

            return true;
        }

        @Override
        public void onNetworkNeeded(NetworkRequest request) {
            Log.d(TAG, "New client network request: " + request);
            if (!isValidL2capSpecifier(request.getNetworkSpecifier())) {
                Log.w(TAG, "Ignoring invalid client request: " + request);
                return;
            }

            final L2capNetworkSpecifier requestSpecifier =
                    (L2capNetworkSpecifier) request.getNetworkSpecifier();
             // Check whether this exact request is already being tracked.
            final ClientRequestInfo cri = mClientNetworkRequests.get(requestSpecifier);
            if (cri != null) {
                Log.d(TAG, "The request is already being tracked. NetworkRequest: " + request);
                cri.requests.add(request);
                return;
            }

            // Check whether a fuzzy match shows a mismatch in header compression by calling
            // canBeSatisfiedBy().
            // TODO: Add a copy constructor to L2capNetworkSpecifier.Builder.
            final L2capNetworkSpecifier matchAnyHeaderCompressionSpecifier =
                    new L2capNetworkSpecifier.Builder()
                            .setRole(requestSpecifier.getRole())
                            .setRemoteAddress(requestSpecifier.getRemoteAddress())
                            .setPsm(requestSpecifier.getPsm())
                            .setHeaderCompression(HEADER_COMPRESSION_ANY)
                            .build();
            for (L2capNetworkSpecifier existingSpecifier : mClientNetworkRequests.keySet()) {
                if (existingSpecifier.canBeSatisfiedBy(matchAnyHeaderCompressionSpecifier)) {
                    // This requeset can never be serviced as this network already exists with a
                    // different header compression mechanism.
                    mProvider.declareNetworkRequestUnfulfillable(request);
                    return;
                }
            }

            // If the code reaches here, this is a new request.
            final BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
            if (bluetoothAdapter == null) {
                Log.w(TAG, "Failed to get BluetoothAdapter");
                mProvider.declareNetworkRequestUnfulfillable(request);
                return;
            }

            final byte[] macAddress = requestSpecifier.getRemoteAddress().toByteArray();
            final BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress);
            final BluetoothSocket socket;
            try {
                socket = bluetoothDevice.createInsecureL2capChannel(requestSpecifier.getPsm());
            } catch (IOException e) {
                Log.w(TAG, "Failed to createInsecureL2capChannel", e);
                mProvider.declareNetworkRequestUnfulfillable(request);
                return;
            }

            final ConnectThread connectThread = new ConnectThread(requestSpecifier, socket);
            connectThread.start();
            final ClientRequestInfo newRequestInfo = new ClientRequestInfo(request, connectThread);
            mClientNetworkRequests.put(requestSpecifier, newRequestInfo);
        }

        @Override
        public void onNetworkUnneeded(NetworkRequest request) {
            final L2capNetworkSpecifier specifier =
                    (L2capNetworkSpecifier) request.getNetworkSpecifier();

            // Map#get() is safe to call with null key
            final ClientRequestInfo cri = mClientNetworkRequests.get(specifier);
            if (cri == null) return;

            cri.requests.remove(request);
            if (cri.requests.size() > 0) return;

            // If the code reaches here, the network needs to be torn down.
            releaseClientNetworkRequest(cri);
        }

        /**
         * Release the client network request and tear down all associated state.
         *
         * Only call this when all associated NetworkRequests have been released.
         */
        private void releaseClientNetworkRequest(ClientRequestInfo cri) {
            mClientNetworkRequests.remove(cri.specifier);
            if (cri.connectThread.isAlive()) {
                // Note that if ConnectThread succeeds between calling #isAlive() and #abort(), the
                // request will already be removed from mClientNetworkRequests by the time the
                // createClientNetwork() call is processed on the handler, so it is safe to call
                // #abort().
                cri.connectThread.abort();
            }

            if (cri.network != null) {
                cri.network.tearDown();
            }
        }

        private void declareAllNetworkRequestsUnfulfillable(L2capNetworkSpecifier specifier) {
            final ClientRequestInfo cri = mClientNetworkRequests.get(specifier);
            if (cri == null) return;

            for (NetworkRequest request : cri.requests) {
                mProvider.declareNetworkRequestUnfulfillable(request);
            }
            releaseClientNetworkRequest(cri);
        }
    }

    @VisibleForTesting
    public static class Dependencies {
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
        mProvider = new NetworkProvider(context, mHandlerThread.getLooper(), TAG);
        mBlanketOffer = new BlanketReservationOffer();
        mClientOffer = new ClientOffer();
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
            mProvider.registerNetworkOffer(ClientOffer.SCORE,
                    ClientOffer.CAPABILITIES, mHandler::post, mClientOffer);
        });
    }
}
