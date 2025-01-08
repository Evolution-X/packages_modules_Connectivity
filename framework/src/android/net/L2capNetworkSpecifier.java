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

package android.net;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.net.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A {@link NetworkSpecifier} used to identify an L2CAP network.
 *
 * An L2CAP network is not symmetrical, meaning there exists both a server (Bluetooth peripheral)
 * and a client (Bluetooth central) node. This specifier contains information required to request or
 * reserve an L2CAP network.
 *
 * An L2CAP server network allocates a PSM to be advertised to the client. Therefore, the server
 * network must always be reserved using {@link ConnectivityManager#reserveNetwork}. The subsequent
 * {@link ConnectivityManager.NetworkCallback#onReserved(NetworkCapabilities)} includes information
 * (i.e. the PSM) for the server to advertise to the client.
 * Under the hood, an L2CAP server network is represented by a {@link
 * android.bluetooth.BluetoothServerSocket} which can, in theory, accept many connections. However,
 * before Android 15 Bluetooth APIs do not expose the channel ID, so these connections are
 * indistinguishable. In practice, this means that network matching semantics in {@link
 * ConnectivityService} will tear down all but the first connection.
 *
 * The L2cap client network can be connected using {@link ConnectivityManager#requestNetwork}
 * including passing in the relevant information (i.e. PSM and destination MAC address) using the
 * {@link L2capNetworkSpecifier}.
 *
 */
@FlaggedApi(Flags.FLAG_IPV6_OVER_BLE)
public final class L2capNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    /** Accept any role. */
    public static final int ROLE_ANY = 0;
    /** Specifier describes a client network. */
    public static final int ROLE_CLIENT = 1;
    /** Specifier describes a server network. */
    public static final int ROLE_SERVER = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "ROLE_", value = {
        ROLE_ANY,
        ROLE_CLIENT,
        ROLE_SERVER
    })
    public @interface Role {}
    /** Role used to distinguish client from server networks. */
    @Role
    private final int mRole;

    /** Accept any form of header compression. */
    public static final int HEADER_COMPRESSION_ANY = 0;
    /** Do not compress packets on this network. */
    public static final int HEADER_COMPRESSION_NONE = 1;
    /** Use 6lowpan header compression as specified in rfc6282. */
    public static final int HEADER_COMPRESSION_6LOWPAN = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "HEADER_COMPRESSION_", value = {
        HEADER_COMPRESSION_ANY,
        HEADER_COMPRESSION_NONE,
        HEADER_COMPRESSION_6LOWPAN
    })
    public @interface HeaderCompression {}
    /** Header compression mechanism used on this network. */
    @HeaderCompression
    private final int mHeaderCompression;

    /**
     *  The MAC address of the remote.
     */
    @Nullable
    private final MacAddress mRemoteAddress;

    /** Match any PSM. */
    public static final int PSM_ANY = -1;

    /** The Bluetooth L2CAP Protocol Service Multiplexer (PSM). */
    private final int mPsm;

    private L2capNetworkSpecifier(Parcel in) {
        mRole = in.readInt();
        mHeaderCompression = in.readInt();
        mRemoteAddress = in.readParcelable(getClass().getClassLoader());
        mPsm = in.readInt();
    }

    /** @hide */
    public L2capNetworkSpecifier(@Role int role, @HeaderCompression int headerCompression,
            MacAddress remoteAddress, int psm) {
        mRole = role;
        mHeaderCompression = headerCompression;
        mRemoteAddress = remoteAddress;
        mPsm = psm;
    }

    /** Returns the role to be used for this network. */
    @Role
    public int getRole() {
        return mRole;
    }

    /** Returns the compression mechanism for this network. */
    @HeaderCompression
    public int getHeaderCompression() {
        return mHeaderCompression;
    }

    /** Returns the remote MAC address for this network to connect to. */
    public @Nullable MacAddress getRemoteAddress() {
        return mRemoteAddress;
    }

    /** Returns the PSM for this network to connect to. */
    public int getPsm() {
        return mPsm;
    }

    /** A builder class for L2capNetworkSpecifier. */
    public static final class Builder {
        @Role
        private int mRole;
        @HeaderCompression
        private int mHeaderCompression;
        @Nullable
        private MacAddress mRemoteAddress;
        private int mPsm = PSM_ANY;

        /**
         * Set the role to use for this network.
         *
         * @param role the role to use.
         */
        @NonNull
        public Builder setRole(@Role int role) {
            mRole = role;
            return this;
        }

        /**
         * Set the header compression mechanism to use for this network.
         *
         * @param headerCompression the header compression mechanism to use.
         */
        @NonNull
        public Builder setHeaderCompression(@HeaderCompression int headerCompression) {
            mHeaderCompression = headerCompression;
            return this;
        }

        /**
         * Set the remote address for the client to connect to.
         *
         * Only valid for client networks. A null MacAddress matches *any* MacAddress.
         *
         * @param remoteAddress the MAC address to connect to, or null to match any MAC address.
         */
        @NonNull
        public Builder setRemoteAddress(@Nullable MacAddress remoteAddress) {
            mRemoteAddress = remoteAddress;
            return this;
        }

        /**
         * Set the PSM for the client to connect to.
         *
         * Can only be configured on client networks.
         *
         * @param psm the Protocol Service Multiplexer (PSM) to connect to.
         */
        @NonNull
        public Builder setPsm(int psm) {
            mPsm = psm;
            return this;
        }

        /** Create the L2capNetworkSpecifier object. */
        @NonNull
        public L2capNetworkSpecifier build() {
            // TODO: throw an exception for combinations that cannot be supported.
            return new L2capNetworkSpecifier(mRole, mHeaderCompression, mRemoteAddress, mPsm);
        }
    }

    /** @hide */
    @Override
    public boolean canBeSatisfiedBy(NetworkSpecifier other) {
        // TODO: implement matching semantics.
        return false;
    }

    /** @hide */
    @Override
    @Nullable
    public NetworkSpecifier redact() {
        // Redact the remote MAC address and the PSM (for non-server roles).
        final NetworkSpecifier redactedSpecifier = new Builder()
                .setRole(mRole)
                .setHeaderCompression(mHeaderCompression)
                // TODO: consider not redacting the specifier in onReserved, so the redaction can be
                // more strict (i.e. the PSM could always be redacted).
                .setPsm(mRole == ROLE_SERVER ? mPsm : PSM_ANY)
                .build();
        return redactedSpecifier;
    }

    /** @hide */
    @Override
    public int hashCode() {
        return Objects.hash(mRole, mHeaderCompression, mRemoteAddress, mPsm);
    }

    /** @hide */
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof L2capNetworkSpecifier)) return false;

        final L2capNetworkSpecifier rhs = (L2capNetworkSpecifier) obj;
        return mRole == rhs.mRole
                && mHeaderCompression == rhs.mHeaderCompression
                && Objects.equals(mRemoteAddress, rhs.mRemoteAddress)
                && mPsm == rhs.mPsm;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRole);
        dest.writeInt(mHeaderCompression);
        dest.writeParcelable(mRemoteAddress, flags);
        dest.writeInt(mPsm);
    }

    public static final @NonNull Creator<L2capNetworkSpecifier> CREATOR = new Creator<>() {
        @Override
        public L2capNetworkSpecifier createFromParcel(Parcel in) {
            return new L2capNetworkSpecifier(in);
        }

        @Override
        public L2capNetworkSpecifier[] newArray(int size) {
            return new L2capNetworkSpecifier[size];
        }
    };
}
