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
import android.os.Message;
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

    private static final int CMD_ON_NETWORK_NEEDED   = 1;
    private static final int CMD_ON_NETWORK_UNNEEDED = 2;

    private class EthernetNetworkOfferCallback implements NetworkOfferCallback {
        private final Set<Integer> mRequestIds = new ArraySet<>();

        @Override
        public void onNetworkNeeded(@NonNull NetworkRequest request) {
            if (this != mNetworkOfferCallback) {
                return;
            }

            mRequestIds.add(request.requestId);
            if (mRequestIds.size() == 1) {
                processMessage(CMD_ON_NETWORK_NEEDED);
            }
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
            if (mRequestIds.isEmpty()) {
                processMessage(CMD_ON_NETWORK_UNNEEDED);
            }
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
    private class LinkDownState extends State {

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

    /**
     * Offer is extended but has not been requested.
     *
     * StoppedState's sole purpose is to react to a CMD_ON_NETWORK_NEEDED and transition to
     * StartedState when that happens. Note that StoppedState could be rolled into
     * NetworkOfferExtendedState. However, keeping the states separate provides some additional
     * protection by logging a Log.wtf if a CMD_ON_NETWORK_NEEDED is received in an unexpected state
     * (i.e. StartedState or RunningState). StoppedState is a child of NetworkOfferExtendedState.
     */
    private class StoppedState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_ON_NETWORK_NEEDED:
                    transitionTo(mStartedState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    /** Network is needed, starts IpClient and manages its lifecycle */
    private class StartedState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_ON_NETWORK_UNNEEDED:
                    transitionTo(mStoppedState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    /** IpClient is running, starts provisioning and registers NetworkAgent */
    private class RunningState extends State {

    }

    private final TetheringState mTetheringState = new TetheringState();
    private final LinkDownState mLinkDownState = new LinkDownState();
    private final NetworkOfferExtendedState mOfferExtendedState = new NetworkOfferExtendedState();
    private final StoppedState mStoppedState = new StoppedState();
    private final StartedState mStartedState = new StartedState();
    private final RunningState mRunningState = new RunningState();

    public EthernetInterfaceStateMachine(String iface, Handler handler, NetworkCapabilities capabilities, NetworkProvider provider) {
        super(EthernetInterfaceStateMachine.class.getSimpleName() + "." + iface,
                handler.getLooper().getThread());

        mHandler = handler;
        mCapabilities = capabilities;
        mNetworkProvider = provider;

        // Interface lifecycle:
        //           [ LinkDownState ]
        //                   |
        //                   v
        //             *link comes up*
        //                   |
        //                   v
        //            [ StoppedState ]
        //                   |
        //                   v
        //           *network is needed*
        //                   |
        //                   v
        //            [ StartedState ]
        //                   |
        //                   v
        //           *IpClient is created*
        //                   |
        //                   v
        //            [ RunningState ]
        //                   |
        //                   v
        //  *interface is requested for tethering*
        //                   |
        //                   v
        //            [TetheringState]
        //
        // Tethering mode is special as the interface is configured by Tethering, rather than the
        // ethernet module.
        final List<StateInfo> states = new ArrayList<>();
        states.add(new StateInfo(mTetheringState, null));

        // CHECKSTYLE:OFF IndentationCheck
        // Initial state
        states.add(new StateInfo(mLinkDownState, null));
        states.add(new StateInfo(mOfferExtendedState, null));
            states.add(new StateInfo(mStoppedState, mOfferExtendedState));
            states.add(new StateInfo(mStartedState, mOfferExtendedState));
                states.add(new StateInfo(mRunningState, mStartedState));
        // CHECKSTYLE:ON IndentationCheck

        // TODO: set initial state to TetheringState if a tethering interface has been requested and
        // this is the first interface to be added.
        start(mLinkDownState);
    }
}
