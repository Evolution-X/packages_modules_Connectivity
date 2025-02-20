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

import static android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN;

import android.annotation.Nullable;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.net.L2capNetworkSpecifier;
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

import com.android.server.L2capNetworkProvider;

public class L2capNetwork {
    private static final NetworkScore NETWORK_SCORE = new NetworkScore.Builder().build();
    private final String mLogTag;
    private final Handler mHandler;
    private final L2capPacketForwarder mForwarder;
    private final NetworkCapabilities mNetworkCapabilities;
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
        private volatile LinkProperties mLinkProperties;

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
            Log.d(mLogTag, "Successfully provisioned l2cap tun: " + lp);
            mLinkProperties = lp;
            mOnProvisioningSuccessCv.open();
        }

        @Override
        public void onProvisioningFailure(LinkProperties lp) {
            Log.i(mLogTag, "Failed to provision l2cap tun: " + lp);
            mLinkProperties = null;
            mOnProvisioningSuccessCv.open();
        }

        /**
         * Starts IPv6 link-local provisioning.
         *
         * @return LinkProperties on success, null on failure.
         */
        @Nullable
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

    public L2capNetwork(String logTag, Handler handler, Context context, NetworkProvider provider,
            BluetoothSocket socket, ParcelFileDescriptor tunFd, NetworkCapabilities nc,
            LinkProperties lp, L2capNetworkProvider.Dependencies deps, ICallback cb) {
        mLogTag = logTag;
        mHandler = handler;
        mNetworkCapabilities = nc;

        final L2capNetworkSpecifier spec = (L2capNetworkSpecifier) nc.getNetworkSpecifier();
        final boolean compressHeaders = spec.getHeaderCompression() == HEADER_COMPRESSION_6LOWPAN;

        mForwarder = deps.createL2capPacketForwarder(handler, tunFd, socket, compressHeaders,
                () -> {
            // TODO: add a check that this callback is invoked on the handler thread.
            cb.onError(L2capNetwork.this);
        });

        final NetworkAgentConfig config = new NetworkAgentConfig.Builder().build();
        mNetworkAgent = new NetworkAgent(context, mHandler.getLooper(), mLogTag,
                nc, lp, NETWORK_SCORE, config, provider) {
            @Override
            public void onNetworkUnwanted() {
                Log.i(mLogTag, "Network is unwanted");
                // TODO: add a check that this callback is invoked on the handler thread.
                cb.onNetworkUnwanted(L2capNetwork.this);
            }
        };
        mNetworkAgent.register();
        mNetworkAgent.markConnected();
    }

    /** Create an L2capNetwork or return null on failure. */
    @Nullable
    public static L2capNetwork create(Handler handler, Context context, NetworkProvider provider,
            String ifname, BluetoothSocket socket, ParcelFileDescriptor tunFd,
            NetworkCapabilities nc, L2capNetworkProvider.Dependencies deps, ICallback cb) {
        // TODO: add a check that this function is invoked on the handler thread.
        final String logTag = String.format("L2capNetwork[%s]", ifname);

        // L2capIpClient#start() blocks until provisioning either succeeds (and returns
        // LinkProperties) or fails (and returns null).
        // Note that since L2capNetwork is using IPv6 link-local provisioning the most likely
        // (only?) failure mode is due to the interface disappearing.
        final LinkProperties lp = new L2capIpClient(logTag, context, ifname).start();
        if (lp == null) return null;

        return new L2capNetwork(
                logTag, handler, context, provider, socket, tunFd, nc, lp, deps, cb);
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
