/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.networkstack.tethering.metrics;

import static android.app.usage.NetworkStats.Bucket.STATE_ALL;
import static android.app.usage.NetworkStats.Bucket.TAG_NONE;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_LOWPAN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;
import static android.net.NetworkStats.DEFAULT_NETWORK_YES;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkStats.UID_TETHERING;
import static android.net.NetworkTemplate.MATCH_BLUETOOTH;
import static android.net.NetworkTemplate.MATCH_ETHERNET;
import static android.net.NetworkTemplate.MATCH_MOBILE;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_NCM;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHER_ERROR_DHCPSERVER_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_DISABLE_FORWARDING_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_ENABLE_FORWARDING_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_ENTITLEMENT_UNKNOWN;
import static android.net.TetheringManager.TETHER_ERROR_IFACE_CFG_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_PROVISIONING_FAILED;
import static android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL;
import static android.net.TetheringManager.TETHER_ERROR_TETHER_IFACE_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_UNAVAIL_IFACE;
import static android.net.TetheringManager.TETHER_ERROR_UNKNOWN_IFACE;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.net.TetheringManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;

import android.annotation.Nullable;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkTemplate;
import android.os.Handler;
import android.os.HandlerThread;
import android.stats.connectivity.DownstreamType;
import android.stats.connectivity.ErrorCode;
import android.stats.connectivity.UpstreamType;
import android.stats.connectivity.UserType;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.HandlerUtils;
import com.android.networkstack.tethering.UpstreamNetworkState;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Collection of utilities for tethering metrics.
 *
 *  <p>This class is thread-safe. All accesses to this class will be either posting to the internal
 *  handler thread for processing or checking whether the access is from the internal handler
 *  thread. However, the constructor is an exception, as it is called on another thread.
 *
 * To see if the logs are properly sent to statsd, execute following commands
 *
 * $ adb shell cmd stats print-logs
 * $ adb logcat | grep statsd OR $ adb logcat -b stats
 *
 * @hide
 */
public class TetheringMetrics {
    private static final String TAG = TetheringMetrics.class.getSimpleName();
    private static final boolean DBG = false;
    private static final String SETTINGS_PKG_NAME = "com.android.settings";
    private static final String SYSTEMUI_PKG_NAME = "com.android.systemui";
    private static final String GMS_PKG_NAME = "com.google.android.gms";
    /**
     * A feature flag to control whether upstream data usage metrics should be enabled.
     */
    private static final String TETHER_UPSTREAM_DATA_USAGE_METRICS =
            "tether_upstream_data_usage_metrics";
    @VisibleForTesting
    static final DataUsage EMPTY = new DataUsage(0L /* txBytes */, 0L /* rxBytes */);
    private final SparseArray<NetworkTetheringReported.Builder> mBuilderMap = new SparseArray<>();
    private final SparseArray<Long> mDownstreamStartTime = new SparseArray<Long>();
    private final ArrayList<RecordUpstreamEvent> mUpstreamEventList = new ArrayList<>();
    // Store the last reported data usage for each upstream type to be used for calculating the
    // usage delta. The keys are the upstream types, and the values are the tethering UID data
    // usage for the corresponding types. Retrieve the baseline data usage when tethering is
    // enabled, update it when the upstream changes, and clear it when tethering is disabled.
    private final ArrayMap<UpstreamType, DataUsage> mLastReportedUpstreamUsage = new ArrayMap<>();
    private final Context mContext;
    private final Dependencies mDependencies;
    private final NetworkStatsManager mNetworkStatsManager;
    private final Handler mHandler;
    private UpstreamType mCurrentUpstream = null;
    private Long mCurrentUpStreamStartTime = 0L;

    /**
     * Dependencies of TetheringMetrics, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see TetheringStatsLog
         */
        public void write(NetworkTetheringReported reported) {
            TetheringStatsLog.write(
                    TetheringStatsLog.NETWORK_TETHERING_REPORTED,
                    reported.getErrorCode().getNumber(),
                    reported.getDownstreamType().getNumber(),
                    reported.getUpstreamType().getNumber(),
                    reported.getUserType().getNumber(),
                    reported.getUpstreamEvents().toByteArray(),
                    reported.getDurationMillis());
        }

        /**
         * @see System#currentTimeMillis()
         */
        public long timeNow() {
            return System.currentTimeMillis();
        }

        /**
         * Indicates whether {@link #TETHER_UPSTREAM_DATA_USAGE_METRICS} is enabled.
         */
        public boolean isUpstreamDataUsageMetricsEnabled(Context context) {
            // Getting data usage requires building a NetworkTemplate. However, the
            // NetworkTemplate#Builder API was introduced in Android T.
            return SdkLevel.isAtLeastT() && DeviceConfigUtils.isTetheringFeatureNotChickenedOut(
                    context, TETHER_UPSTREAM_DATA_USAGE_METRICS);
        }

        /**
         * @see Handler
         *
         * Note: This should only be called once, within the constructor, as it creates a new
         * thread. Calling it multiple times could lead to a thread leak.
         */
        @NonNull
        public Handler createHandler() {
            final HandlerThread thread = new HandlerThread(TAG);
            thread.start();
            return new Handler(thread.getLooper());
        }
    }

    /**
     * Constructor for the TetheringMetrics class.
     *
     * @param context The Context object used to access system services.
     */
    public TetheringMetrics(Context context) {
        this(context, new Dependencies());
    }

    TetheringMetrics(Context context, Dependencies dependencies) {
        mContext = context;
        mDependencies = dependencies;
        mNetworkStatsManager = mContext.getSystemService(NetworkStatsManager.class);
        mHandler = dependencies.createHandler();
    }

    @VisibleForTesting
    static class DataUsage {
        public final long txBytes;
        public final long rxBytes;

        DataUsage(long txBytes, long rxBytes) {
            this.txBytes = txBytes;
            this.rxBytes = rxBytes;
        }

        /*** Calculate the data usage delta from give new and old usage */
        public static DataUsage subtract(DataUsage newUsage, DataUsage oldUsage) {
            return new DataUsage(
                    newUsage.txBytes - oldUsage.txBytes,
                    newUsage.rxBytes - oldUsage.rxBytes);
        }

        @Override
        public int hashCode() {
            return (int) (txBytes & 0xFFFFFFFF)
                    + ((int) (txBytes >> 32) * 3)
                    + ((int) (rxBytes & 0xFFFFFFFF) * 5)
                    + ((int) (rxBytes >> 32) * 7);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DataUsage)) {
                return false;
            }
            return txBytes == ((DataUsage) other).txBytes
                    && rxBytes == ((DataUsage) other).rxBytes;
        }

    }

    private static class RecordUpstreamEvent {
        final long mStartTime;
        final long mStopTime;
        final UpstreamType mUpstreamType;
        final DataUsage mDataUsage;

        RecordUpstreamEvent(final long startTime, final long stopTime,
                final UpstreamType upstream, final DataUsage dataUsage) {
            mStartTime = startTime;
            mStopTime = stopTime;
            mUpstreamType = upstream;
            mDataUsage = dataUsage;
        }
    }

    /**
     * Creates a |NetworkTetheringReported.Builder| object to update the tethering stats for the
     * specified downstream type and caller's package name. Initializes the upstream events, error
     * code, and duration to default values. Sets the start time for the downstream type in the
     * |mDownstreamStartTime| map.
     * @param downstreamType The type of downstream connection (e.g. Wifi, USB, Bluetooth).
     * @param callerPkg The package name of the caller.
     */
    public void createBuilder(final int downstreamType, final String callerPkg) {
        mHandler.post(() -> handleCreateBuilder(downstreamType, callerPkg));
    }

    private void handleCreateBuilder(final int downstreamType, final String callerPkg) {
        NetworkTetheringReported.Builder statsBuilder = NetworkTetheringReported.newBuilder()
                .setDownstreamType(downstreamTypeToEnum(downstreamType))
                .setUserType(userTypeToEnum(callerPkg))
                .setUpstreamType(UpstreamType.UT_UNKNOWN)
                .setErrorCode(ErrorCode.EC_NO_ERROR)
                .setUpstreamEvents(UpstreamEvents.newBuilder())
                .setDurationMillis(0);
        mBuilderMap.put(downstreamType, statsBuilder);
        mDownstreamStartTime.put(downstreamType, mDependencies.timeNow());
    }

    /**
     * Update the error code of the given downstream type in the Tethering stats.
     * @param downstreamType The downstream type whose error code to update.
     * @param errCode The error code to set.
     */
    public void updateErrorCode(final int downstreamType, final int errCode) {
        mHandler.post(() -> handleUpdateErrorCode(downstreamType, errCode));
    }

    private void handleUpdateErrorCode(final int downstreamType, final int errCode) {
        NetworkTetheringReported.Builder statsBuilder = mBuilderMap.get(downstreamType);
        if (statsBuilder == null) {
            Log.e(TAG, "Given downstreamType does not exist, this is a bug!");
            return;
        }
        statsBuilder.setErrorCode(errorCodeToEnum(errCode));
    }

    /**
     * Calculates the data usage difference between the current and previous usage for the
     * specified upstream type.
     *
     * Note: This must be called before updating mCurrentUpstream when changing the upstream.
     *
     * @return A DataUsage object containing the calculated difference in transmitted (tx) and
     *         received (rx) bytes.
     */
    private DataUsage calculateDataUsageDelta(@Nullable UpstreamType upstream) {
        if (!mDependencies.isUpstreamDataUsageMetricsEnabled(mContext)) {
            return EMPTY;
        }

        if (upstream == null || !isUsageSupportedForUpstreamType(upstream)) {
            return EMPTY;
        }

        final DataUsage oldUsage = mLastReportedUpstreamUsage.getOrDefault(upstream, EMPTY);
        if (oldUsage.equals(EMPTY)) {
            Log.d(TAG, "No usage baseline for the upstream=" + upstream);
            return EMPTY;
        }
        // TODO(b/370724247): Fix data usage which might be incorrect if the device uses
        //  tethering with the same upstream for over 15 days.
        // Need to refresh the baseline usage data. If the network switches back to Wi-Fi after
        // using cellular data (Wi-Fi -> Cellular -> Wi-Fi), the old baseline might be
        // inaccurate, leading to incorrect delta calculations.
        final DataUsage newUsage = getCurrentDataUsageForUpstreamType(upstream);
        mLastReportedUpstreamUsage.put(upstream, newUsage);
        return DataUsage.subtract(newUsage, oldUsage);
    }

    /**
     * Update the list of upstream types and their duration whenever the current upstream type
     * changes.
     * @param ns The UpstreamNetworkState object representing the current upstream network state.
     */
    public void maybeUpdateUpstreamType(@Nullable final UpstreamNetworkState ns) {
        mHandler.post(() -> handleMaybeUpdateUpstreamType(ns));
    }

    private void handleMaybeUpdateUpstreamType(@Nullable final UpstreamNetworkState ns) {
        UpstreamType upstream = transportTypeToUpstreamTypeEnum(ns);
        if (upstream.equals(mCurrentUpstream)) return;

        final long newTime = mDependencies.timeNow();
        if (mCurrentUpstream != null) {
            final DataUsage dataUsage = calculateDataUsageDelta(mCurrentUpstream);
            mUpstreamEventList.add(new RecordUpstreamEvent(mCurrentUpStreamStartTime, newTime,
                    mCurrentUpstream, dataUsage));
        }
        mCurrentUpstream = upstream;
        mCurrentUpStreamStartTime = newTime;
    }

    /**
     * Updates the upstream events builder with a new upstream event.
     * @param upstreamEventsBuilder the builder for the upstream events list
     * @param start the start time of the upstream event
     * @param stop the stop time of the upstream event
     * @param upstream the type of upstream type (e.g. Wifi, Cellular, Bluetooth, ...)
     */
    private void addUpstreamEvent(final UpstreamEvents.Builder upstreamEventsBuilder,
            final long start, final long stop, @Nullable final UpstreamType upstream,
            final long txBytes, final long rxBytes) {
        final UpstreamEvent.Builder upstreamEventBuilder = UpstreamEvent.newBuilder()
                .setUpstreamType(upstream == null ? UpstreamType.UT_NO_NETWORK : upstream)
                .setDurationMillis(stop - start)
                .setTxBytes(txBytes)
                .setRxBytes(rxBytes);
        upstreamEventsBuilder.addUpstreamEvent(upstreamEventBuilder);
    }

    /**
     * Updates the |NetworkTetheringReported.Builder| with relevant upstream events associated with
     * the downstream event identified by the given downstream start time.
     *
     * This method iterates through the list of upstream events and adds any relevant events to a
     * |UpstreamEvents.Builder|. Upstream events are considered relevant if their stop time is
     * greater than or equal to the given downstream start time. The method also adds the last
     * upstream event that occurred up until the current time.
     *
     * The resulting |UpstreamEvents.Builder| is then added to the
     * |NetworkTetheringReported.Builder|, along with the duration of the downstream event
     * (i.e., stop time minus downstream start time).
     *
     * @param statsBuilder the builder for the NetworkTetheringReported message
     * @param downstreamStartTime the start time of the downstream event to find relevant upstream
     * events for
     */
    private void noteDownstreamStopped(final NetworkTetheringReported.Builder statsBuilder,
                    final long downstreamStartTime) {
        UpstreamEvents.Builder upstreamEventsBuilder = UpstreamEvents.newBuilder();

        for (RecordUpstreamEvent event : mUpstreamEventList) {
            if (downstreamStartTime > event.mStopTime) continue;

            final long startTime = Math.max(downstreamStartTime, event.mStartTime);
            // Handle completed upstream events.
            addUpstreamEvent(upstreamEventsBuilder, startTime, event.mStopTime,
                    event.mUpstreamType, event.mDataUsage.txBytes, event.mDataUsage.rxBytes);
        }
        final long startTime = Math.max(downstreamStartTime, mCurrentUpStreamStartTime);
        final long stopTime = mDependencies.timeNow();
        // Handle the last upstream event.
        final DataUsage dataUsage = calculateDataUsageDelta(mCurrentUpstream);
        addUpstreamEvent(upstreamEventsBuilder, startTime, stopTime, mCurrentUpstream,
                dataUsage.txBytes, dataUsage.rxBytes);
        statsBuilder.setUpstreamEvents(upstreamEventsBuilder);
        statsBuilder.setDurationMillis(stopTime - downstreamStartTime);
    }

    /**
     * Removes tethering statistics for the given downstream type. If there are any stats to write
     * for the downstream event associated with the type, they are written before removing the
     * statistics.
     *
     * If the given downstream type does not exist in the map, an error message is logged and the
     * method returns without doing anything.
     *
     * @param downstreamType the type of downstream event to remove statistics for
     */
    public void sendReport(final int downstreamType) {
        mHandler.post(() -> handleSendReport(downstreamType));
    }

    private void handleSendReport(final int downstreamType) {
        final NetworkTetheringReported.Builder statsBuilder = mBuilderMap.get(downstreamType);
        if (statsBuilder == null) {
            Log.e(TAG, "Given downstreamType does not exist, this is a bug!");
            return;
        }

        noteDownstreamStopped(statsBuilder, mDownstreamStartTime.get(downstreamType));
        write(statsBuilder.build());

        mBuilderMap.remove(downstreamType);
        mDownstreamStartTime.remove(downstreamType);
    }

    /**
     * Collects tethering statistics and writes them to the statsd pipeline. This method takes in a
     * NetworkTetheringReported object, extracts its fields and uses them to write statistics data
     * to the statsd pipeline.
     *
     * @param reported a NetworkTetheringReported object containing statistics to write
     */
    private void write(@NonNull final NetworkTetheringReported reported) {
        mDependencies.write(reported);
        if (DBG) {
            Log.d(
                    TAG,
                    "Write errorCode: "
                    + reported.getErrorCode().getNumber()
                    + ", downstreamType: "
                    + reported.getDownstreamType().getNumber()
                    + ", upstreamType: "
                    + reported.getUpstreamType().getNumber()
                    + ", userType: "
                    + reported.getUserType().getNumber()
                    + ", upstreamTypes: "
                    + Arrays.toString(reported.getUpstreamEvents().toByteArray())
                    + ", durationMillis: "
                    + reported.getDurationMillis());
        }
    }

    /**
     * Initialize the upstream data usage baseline when tethering is turned on.
     */
    public void initUpstreamUsageBaseline() {
        mHandler.post(() -> handleInitUpstreamUsageBaseline());
    }

    private void handleInitUpstreamUsageBaseline() {
        if (!mDependencies.isUpstreamDataUsageMetricsEnabled(mContext)) {
            return;
        }

        if (!mLastReportedUpstreamUsage.isEmpty()) {
            Log.wtf(TAG, "The upstream usage baseline has been initialed.");
            return;
        }

        for (UpstreamType type : UpstreamType.values()) {
            if (!isUsageSupportedForUpstreamType(type)) continue;
            mLastReportedUpstreamUsage.put(type, getCurrentDataUsageForUpstreamType(type));
        }
    }

    @VisibleForTesting
    @NonNull
    DataUsage getLastReportedUsageFromUpstreamType(@NonNull UpstreamType type) {
        HandlerUtils.ensureRunningOnHandlerThread(mHandler);
        return mLastReportedUpstreamUsage.getOrDefault(type, EMPTY);
    }


    /**
     * Get the current usage for given upstream type.
     */
    @NonNull
    private DataUsage getCurrentDataUsageForUpstreamType(@NonNull UpstreamType type) {
        final NetworkStats stats = mNetworkStatsManager.queryDetailsForUidTagState(
                buildNetworkTemplateForUpstreamType(type), Long.MIN_VALUE, Long.MAX_VALUE,
                UID_TETHERING, TAG_NONE, STATE_ALL);

        final NetworkStats.Bucket bucket = new NetworkStats.Bucket();
        Long totalTxBytes = 0L;
        Long totalRxBytes = 0L;
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket);
            totalTxBytes += bucket.getTxBytes();
            totalRxBytes += bucket.getRxBytes();
        }
        return new DataUsage(totalTxBytes, totalRxBytes);
    }

    /**
     * Cleans up the variables related to upstream events when tethering is turned off.
     */
    public void cleanup() {
        mHandler.post(() -> handleCleanup());
    }

    private void handleCleanup() {
        mUpstreamEventList.clear();
        mCurrentUpstream = null;
        mCurrentUpStreamStartTime = 0L;
        mLastReportedUpstreamUsage.clear();
    }

    private DownstreamType downstreamTypeToEnum(final int ifaceType) {
        switch(ifaceType) {
            case TETHERING_WIFI:
                return DownstreamType.DS_TETHERING_WIFI;
            case TETHERING_WIFI_P2P:
                return DownstreamType.DS_TETHERING_WIFI_P2P;
            case TETHERING_USB:
                return DownstreamType.DS_TETHERING_USB;
            case TETHERING_BLUETOOTH:
                return DownstreamType.DS_TETHERING_BLUETOOTH;
            case TETHERING_NCM:
                return DownstreamType.DS_TETHERING_NCM;
            case TETHERING_ETHERNET:
                return DownstreamType.DS_TETHERING_ETHERNET;
            default:
                return DownstreamType.DS_UNSPECIFIED;
        }
    }

    private ErrorCode errorCodeToEnum(final int lastError) {
        switch(lastError) {
            case TETHER_ERROR_NO_ERROR:
                return ErrorCode.EC_NO_ERROR;
            case TETHER_ERROR_UNKNOWN_IFACE:
                return ErrorCode.EC_UNKNOWN_IFACE;
            case TETHER_ERROR_SERVICE_UNAVAIL:
                return ErrorCode.EC_SERVICE_UNAVAIL;
            case TETHER_ERROR_UNSUPPORTED:
                return ErrorCode.EC_UNSUPPORTED;
            case TETHER_ERROR_UNAVAIL_IFACE:
                return ErrorCode.EC_UNAVAIL_IFACE;
            case TETHER_ERROR_INTERNAL_ERROR:
                return ErrorCode.EC_INTERNAL_ERROR;
            case TETHER_ERROR_TETHER_IFACE_ERROR:
                return ErrorCode.EC_TETHER_IFACE_ERROR;
            case TETHER_ERROR_UNTETHER_IFACE_ERROR:
                return ErrorCode.EC_UNTETHER_IFACE_ERROR;
            case TETHER_ERROR_ENABLE_FORWARDING_ERROR:
                return ErrorCode.EC_ENABLE_FORWARDING_ERROR;
            case TETHER_ERROR_DISABLE_FORWARDING_ERROR:
                return ErrorCode.EC_DISABLE_FORWARDING_ERROR;
            case TETHER_ERROR_IFACE_CFG_ERROR:
                return ErrorCode.EC_IFACE_CFG_ERROR;
            case TETHER_ERROR_PROVISIONING_FAILED:
                return ErrorCode.EC_PROVISIONING_FAILED;
            case TETHER_ERROR_DHCPSERVER_ERROR:
                return ErrorCode.EC_DHCPSERVER_ERROR;
            case TETHER_ERROR_ENTITLEMENT_UNKNOWN:
                return ErrorCode.EC_ENTITLEMENT_UNKNOWN;
            case TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION:
                return ErrorCode.EC_NO_CHANGE_TETHERING_PERMISSION;
            case TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION:
                return ErrorCode.EC_NO_ACCESS_TETHERING_PERMISSION;
            default:
                return ErrorCode.EC_UNKNOWN_TYPE;
        }
    }

    private UserType userTypeToEnum(final String callerPkg) {
        if (callerPkg.equals(SETTINGS_PKG_NAME)) {
            return UserType.USER_SETTINGS;
        } else if (callerPkg.equals(SYSTEMUI_PKG_NAME)) {
            return UserType.USER_SYSTEMUI;
        } else if (callerPkg.equals(GMS_PKG_NAME)) {
            return UserType.USER_GMS;
        } else {
            return UserType.USER_UNKNOWN;
        }
    }

    private UpstreamType transportTypeToUpstreamTypeEnum(final UpstreamNetworkState ns) {
        final NetworkCapabilities nc = (ns != null) ? ns.networkCapabilities : null;
        if (nc == null) return UpstreamType.UT_NO_NETWORK;

        final int typeCount = nc.getTransportTypes().length;
        // It's possible for a VCN network to be mapped to UT_UNKNOWN, as it may consist of both
        // Wi-Fi and cellular transport.
        // TODO: It's necessary to define a new upstream type for VCN, which can be identified by
        // NET_CAPABILITY_NOT_VCN_MANAGED.
        if (typeCount > 1) return UpstreamType.UT_UNKNOWN;

        if (nc.hasTransport(TRANSPORT_CELLULAR)) return UpstreamType.UT_CELLULAR;
        if (nc.hasTransport(TRANSPORT_WIFI)) return UpstreamType.UT_WIFI;
        if (nc.hasTransport(TRANSPORT_BLUETOOTH)) return UpstreamType.UT_BLUETOOTH;
        if (nc.hasTransport(TRANSPORT_ETHERNET)) return UpstreamType.UT_ETHERNET;
        if (nc.hasTransport(TRANSPORT_WIFI_AWARE)) return UpstreamType.UT_WIFI_AWARE;
        if (nc.hasTransport(TRANSPORT_LOWPAN)) return UpstreamType.UT_LOWPAN;

        return UpstreamType.UT_UNKNOWN;
    }

    /**
     * Check whether tethering metrics' data usage can be collected for a given upstream type.
     *
     * @param type the upstream type
     */
    public static boolean isUsageSupportedForUpstreamType(@NonNull UpstreamType type) {
        switch(type) {
            case UT_CELLULAR:
            case UT_WIFI:
            case UT_BLUETOOTH:
            case UT_ETHERNET:
                return true;
            default:
                break;
        }
        return false;
    }

    /**
     * Build NetworkTemplate for the given upstream type.
     *
     * <p> NetworkTemplate.Builder API was introduced in Android T.
     *
     * @param type the upstream type
     * @return A NetworkTemplate object with a corresponding match rule or null if tethering
     * metrics' data usage cannot be collected for a given upstream type.
     */
    @Nullable
    public static NetworkTemplate buildNetworkTemplateForUpstreamType(@NonNull UpstreamType type) {
        if (!isUsageSupportedForUpstreamType(type)) return null;

        switch (type) {
            case UT_CELLULAR:
                // TODO: Handle the DUN connection, which is not a default network.
                return new NetworkTemplate.Builder(MATCH_MOBILE)
                        .setMeteredness(METERED_YES)
                        .setDefaultNetworkStatus(DEFAULT_NETWORK_YES)
                        .build();
            case UT_WIFI:
                return new NetworkTemplate.Builder(MATCH_WIFI)
                        .setMeteredness(METERED_YES)
                        .setDefaultNetworkStatus(DEFAULT_NETWORK_YES)
                        .build();
            case UT_BLUETOOTH:
                return new NetworkTemplate.Builder(MATCH_BLUETOOTH)
                        .setMeteredness(METERED_YES)
                        .setDefaultNetworkStatus(DEFAULT_NETWORK_YES)
                        .build();
            case UT_ETHERNET:
                return new NetworkTemplate.Builder(MATCH_ETHERNET)
                        .setMeteredness(METERED_YES)
                        .setDefaultNetworkStatus(DEFAULT_NETWORK_YES)
                        .build();
            default:
                Log.e(TAG, "Unsupported UpstreamType: " + type.name());
                break;
        }
        return null;
    }
}
