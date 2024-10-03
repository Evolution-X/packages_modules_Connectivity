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
import static android.net.NetworkStats.METERED_NO;
import static android.net.NetworkStats.ROAMING_NO;
import static android.net.NetworkStats.SET_DEFAULT;
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
import static android.net.TetheringManager.TETHER_ERROR_UNKNOWN_TYPE;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.net.TetheringManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;
import static android.stats.connectivity.UpstreamType.UT_BLUETOOTH;
import static android.stats.connectivity.UpstreamType.UT_CELLULAR;
import static android.stats.connectivity.UpstreamType.UT_ETHERNET;
import static android.stats.connectivity.UpstreamType.UT_WIFI;

import static com.android.networkstack.tethering.metrics.TetheringMetrics.EMPTY;
import static com.android.testutils.NetworkStatsUtilsKt.makePublicStatsFromAndroidNetStats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.stats.connectivity.DownstreamType;
import android.stats.connectivity.ErrorCode;
import android.stats.connectivity.UpstreamType;
import android.stats.connectivity.UserType;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.networkstack.tethering.UpstreamNetworkState;
import com.android.networkstack.tethering.metrics.TetheringMetrics.DataUsage;
import com.android.networkstack.tethering.metrics.TetheringMetrics.Dependencies;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
public final class TetheringMetricsTest {
    @Rule public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final String TEST_CALLER_PKG = "com.test.caller.pkg";
    private static final String SETTINGS_PKG = "com.android.settings";
    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private static final String GMS_PKG = "com.google.android.gms";
    private static final long TEST_START_TIME = 1670395936033L;
    private static final long SECOND_IN_MILLIS = 1_000L;
    private static final long DEFAULT_TIMEOUT = 2000L;
    private static final int MATCH_NONE = -1;

    @Mock private Context mContext;
    @Mock private Dependencies mDeps;
    @Mock private NetworkStatsManager mNetworkStatsManager;

    private TetheringMetrics mTetheringMetrics;
    private final NetworkTetheringReported.Builder mStatsBuilder =
            NetworkTetheringReported.newBuilder();
    private final ArrayMap<UpstreamType, DataUsage> mMockUpstreamUsageBaseline = new ArrayMap<>();
    private HandlerThread mThread;
    private Handler mHandler;

    private long mElapsedRealtime;

    private long currentTimeMillis() {
        return TEST_START_TIME + mElapsedRealtime;
    }

    private void incrementCurrentTime(final long duration) {
        mElapsedRealtime += duration;
        final long currentTimeMillis = currentTimeMillis();
        doReturn(currentTimeMillis).when(mDeps).timeNow();
    }

    private long getElapsedRealtime() {
        return mElapsedRealtime;
    }

    private void clearElapsedRealtime() {
        mElapsedRealtime = 0;
        doReturn(TEST_START_TIME).when(mDeps).timeNow();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(TEST_START_TIME).when(mDeps).timeNow();
        doReturn(mNetworkStatsManager).when(mContext).getSystemService(NetworkStatsManager.class);
        mThread = new HandlerThread("TetheringMetricsTest");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        doReturn(mHandler).when(mDeps).createHandler();
        // Set up the usage for upstream types.
        mMockUpstreamUsageBaseline.put(UT_CELLULAR, new DataUsage(100L, 200L));
        mMockUpstreamUsageBaseline.put(UT_WIFI, new DataUsage(400L, 800L));
        mMockUpstreamUsageBaseline.put(UT_BLUETOOTH, new DataUsage(50L, 80L));
        mMockUpstreamUsageBaseline.put(UT_ETHERNET, new DataUsage(0L, 0L));
        doAnswer(inv -> {
            final NetworkTemplate template = (NetworkTemplate) inv.getArguments()[0];
            final DataUsage dataUsage = mMockUpstreamUsageBaseline.getOrDefault(
                    matchRuleToUpstreamType(template.getMatchRule()), new DataUsage(0L, 0L));
            return makeNetworkStatsWithTxRxBytes(dataUsage);
        }).when(mNetworkStatsManager).queryDetailsForUidTagState(any(), eq(Long.MIN_VALUE),
                eq(Long.MAX_VALUE), eq(UID_TETHERING), eq(TAG_NONE), eq(STATE_ALL));
        mTetheringMetrics = new TetheringMetrics(mContext, mDeps);
        mElapsedRealtime = 0L;
    }

    @After
    public void tearDown() throws Exception {
        if (mThread != null) {
            mThread.quitSafely();
            mThread.join();
        }
    }

    private void verifyReport(final DownstreamType downstream, final ErrorCode error,
            final UserType user, final UpstreamEvents.Builder upstreamEvents, final long duration)
            throws Exception {
        final NetworkTetheringReported expectedReport =
                mStatsBuilder.setDownstreamType(downstream)
                .setUserType(user)
                .setUpstreamType(UpstreamType.UT_UNKNOWN)
                .setErrorCode(error)
                .setUpstreamEvents(upstreamEvents)
                .setDurationMillis(duration)
                .build();
        verify(mDeps).write(expectedReport);
    }

    private void runAndWaitForIdle(Runnable r) {
        r.run();
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
    }

    private void updateErrorAndSendReport(final int downstream, final int error) {
        mTetheringMetrics.updateErrorCode(downstream, error);
        mTetheringMetrics.sendReport(downstream);
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
    }

    private static NetworkCapabilities buildUpstreamCapabilities(final int[] transports) {
        final NetworkCapabilities nc = new NetworkCapabilities();
        for (int type: transports) {
            nc.addTransportType(type);
        }
        return nc;
    }

    private static UpstreamNetworkState buildUpstreamState(final int... transports) {
        return new UpstreamNetworkState(
                null,
                buildUpstreamCapabilities(transports),
                null);
    }

    private void addUpstreamEvent(UpstreamEvents.Builder upstreamEvents,
            final UpstreamType expectedResult, final long duration, final long txBytes,
                    final long rxBytes) {
        UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                .setUpstreamType(expectedResult)
                .setDurationMillis(duration)
                .setTxBytes(txBytes)
                .setRxBytes(rxBytes);
        upstreamEvents.addUpstreamEvent(upstreamEvent);
    }

    private void runDownstreamTypesTest(final int type, final DownstreamType expectedResult)
            throws Exception {
        mTetheringMetrics = new TetheringMetrics(mContext, mDeps);
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(type, TEST_CALLER_PKG));
        final long duration = 2 * SECOND_IN_MILLIS;
        incrementCurrentTime(duration);
        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder();
        // Set UpstreamType as NO_NETWORK because the upstream type has not been changed.
        addUpstreamEvent(upstreamEvents, UpstreamType.UT_NO_NETWORK, duration, 0L, 0L);
        updateErrorAndSendReport(type, TETHER_ERROR_NO_ERROR);

        verifyReport(expectedResult, ErrorCode.EC_NO_ERROR, UserType.USER_UNKNOWN,
                upstreamEvents, getElapsedRealtime());
        clearElapsedRealtime();
    }

    @Test
    public void testDownstreamTypes() throws Exception {
        runDownstreamTypesTest(TETHERING_WIFI, DownstreamType.DS_TETHERING_WIFI);
        runDownstreamTypesTest(TETHERING_WIFI_P2P, DownstreamType.DS_TETHERING_WIFI_P2P);
        runDownstreamTypesTest(TETHERING_BLUETOOTH, DownstreamType.DS_TETHERING_BLUETOOTH);
        runDownstreamTypesTest(TETHERING_USB, DownstreamType.DS_TETHERING_USB);
        runDownstreamTypesTest(TETHERING_NCM, DownstreamType.DS_TETHERING_NCM);
        runDownstreamTypesTest(TETHERING_ETHERNET, DownstreamType.DS_TETHERING_ETHERNET);
    }

    private void runErrorCodesTest(final int errorCode, final ErrorCode expectedResult)
            throws Exception {
        mTetheringMetrics = new TetheringMetrics(mContext, mDeps);
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_WIFI, TEST_CALLER_PKG));
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI)));
        final long duration = 2 * SECOND_IN_MILLIS;
        incrementCurrentTime(duration);
        updateErrorAndSendReport(TETHERING_WIFI, errorCode);

        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(upstreamEvents, UT_WIFI, duration, 0L, 0L);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, expectedResult, UserType.USER_UNKNOWN,
                    upstreamEvents, getElapsedRealtime());
        clearElapsedRealtime();
    }

    @Test
    public void testErrorCodes() throws Exception {
        runErrorCodesTest(TETHER_ERROR_NO_ERROR, ErrorCode.EC_NO_ERROR);
        runErrorCodesTest(TETHER_ERROR_UNKNOWN_IFACE, ErrorCode.EC_UNKNOWN_IFACE);
        runErrorCodesTest(TETHER_ERROR_SERVICE_UNAVAIL, ErrorCode.EC_SERVICE_UNAVAIL);
        runErrorCodesTest(TETHER_ERROR_UNSUPPORTED, ErrorCode.EC_UNSUPPORTED);
        runErrorCodesTest(TETHER_ERROR_UNAVAIL_IFACE, ErrorCode.EC_UNAVAIL_IFACE);
        runErrorCodesTest(TETHER_ERROR_INTERNAL_ERROR, ErrorCode.EC_INTERNAL_ERROR);
        runErrorCodesTest(TETHER_ERROR_TETHER_IFACE_ERROR, ErrorCode.EC_TETHER_IFACE_ERROR);
        runErrorCodesTest(TETHER_ERROR_UNTETHER_IFACE_ERROR, ErrorCode.EC_UNTETHER_IFACE_ERROR);
        runErrorCodesTest(TETHER_ERROR_ENABLE_FORWARDING_ERROR,
                ErrorCode.EC_ENABLE_FORWARDING_ERROR);
        runErrorCodesTest(TETHER_ERROR_DISABLE_FORWARDING_ERROR,
                ErrorCode.EC_DISABLE_FORWARDING_ERROR);
        runErrorCodesTest(TETHER_ERROR_IFACE_CFG_ERROR, ErrorCode.EC_IFACE_CFG_ERROR);
        runErrorCodesTest(TETHER_ERROR_PROVISIONING_FAILED, ErrorCode.EC_PROVISIONING_FAILED);
        runErrorCodesTest(TETHER_ERROR_DHCPSERVER_ERROR, ErrorCode.EC_DHCPSERVER_ERROR);
        runErrorCodesTest(TETHER_ERROR_ENTITLEMENT_UNKNOWN, ErrorCode.EC_ENTITLEMENT_UNKNOWN);
        runErrorCodesTest(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION,
                ErrorCode.EC_NO_CHANGE_TETHERING_PERMISSION);
        runErrorCodesTest(TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION,
                ErrorCode.EC_NO_ACCESS_TETHERING_PERMISSION);
        runErrorCodesTest(TETHER_ERROR_UNKNOWN_TYPE, ErrorCode.EC_UNKNOWN_TYPE);
    }

    private void runUserTypesTest(final String callerPkg, final UserType expectedResult)
            throws Exception {
        mTetheringMetrics = new TetheringMetrics(mContext, mDeps);
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_WIFI, callerPkg));
        final long duration = 1 * SECOND_IN_MILLIS;
        incrementCurrentTime(duration);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder();
        // Set UpstreamType as NO_NETWORK because the upstream type has not been changed.
        addUpstreamEvent(upstreamEvents, UpstreamType.UT_NO_NETWORK, duration, 0L, 0L);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR, expectedResult,
                    upstreamEvents, getElapsedRealtime());
        clearElapsedRealtime();
    }

    @Test
    public void testUserTypes() throws Exception {
        runUserTypesTest(TEST_CALLER_PKG, UserType.USER_UNKNOWN);
        runUserTypesTest(SETTINGS_PKG, UserType.USER_SETTINGS);
        runUserTypesTest(SYSTEMUI_PKG, UserType.USER_SYSTEMUI);
        runUserTypesTest(GMS_PKG, UserType.USER_GMS);
    }

    private void runUpstreamTypesTest(final UpstreamNetworkState ns,
            final UpstreamType expectedResult) throws Exception {
        mTetheringMetrics = new TetheringMetrics(mContext, mDeps);
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_WIFI, TEST_CALLER_PKG));
        runAndWaitForIdle(() -> mTetheringMetrics.maybeUpdateUpstreamType(ns));
        final long duration = 2 * SECOND_IN_MILLIS;
        incrementCurrentTime(duration);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(upstreamEvents, expectedResult, duration, 0L, 0L);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                UserType.USER_UNKNOWN, upstreamEvents, getElapsedRealtime());
        clearElapsedRealtime();
    }

    @Test
    public void testUpstreamTypes() throws Exception {
        runUpstreamTypesTest(null , UpstreamType.UT_NO_NETWORK);
        runUpstreamTypesTest(buildUpstreamState(TRANSPORT_CELLULAR), UT_CELLULAR);
        runUpstreamTypesTest(buildUpstreamState(TRANSPORT_WIFI), UT_WIFI);
        runUpstreamTypesTest(buildUpstreamState(TRANSPORT_BLUETOOTH), UT_BLUETOOTH);
        runUpstreamTypesTest(buildUpstreamState(TRANSPORT_ETHERNET), UT_ETHERNET);
        runUpstreamTypesTest(buildUpstreamState(TRANSPORT_WIFI_AWARE), UpstreamType.UT_WIFI_AWARE);
        runUpstreamTypesTest(buildUpstreamState(TRANSPORT_LOWPAN), UpstreamType.UT_LOWPAN);
        runUpstreamTypesTest(buildUpstreamState(TRANSPORT_CELLULAR, TRANSPORT_WIFI,
                TRANSPORT_BLUETOOTH), UpstreamType.UT_UNKNOWN);
    }

    @Test
    public void testMultiBuildersCreatedBeforeSendReport() throws Exception {
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG));
        final long wifiTetheringStartTime = currentTimeMillis();
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_USB, SYSTEMUI_PKG));
        final long usbTetheringStartTime = currentTimeMillis();
        incrementCurrentTime(2 * SECOND_IN_MILLIS);
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_BLUETOOTH, GMS_PKG));
        final long bluetoothTetheringStartTime = currentTimeMillis();
        incrementCurrentTime(3 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_DHCPSERVER_ERROR);

        UpstreamEvents.Builder wifiTetheringUpstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(wifiTetheringUpstreamEvents, UpstreamType.UT_NO_NETWORK,
                currentTimeMillis() - wifiTetheringStartTime, 0L, 0L);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_DHCPSERVER_ERROR,
                UserType.USER_SETTINGS, wifiTetheringUpstreamEvents,
                currentTimeMillis() - wifiTetheringStartTime);
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_USB, TETHER_ERROR_ENABLE_FORWARDING_ERROR);

        UpstreamEvents.Builder usbTetheringUpstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(usbTetheringUpstreamEvents, UpstreamType.UT_NO_NETWORK,
                currentTimeMillis() - usbTetheringStartTime, 0L, 0L);

        verifyReport(DownstreamType.DS_TETHERING_USB, ErrorCode.EC_ENABLE_FORWARDING_ERROR,
                UserType.USER_SYSTEMUI, usbTetheringUpstreamEvents,
                currentTimeMillis() - usbTetheringStartTime);
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_BLUETOOTH, TETHER_ERROR_TETHER_IFACE_ERROR);

        UpstreamEvents.Builder bluetoothTetheringUpstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(bluetoothTetheringUpstreamEvents, UpstreamType.UT_NO_NETWORK,
                currentTimeMillis() - bluetoothTetheringStartTime, 0L, 0L);
        verifyReport(DownstreamType.DS_TETHERING_BLUETOOTH, ErrorCode.EC_TETHER_IFACE_ERROR,
                UserType.USER_GMS, bluetoothTetheringUpstreamEvents,
                currentTimeMillis() - bluetoothTetheringStartTime);
    }

    @Test
    public void testUpstreamsWithMultipleDownstreams() throws Exception {
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG));
        final long wifiTetheringStartTime = currentTimeMillis();
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI)));
        final long wifiUpstreamStartTime = currentTimeMillis();
        incrementCurrentTime(5 * SECOND_IN_MILLIS);
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_USB, SYSTEMUI_PKG));
        final long usbTetheringStartTime = currentTimeMillis();
        incrementCurrentTime(5 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_USB, TETHER_ERROR_NO_ERROR);

        UpstreamEvents.Builder usbTetheringUpstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(usbTetheringUpstreamEvents, UT_WIFI,
                currentTimeMillis() - usbTetheringStartTime, 0L, 0L);
        verifyReport(DownstreamType.DS_TETHERING_USB, ErrorCode.EC_NO_ERROR,
                UserType.USER_SYSTEMUI, usbTetheringUpstreamEvents,
                currentTimeMillis() - usbTetheringStartTime);
        incrementCurrentTime(7 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        UpstreamEvents.Builder wifiTetheringUpstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(wifiTetheringUpstreamEvents, UT_WIFI,
                currentTimeMillis() - wifiUpstreamStartTime, 0L, 0L);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                UserType.USER_SETTINGS, wifiTetheringUpstreamEvents,
                currentTimeMillis() - wifiTetheringStartTime);
    }

    @Test
    public void testSwitchingMultiUpstreams() throws Exception {
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG));
        final long wifiTetheringStartTime = currentTimeMillis();
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI)));
        final long wifiDuration = 5 * SECOND_IN_MILLIS;
        incrementCurrentTime(wifiDuration);
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_BLUETOOTH)));
        final long bluetoothDuration = 15 * SECOND_IN_MILLIS;
        incrementCurrentTime(bluetoothDuration);
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_CELLULAR)));
        final long celltoothDuration = 20 * SECOND_IN_MILLIS;
        incrementCurrentTime(celltoothDuration);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(upstreamEvents, UT_WIFI, wifiDuration, 0L, 0L);
        addUpstreamEvent(upstreamEvents, UT_BLUETOOTH, bluetoothDuration, 0L, 0L);
        addUpstreamEvent(upstreamEvents, UT_CELLULAR, celltoothDuration, 0L, 0L);

        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                UserType.USER_SETTINGS, upstreamEvents,
                currentTimeMillis() - wifiTetheringStartTime);
    }

    private void runUsageSupportedForUpstreamTypeTest(final UpstreamType upstreamType,
            final boolean isSupported) {
        final boolean result = TetheringMetrics.isUsageSupportedForUpstreamType(upstreamType);
        assertEquals(isSupported, result);
    }

    @Test
    public void testUsageSupportedForUpstreamTypeTest() {
        runUsageSupportedForUpstreamTypeTest(UT_CELLULAR, true /* isSupported */);
        runUsageSupportedForUpstreamTypeTest(UT_WIFI, true /* isSupported */);
        runUsageSupportedForUpstreamTypeTest(UT_BLUETOOTH, true /* isSupported */);
        runUsageSupportedForUpstreamTypeTest(UT_ETHERNET, true /* isSupported */);
        runUsageSupportedForUpstreamTypeTest(UpstreamType.UT_WIFI_AWARE, false /* isSupported */);
        runUsageSupportedForUpstreamTypeTest(UpstreamType.UT_LOWPAN, false /* isSupported */);
        runUsageSupportedForUpstreamTypeTest(UpstreamType.UT_UNKNOWN, false /* isSupported */);
    }

    private void runBuildNetworkTemplateForUpstreamType(final UpstreamType upstreamType,
            final int matchRule)  {
        final NetworkTemplate template =
                TetheringMetrics.buildNetworkTemplateForUpstreamType(upstreamType);
        if (matchRule == MATCH_NONE) {
            assertNull(template);
        } else {
            assertEquals(matchRule, template.getMatchRule());
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testBuildNetworkTemplateForUpstreamType() {
        runBuildNetworkTemplateForUpstreamType(UT_CELLULAR, MATCH_MOBILE);
        runBuildNetworkTemplateForUpstreamType(UT_WIFI, MATCH_WIFI);
        runBuildNetworkTemplateForUpstreamType(UT_BLUETOOTH, MATCH_BLUETOOTH);
        runBuildNetworkTemplateForUpstreamType(UT_ETHERNET, MATCH_ETHERNET);
        runBuildNetworkTemplateForUpstreamType(UpstreamType.UT_WIFI_AWARE, MATCH_NONE);
        runBuildNetworkTemplateForUpstreamType(UpstreamType.UT_LOWPAN, MATCH_NONE);
        runBuildNetworkTemplateForUpstreamType(UpstreamType.UT_UNKNOWN, MATCH_NONE);
    }

    private void verifyEmptyUsageForAllUpstreamTypes() {
        mHandler.post(() -> {
            for (UpstreamType type : UpstreamType.values()) {
                assertEquals(EMPTY, mTetheringMetrics.getLastReportedUsageFromUpstreamType(type));
            }
        });
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
    }

    @Test
    public void testInitializeUpstreamDataUsageBeforeT() {
        // Verify the usage is empty for all upstream types before initialization.
        verifyEmptyUsageForAllUpstreamTypes();

        // Verify the usage is still empty after initialization if sdk is lower than T.
        doReturn(false).when(mDeps).isUpstreamDataUsageMetricsEnabled(any());
        runAndWaitForIdle(() -> mTetheringMetrics.initUpstreamUsageBaseline());
        verifyEmptyUsageForAllUpstreamTypes();
    }

    private android.app.usage.NetworkStats makeNetworkStatsWithTxRxBytes(DataUsage dataUsage) {
        final NetworkStats testAndroidNetStats =
                new NetworkStats(0L /* elapsedRealtime */, 1 /* initialSize */).addEntry(
                        new NetworkStats.Entry("test", 10001, SET_DEFAULT, TAG_NONE,
                                METERED_NO, ROAMING_NO, DEFAULT_NETWORK_YES, dataUsage.rxBytes,
                                10, dataUsage.txBytes, 10, 10));
        return makePublicStatsFromAndroidNetStats(testAndroidNetStats);
    }

    private static UpstreamType matchRuleToUpstreamType(int matchRule) {
        switch (matchRule) {
            case MATCH_MOBILE:
                return UT_CELLULAR;
            case MATCH_WIFI:
                return UT_WIFI;
            case MATCH_BLUETOOTH:
                return UT_BLUETOOTH;
            case MATCH_ETHERNET:
                return UT_ETHERNET;
            default:
                return UpstreamType.UT_UNKNOWN;
        }
    }

    private void initializeUpstreamUsageBaseline() {
        doReturn(true).when(mDeps).isUpstreamDataUsageMetricsEnabled(any());
        runAndWaitForIdle(() -> mTetheringMetrics.initUpstreamUsageBaseline());
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testInitUpstreamUsageBaselineAndCleanup() {
        // Verify the usage is empty for all upstream types before initialization.
        verifyEmptyUsageForAllUpstreamTypes();

        // Verify the usage has been initialized
        initializeUpstreamUsageBaseline();

        mHandler.post(() -> {
            for (UpstreamType type : UpstreamType.values()) {
                final DataUsage dataUsage =
                        mTetheringMetrics.getLastReportedUsageFromUpstreamType(type);
                if (TetheringMetrics.isUsageSupportedForUpstreamType(type)) {
                    assertEquals(mMockUpstreamUsageBaseline.get(type), dataUsage);
                } else {
                    assertEquals(EMPTY, dataUsage);
                }
            }
        });
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);

        // Verify the usage is empty after clean up
        runAndWaitForIdle(() -> mTetheringMetrics.cleanup());
        verifyEmptyUsageForAllUpstreamTypes();
    }

    private void updateUpstreamDataUsage(UpstreamType type, long usageDiff) {
        final DataUsage oldWifiUsage = mMockUpstreamUsageBaseline.get(type);
        final DataUsage newWifiUsage = new DataUsage(
                oldWifiUsage.txBytes + usageDiff,
                oldWifiUsage.rxBytes + usageDiff);
        mMockUpstreamUsageBaseline.put(type, newWifiUsage);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testDataUsageCalculation() throws Exception {
        initializeUpstreamUsageBaseline();
        runAndWaitForIdle(() -> mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG));
        final long wifiTetheringStartTime = currentTimeMillis();
        incrementCurrentTime(1 * SECOND_IN_MILLIS);

        // Change the upstream to Wi-Fi and update the data usage
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI)));
        final long wifiDuration = 5 * SECOND_IN_MILLIS;
        final long wifiUsageDiff = 100L;
        incrementCurrentTime(wifiDuration);
        updateUpstreamDataUsage(UT_WIFI, wifiUsageDiff);

        // Change the upstream to bluetooth and update the data usage
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_BLUETOOTH)));
        final long bluetoothDuration = 15 * SECOND_IN_MILLIS;
        final long btUsageDiff = 50L;
        incrementCurrentTime(bluetoothDuration);
        updateUpstreamDataUsage(UT_BLUETOOTH, btUsageDiff);

        // Change the upstream to cellular and update the data usage
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_CELLULAR)));
        final long cellDuration = 20 * SECOND_IN_MILLIS;
        final long cellUsageDiff = 500L;
        incrementCurrentTime(cellDuration);
        updateUpstreamDataUsage(UT_CELLULAR, cellUsageDiff);

        // Change the upstream back to Wi-FI and update the data usage
        runAndWaitForIdle(() ->
                mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI)));
        final long wifiDuration2 = 50 * SECOND_IN_MILLIS;
        final long wifiUsageDiff2 = 1000L;
        incrementCurrentTime(wifiDuration2);
        updateUpstreamDataUsage(UT_WIFI, wifiUsageDiff2);

        // Stop tethering and verify that the data usage is uploaded.
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);
        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder();
        addUpstreamEvent(upstreamEvents, UT_WIFI, wifiDuration, wifiUsageDiff, wifiUsageDiff);
        addUpstreamEvent(upstreamEvents, UT_BLUETOOTH, bluetoothDuration, btUsageDiff, btUsageDiff);
        addUpstreamEvent(upstreamEvents, UT_CELLULAR, cellDuration, cellUsageDiff, cellUsageDiff);
        addUpstreamEvent(upstreamEvents, UT_WIFI, wifiDuration2, wifiUsageDiff2, wifiUsageDiff2);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                UserType.USER_SETTINGS, upstreamEvents,
                currentTimeMillis() - wifiTetheringStartTime);
    }
}
