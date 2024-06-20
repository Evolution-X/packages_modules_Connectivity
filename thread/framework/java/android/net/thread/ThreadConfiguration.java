/*
 * Copyright 2024 The Android Open Source Project
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
package android.net.thread;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data interface for Thread device configuration.
 *
 * <p>An example usage of creating a {@link ThreadConfiguration} that turns on NAT64 feature based
 * on an existing {@link ThreadConfiguration}:
 *
 * <pre>{@code
 * ThreadConfiguration config =
 *     new ThreadConfiguration.Builder(existingConfig).setNat64Enabled(true).build();
 * }</pre>
 *
 * @see ThreadNetworkController#setConfiguration
 * @see ThreadNetworkController#registerConfigurationCallback
 * @see ThreadNetworkController#unregisterConfigurationCallback
 * @hide
 */
// @FlaggedApi(ThreadNetworkFlags.FLAG_CONFIGURATION_ENABLED)
// @SystemApi
public final class ThreadConfiguration implements Parcelable {
    private final boolean mNat64Enabled;
    private final boolean mDhcp6PdEnabled;

    private ThreadConfiguration(Builder builder) {
        this(builder.mNat64Enabled, builder.mDhcp6PdEnabled);
    }

    private ThreadConfiguration(boolean nat64Enabled, boolean dhcp6PdEnabled) {
        this.mNat64Enabled = nat64Enabled;
        this.mDhcp6PdEnabled = dhcp6PdEnabled;
    }

    /** Returns {@code true} if NAT64 is enabled. */
    public boolean isNat64Enabled() {
        return mNat64Enabled;
    }

    /** Returns {@code true} if DHCPv6 Prefix Delegation is enabled. */
    public boolean isDhcp6PdEnabled() {
        return mDhcp6PdEnabled;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof ThreadConfiguration)) {
            return false;
        } else {
            ThreadConfiguration otherConfig = (ThreadConfiguration) other;
            return mNat64Enabled == otherConfig.mNat64Enabled
                    && mDhcp6PdEnabled == otherConfig.mDhcp6PdEnabled;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNat64Enabled, mDhcp6PdEnabled);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("Nat64Enabled=").append(mNat64Enabled);
        sb.append(", Dhcp6PdEnabled=").append(mDhcp6PdEnabled);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mNat64Enabled);
        dest.writeBoolean(mDhcp6PdEnabled);
    }

    public static final @NonNull Creator<ThreadConfiguration> CREATOR =
            new Creator<>() {
                @Override
                public ThreadConfiguration createFromParcel(Parcel in) {
                    ThreadConfiguration.Builder builder = new ThreadConfiguration.Builder();
                    builder.setNat64Enabled(in.readBoolean());
                    builder.setDhcp6PdEnabled(in.readBoolean());
                    return builder.build();
                }

                @Override
                public ThreadConfiguration[] newArray(int size) {
                    return new ThreadConfiguration[size];
                }
            };

    /** The builder for creating {@link ThreadConfiguration} objects. */
    public static final class Builder {
        private boolean mNat64Enabled = false;
        private boolean mDhcp6PdEnabled = false;

        /** Creates a new {@link Builder} object with all features disabled. */
        public Builder() {}

        /**
         * Creates a new {@link Builder} object from a {@link ThreadConfiguration} object.
         *
         * @param config the Border Router configurations to be copied
         */
        public Builder(@NonNull ThreadConfiguration config) {
            Objects.requireNonNull(config);

            mNat64Enabled = config.mNat64Enabled;
            mDhcp6PdEnabled = config.mDhcp6PdEnabled;
        }

        /**
         * Enables or disables NAT64 for the device.
         *
         * <p>Enabling this feature will allow Thread devices to connect to the internet/cloud over
         * IPv4.
         */
        @NonNull
        public Builder setNat64Enabled(boolean enabled) {
            this.mNat64Enabled = enabled;
            return this;
        }

        /**
         * Enables or disables Prefix Delegation for the device.
         *
         * <p>Enabling this feature will allow Thread devices to connect to the internet/cloud over
         * IPv6.
         */
        @NonNull
        public Builder setDhcp6PdEnabled(boolean enabled) {
            this.mDhcp6PdEnabled = enabled;
            return this;
        }

        /** Creates a new {@link ThreadConfiguration} object. */
        @NonNull
        public ThreadConfiguration build() {
            return new ThreadConfiguration(this);
        }
    }
}
