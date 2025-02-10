/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.net;

import android.annotation.Nullable;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkScore;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientManager;
import android.net.ip.IpClientUtil;
import android.net.shared.ProvisioningConfiguration;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class L2capNetwork {
    private static final NetworkScore NETWORK_SCORE = new NetworkScore.Builder().build();
    private final String mLogTag;
    private final Handler mHandler;
    private final String mIfname;
    private final L2capPacketForwarder mForwarder;
    private final NetworkCapabilities mNetworkCapabilities;
    private final L2capIpClient mIpClient;
    private final NetworkAgent mNetworkAgent;

    /** IpClient wrapper to handle IPv6 link-local provisioning for L2CAP tun.
     *
     * Note that the IpClient does not need to be stopped.
     */
    private static class L2capIpClient extends IpClientCallbacks {
        private final String mLogTag;
        private final ConditionVariable mOnIpClientCreatedCv = new ConditionVariable(false);
        private final ConditionVariable mOnProvisioningSuccessCv = new ConditionVariable(false);
        @Nullable
        private IpClientManager mIpClient;
        @Nullable
        private LinkProperties mLinkProperties;

        L2capIpClient(String logTag, Context context, String ifname) {
            mLogTag = logTag;
            IpClientUtil.makeIpClient(context, ifname, this);
        }

        @Override
        public void onIpClientCreated(IIpClient ipClient) {
            mIpClient = new IpClientManager(ipClient, mLogTag);
            mOnIpClientCreatedCv.open();
        }

        @Override
        public void onProvisioningSuccess(LinkProperties lp) {
            Log.d(mLogTag, "Successfully provisionined l2cap tun: " + lp);
            mLinkProperties = lp;
            mOnProvisioningSuccessCv.open();
        }

        public LinkProperties start() {
            mOnIpClientCreatedCv.block();
            // mIpClient guaranteed non-null.
            final ProvisioningConfiguration config = new ProvisioningConfiguration.Builder()
                    .withoutIPv4()
                    .withIpv6LinkLocalOnly()
                    .withRandomMacAddress() // addr_gen_mode EUI64 -> random on tun.
                    .build();
            mIpClient.startProvisioning(config);
            // "Provisioning" is guaranteed to succeed as link-local only mode does not actually
            // require any provisioning.
            mOnProvisioningSuccessCv.block();
            return mLinkProperties;
        }
    }

    public interface ICallback {
        /** Called when an error is encountered */
        void onError(L2capNetwork network);
        /** Called when CS triggers NetworkAgent#onNetworkUnwanted */
        void onNetworkUnwanted(L2capNetwork network);
    }

    public L2capNetwork(Handler handler, Context context, NetworkProvider provider, String ifname,
            BluetoothSocket socket, ParcelFileDescriptor tunFd,
            NetworkCapabilities networkCapabilities, ICallback cb) {
        // TODO: add a check that this constructor is invoked on the handler thread.
        mLogTag = String.format("L2capNetwork[%s]", ifname);
        mHandler = handler;
        mIfname = ifname;
        mForwarder = new L2capPacketForwarder(handler, tunFd, socket, () -> {
            // TODO: add a check that this callback is invoked on the handler thread.
            cb.onError(L2capNetwork.this);
        });
        mNetworkCapabilities = networkCapabilities;
        mIpClient = new L2capIpClient(mLogTag, context, ifname);
        final LinkProperties linkProperties = mIpClient.start();

        final NetworkAgentConfig config = new NetworkAgentConfig.Builder().build();
        mNetworkAgent = new NetworkAgent(context, mHandler.getLooper(), mLogTag,
                networkCapabilities, linkProperties, NETWORK_SCORE, config, provider) {
            @Override
            public void onNetworkUnwanted() {
                Log.i(mLogTag, mIfname + ": Network is unwanted");
                // TODO: add a check that this callback is invoked on the handler thread.
                cb.onNetworkUnwanted(L2capNetwork.this);
            }
        };
        mNetworkAgent.register();
        mNetworkAgent.markConnected();
    }

    /** Get the NetworkCapabilities used for this Network */
    public NetworkCapabilities getNetworkCapabilities() {
        return mNetworkCapabilities;
    }

    /** Tear down the network and associated resources */
    public void tearDown() {
        mNetworkAgent.unregister();
        mForwarder.tearDown();
    }
}
