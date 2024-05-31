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

import android.os.Handler;

import com.android.internal.util.State;
import com.android.net.module.util.SyncStateMachine;
import com.android.net.module.util.SyncStateMachine.StateInfo;

import java.util.ArrayList;
import java.util.List;

class EthernetInterfaceStateMachine extends SyncStateMachine {
    /** Interface is in tethering mode. */
    private class TetheringState extends State {

    }

    /** Link is down */
    private class DisconnectedState extends State {

    }

    /** Parent states of all states that do not cause a NetworkOffer to be extended. */
    private class NetworkOfferExtendedState extends State {

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

    public EthernetInterfaceStateMachine(String iface, Handler handler) {
        super(EthernetInterfaceStateMachine.class.getSimpleName() + "." + iface,
                handler.getLooper().getThread());

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
