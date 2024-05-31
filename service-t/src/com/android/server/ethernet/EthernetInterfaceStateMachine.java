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

package com.android.server.ethernet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.State;
import com.android.net.module.util.SyncStateMachine;
import com.android.net.module.util.SyncStateMachine.StateInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class EthernetInterfaceStateMachine extends SyncStateMachine {
    private static final String TAG = EthernetInterfaceStateMachine.class.getSimpleName();

    private class EthernetNetworkOfferCallback implements NetworkOfferCallback {
        private final Set<Integer> mRequestIds = new ArraySet<>();

        @Override
        public void onNetworkNeeded(@NonNull NetworkRequest request) {
            if (this != mNetworkOfferCallback) {
                return;
            }

            mRequestIds.add(request.requestId);
            // TODO: send ON_NETWORK_NEEDED message if requestIds.size() == 1
        }

        @Override
        public void onNetworkUnneeded(@NonNull NetworkRequest request) {
            if (this != mNetworkOfferCallback) {
                return;
            }

            if (!mRequestIds.remove(request.requestId)) {
                // This can only happen if onNetworkNeeded was not called for a request or if
                // the requestId changed. Both should *never* happen.
                Log.wtf(TAG, "onNetworkUnneeded called for unknown request");
            }
            // TODO: send ON_NETWORK_UNNEEDED message if requestIds.isEmpty()
        }
    }

    private @Nullable EthernetNetworkOfferCallback mNetworkOfferCallback;
    private final Handler mHandler;
    private final NetworkCapabilities mCapabilities;
    private final NetworkProvider mNetworkProvider;

    /** Interface is in tethering mode. */
    private class TetheringState extends State {

    }

    /** Link is down */
    private class DisconnectedState extends State {

    }

    /** Parent states of all states that do not cause a NetworkOffer to be extended. */
    private class NetworkOfferExtendedState extends State {
        @Override
        public void enter() {
            if (mNetworkOfferCallback != null) {
                // This should never happen. If it happens anyway, log and move on.
                Log.wtf(TAG, "Previous NetworkOffer was never retracted");
            }

            mNetworkOfferCallback = new EthernetNetworkOfferCallback();
            final NetworkScore defaultScore = new NetworkScore.Builder().build();
            mNetworkProvider.registerNetworkOffer(defaultScore,
                    new NetworkCapabilities(mCapabilities), cmd -> mHandler.post(cmd),
                    mNetworkOfferCallback);
        }

        @Override
        public void exit() {
            mNetworkProvider.unregisterNetworkOffer(mNetworkOfferCallback);
            mNetworkOfferCallback = null;
        }
    }

    /** Link is up, network offer is extended */
    private class StoppedState extends State {
    }

    /** Network is needed, start provisioning */
    private class ProvisioningState extends State {

    }

    /** Network is needed */
    private class RunningState extends State {

    }

    private final TetheringState mTetheringState = new TetheringState();
    private final DisconnectedState mDisconnectedState = new DisconnectedState();
    private final NetworkOfferExtendedState mOfferExtendedState = new NetworkOfferExtendedState();
    private final StoppedState mStoppedState = new StoppedState();
    private final ProvisioningState mProvisioningState = new ProvisioningState();
    private final RunningState mRunningState = new RunningState();

    public EthernetInterfaceStateMachine(String iface, Handler handler, NetworkCapabilities capabilities, NetworkProvider provider) {
        super(EthernetInterfaceStateMachine.class.getSimpleName() + "." + iface,
                handler.getLooper().getThread());

        mHandler = handler;
        mCapabilities = capabilities;
        mNetworkProvider = provider;

        // Tethering mode is special as the interface is configured by Tethering, rather than the
        // ethernet module.
        final List<StateInfo> states = new ArrayList<>();
        states.add(new StateInfo(mTetheringState, null));

        // CHECKSTYLE:OFF IndentationCheck
        // Initial state
        states.add(new StateInfo(mDisconnectedState, null));
        states.add(new StateInfo(mOfferExtendedState, null));
            states.add(new StateInfo(mStoppedState, mOfferExtendedState));
            states.add(new StateInfo(mProvisioningState, mOfferExtendedState));
            states.add(new StateInfo(mRunningState, mOfferExtendedState));
        // CHECKSTYLE:ON IndentationCheck

        // TODO: set initial state to TetheringState if a tethering interface has been requested and
        // this is the first interface to be added.
        start(mDisconnectedState);
    }
}
