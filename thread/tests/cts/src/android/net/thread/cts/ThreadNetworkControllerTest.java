/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.thread.cts;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_CHILD;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_ROUTER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.ThreadNetworkController.EPHEMERAL_KEY_DISABLED;
import static android.net.thread.ThreadNetworkController.EPHEMERAL_KEY_ENABLED;
import static android.net.thread.ThreadNetworkController.STATE_DISABLED;
import static android.net.thread.ThreadNetworkController.STATE_DISABLING;
import static android.net.thread.ThreadNetworkController.STATE_ENABLED;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;
import static android.net.thread.ThreadNetworkException.ERROR_BUSY;
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_REJECTED_BY_PEER;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;
import static android.net.thread.ThreadNetworkException.ERROR_UNSUPPORTED_FEATURE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadConfiguration;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.OperationalDatasetCallback;
import android.net.thread.ThreadNetworkController.StateCallback;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.net.thread.utils.TapTestNetworkTracker;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;
import android.os.Build;
import android.os.HandlerThread;
import android.os.OutcomeReceiver;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;

import com.android.net.module.util.ArrayTrackRecord;
import com.android.net.thread.flags.Flags;
import com.android.testutils.FunctionalUtils.ThrowingRunnable;

import kotlin.Triple;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** CTS tests for {@link ThreadNetworkController}. */
@LargeTest
@RequiresThreadFeature
@RunWith(Parameterized.class)
public class ThreadNetworkControllerTest {
    private static final int JOIN_TIMEOUT_MILLIS = 30 * 1000;
    private static final int LEAVE_TIMEOUT_MILLIS = 2_000;
    private static final int MIGRATION_TIMEOUT_MILLIS = 40 * 1_000;
    private static final int NETWORK_CALLBACK_TIMEOUT_MILLIS = 10 * 1000;
    private static final int CALLBACK_TIMEOUT_MILLIS = 1_000;
    private static final int ENABLED_TIMEOUT_MILLIS = 2_000;
    private static final int SET_CONFIGURATION_TIMEOUT_MILLIS = 1_000;
    private static final int SERVICE_DISCOVERY_TIMEOUT_MILLIS = 30_000;
    private static final int SERVICE_LOST_TIMEOUT_MILLIS = 20_000;
    private static final int VALID_POWER = 32_767;
    private static final int VALID_CHANNEL = 20;
    private static final int INVALID_CHANNEL = 10;
    private static final String MESHCOP_SERVICE_TYPE = "_meshcop._udp";
    private static final String THREAD_NETWORK_PRIVILEGED =
            "android.permission.THREAD_NETWORK_PRIVILEGED";
    private static final SparseIntArray CHANNEL_MAX_POWERS =
            new SparseIntArray() {
                {
                    put(VALID_CHANNEL, VALID_POWER);
                }
            };
    private static final Duration EPHEMERAL_KEY_LIFETIME = Duration.ofSeconds(1);

    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ExecutorService mExecutor;
    private ThreadNetworkController mController;
    private NsdManager mNsdManager;

    private Set<String> mGrantedPermissions;
    private HandlerThread mHandlerThread;
    private TapTestNetworkTracker mTestNetworkTracker;

    private final List<Consumer<ThreadConfiguration>> mConfigurationCallbacksToCleanUp =
            new ArrayList<>();

    public final boolean mIsBorderRouterEnabled;
    private final ThreadConfiguration mDefaultConfig;

    @Parameterized.Parameters
    public static Collection configArguments() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    public ThreadNetworkControllerTest(boolean isBorderRouterEnabled) {
        mIsBorderRouterEnabled = isBorderRouterEnabled;
        mDefaultConfig =
                new ThreadConfiguration.Builder()
                        .setBorderRouterEnabled(isBorderRouterEnabled)
                        .build();
    }

    @Before
    public void setUp() throws Exception {
        mController =
                mContext.getSystemService(ThreadNetworkManager.class)
                        .getAllThreadNetworkControllers()
                        .get(0);

        mGrantedPermissions = new HashSet<String>();
        mExecutor = Executors.newSingleThreadExecutor();
        mNsdManager = mContext.getSystemService(NsdManager.class);
        mHandlerThread = new HandlerThread(this.getClass().getSimpleName());
        mHandlerThread.start();

        setEnabledAndWait(mController, true);
        setConfigurationAndWait(mController, mDefaultConfig);
        if (mDefaultConfig.isBorderRouterEnabled()) {
            deactivateEphemeralKeyModeAndWait(mController);
        }
    }

    @After
    public void tearDown() throws Exception {
        dropAllPermissions();
        setEnabledAndWait(mController, true);
        leaveAndWait(mController);
        tearDownTestNetwork();
        setConfigurationAndWait(mController, mDefaultConfig);
        for (Consumer<ThreadConfiguration> configurationCallback :
                mConfigurationCallbacksToCleanUp) {
            try {
                runAsShell(
                        THREAD_NETWORK_PRIVILEGED,
                        () -> mController.unregisterConfigurationCallback(configurationCallback));
            } catch (IllegalArgumentException e) {
                // Ignore the exception when the callback is not registered.
            }
        }
        mConfigurationCallbacksToCleanUp.clear();
        if (mDefaultConfig.isBorderRouterEnabled()) {
            deactivateEphemeralKeyModeAndWait(mController);
        }
    }

    @Test
    public void getThreadVersion_returnsAtLeastThreadVersion1P3() {
        assertThat(mController.getThreadVersion()).isAtLeast(THREAD_VERSION_1_3);
    }

    @Test
    public void registerStateCallback_permissionsGranted_returnsCurrentStates() throws Exception {
        CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
        StateCallback callback = deviceRole::complete;

        try {
            runAsShell(
                    ACCESS_NETWORK_STATE,
                    () -> mController.registerStateCallback(mExecutor, callback));

            assertThat(deviceRole.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS))
                    .isEqualTo(DEVICE_ROLE_STOPPED);
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(callback));
        }
    }

    @Test
    public void subscribeThreadEnableState_getActiveDataset_onThreadEnableStateChangedNotCalled()
            throws Exception {
        EnabledStateListener listener = new EnabledStateListener(mController);
        listener.expectThreadEnabledState(STATE_ENABLED);

        getActiveOperationalDataset(mController);

        listener.expectCallbackNotCalled();
    }

    @Test
    public void registerStateCallback_returnsUpdatedEnabledStates() throws Exception {
        CompletableFuture<Void> setFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> setFuture2 = new CompletableFuture<>();
        EnabledStateListener listener = new EnabledStateListener(mController);

        try {
            runAsShell(
                    THREAD_NETWORK_PRIVILEGED,
                    () -> {
                        mController.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture1));
                    });
            setFuture1.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);

            runAsShell(
                    THREAD_NETWORK_PRIVILEGED,
                    () -> {
                        mController.setEnabled(true, mExecutor, newOutcomeReceiver(setFuture2));
                    });
            setFuture2.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);

            listener.expectThreadEnabledState(STATE_ENABLED);
            listener.expectThreadEnabledState(STATE_DISABLING);
            listener.expectThreadEnabledState(STATE_DISABLED);
            listener.expectThreadEnabledState(STATE_ENABLED);
        } finally {
            listener.unregisterStateCallback();
        }
    }

    @Test
    public void registerStateCallback_noPermissions_throwsSecurityException() throws Exception {
        dropAllPermissions();

        assertThrows(
                SecurityException.class,
                () -> mController.registerStateCallback(mExecutor, role -> {}));
    }

    @Test
    public void registerStateCallback_alreadyRegistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE);
        CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
        StateCallback callback = role -> deviceRole.complete(role);

        mController.registerStateCallback(mExecutor, callback);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.registerStateCallback(mExecutor, callback));
    }

    @Test
    public void unregisterStateCallback_noPermissions_throwsSecurityException() throws Exception {
        CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
        StateCallback callback = role -> deviceRole.complete(role);
        runAsShell(
                ACCESS_NETWORK_STATE, () -> mController.registerStateCallback(mExecutor, callback));

        try {
            dropAllPermissions();
            assertThrows(
                    SecurityException.class, () -> mController.unregisterStateCallback(callback));
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(callback));
        }
    }

    @Test
    public void unregisterStateCallback_callbackRegistered_success() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE);
        CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
        StateCallback callback = role -> deviceRole.complete(role);

        assertDoesNotThrow(() -> mController.registerStateCallback(mExecutor, callback));
        mController.unregisterStateCallback(callback);
    }

    @Test
    public void unregisterStateCallback_callbackNotRegistered_throwsIllegalArgumentException()
            throws Exception {
        CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
        StateCallback callback = role -> deviceRole.complete(role);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.unregisterStateCallback(callback));
    }

    @Test
    public void unregisterStateCallback_alreadyUnregistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE);
        CompletableFuture<Integer> deviceRole = new CompletableFuture<>();
        StateCallback callback = deviceRole::complete;
        mController.registerStateCallback(mExecutor, callback);
        mController.unregisterStateCallback(callback);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.unregisterStateCallback(callback));
    }

    @Test
    public void registerOperationalDatasetCallback_permissionsGranted_returnsCurrentStates()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, THREAD_NETWORK_PRIVILEGED);
        CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
        CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
        var callback = newDatasetCallback(activeFuture, pendingFuture);

        try {
            mController.registerOperationalDatasetCallback(mExecutor, callback);

            assertThat(activeFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNull();
            assertThat(pendingFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNull();
        } finally {
            mController.unregisterOperationalDatasetCallback(callback);
        }
    }

    @Test
    public void registerOperationalDatasetCallback_noPermissions_throwsSecurityException()
            throws Exception {
        dropAllPermissions();
        CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
        CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
        var callback = newDatasetCallback(activeFuture, pendingFuture);

        assertThrows(
                SecurityException.class,
                () -> mController.registerOperationalDatasetCallback(mExecutor, callback));
    }

    @Test
    public void unregisterOperationalDatasetCallback_callbackRegistered_success() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, THREAD_NETWORK_PRIVILEGED);
        CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
        CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
        var callback = newDatasetCallback(activeFuture, pendingFuture);
        mController.registerOperationalDatasetCallback(mExecutor, callback);

        assertDoesNotThrow(() -> mController.unregisterOperationalDatasetCallback(callback));
    }

    @Test
    public void unregisterOperationalDatasetCallback_noPermissions_throwsSecurityException()
            throws Exception {
        CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
        CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
        var callback = newDatasetCallback(activeFuture, pendingFuture);
        runAsShell(
                ACCESS_NETWORK_STATE,
                THREAD_NETWORK_PRIVILEGED,
                () -> mController.registerOperationalDatasetCallback(mExecutor, callback));

        try {
            dropAllPermissions();
            assertThrows(
                    SecurityException.class,
                    () -> mController.unregisterOperationalDatasetCallback(callback));
        } finally {
            runAsShell(
                    ACCESS_NETWORK_STATE,
                    THREAD_NETWORK_PRIVILEGED,
                    () -> mController.unregisterOperationalDatasetCallback(callback));
        }
    }

    private static <V> OutcomeReceiver<V, ThreadNetworkException> newOutcomeReceiver(
            CompletableFuture<V> future) {
        return new OutcomeReceiver<V, ThreadNetworkException>() {
            @Override
            public void onResult(V result) {
                future.complete(result);
            }

            @Override
            public void onError(ThreadNetworkException e) {
                future.completeExceptionally(e);
            }
        };
    }

    @Test
    public void join_withPrivilegedPermission_success() throws Exception {
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture)));
        joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

        assertThat(isAttached(mController)).isTrue();
        assertThat(getActiveOperationalDataset(mController)).isEqualTo(activeDataset);
    }

    @Test
    public void join_withoutPrivilegedPermission_throwsSecurityException() throws Exception {
        dropAllPermissions();
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);

        assertThrows(
                SecurityException.class, () -> mController.join(activeDataset, mExecutor, v -> {}));
    }

    @Test
    public void join_threadDisabled_failsWithErrorThreadDisabled() throws Exception {
        setEnabledAndWait(mController, false);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture)));

        var thrown =
                assertThrows(
                        ExecutionException.class,
                        () -> joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS));
        var threadException = (ThreadNetworkException) thrown.getCause();
        assertThat(threadException.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void join_concurrentRequests_firstOneIsAborted() throws Exception {
        final byte[] KEY_1 = new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        final byte[] KEY_2 = new byte[] {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2};
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("TestNet", mController))
                        .setNetworkKey(KEY_1)
                        .build();
        ActiveOperationalDataset activeDataset2 =
                new ActiveOperationalDataset.Builder(activeDataset1).setNetworkKey(KEY_2).build();
        CompletableFuture<Void> joinFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> joinFuture2 = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mController.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture1));
                    mController.join(activeDataset2, mExecutor, newOutcomeReceiver(joinFuture2));
                });

        var thrown =
                assertThrows(
                        ExecutionException.class,
                        () -> joinFuture1.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS));
        var threadException = (ThreadNetworkException) thrown.getCause();
        assertThat(threadException.getErrorCode()).isEqualTo(ERROR_ABORTED);
        joinFuture2.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);
        assertThat(isAttached(mController)).isTrue();
        assertThat(getActiveOperationalDataset(mController)).isEqualTo(activeDataset2);
    }

    @Test
    public void leave_withPrivilegedPermission_success() throws Exception {
        CompletableFuture<Void> leaveFuture = new CompletableFuture<>();
        joinRandomizedDatasetAndWait(mController);

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> mController.leave(mExecutor, newOutcomeReceiver(leaveFuture)));
        leaveFuture.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);

        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void leave_withoutPrivilegedPermission_throwsSecurityException() {
        dropAllPermissions();

        assertThrows(SecurityException.class, () -> mController.leave(mExecutor, v -> {}));
    }

    @Test
    public void leave_threadDisabled_success() throws Exception {
        setEnabledAndWait(mController, false);
        CompletableFuture<Void> leaveFuture = new CompletableFuture<>();

        leave(mController, newOutcomeReceiver(leaveFuture));
        leaveFuture.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);

        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void leave_concurrentRequests_bothSuccess() throws Exception {
        CompletableFuture<Void> leaveFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> leaveFuture2 = new CompletableFuture<>();
        joinRandomizedDatasetAndWait(mController);

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mController.leave(mExecutor, newOutcomeReceiver(leaveFuture1));
                    mController.leave(mExecutor, newOutcomeReceiver(leaveFuture2));
                });

        leaveFuture1.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);
        leaveFuture2.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);
        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void scheduleMigration_withPrivilegedPermission_newDatasetApplied() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("TestNet", mController))
                        .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                        .setExtendedPanId(new byte[] {1, 1, 1, 1, 1, 1, 1, 1})
                        .build();
        ActiveOperationalDataset activeDataset2 =
                new ActiveOperationalDataset.Builder(activeDataset1)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                        .setNetworkName("ThreadNet2")
                        .build();
        PendingOperationalDataset pendingDataset2 =
                new PendingOperationalDataset(
                        activeDataset2,
                        OperationalDatasetTimestamp.fromInstant(Instant.now()),
                        Duration.ofSeconds(30));
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        CompletableFuture<Void> migrateFuture = new CompletableFuture<>();
        mController.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

        mController.scheduleMigration(
                pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture));
        migrateFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);

        CompletableFuture<Boolean> dataset2IsApplied = new CompletableFuture<>();
        CompletableFuture<Boolean> pendingDatasetIsRemoved = new CompletableFuture<>();
        OperationalDatasetCallback datasetCallback =
                new OperationalDatasetCallback() {
                    @Override
                    public void onActiveOperationalDatasetChanged(
                            ActiveOperationalDataset activeDataset) {
                        if (Objects.equals(activeDataset, activeDataset2)) {
                            dataset2IsApplied.complete(true);
                        }
                    }

                    @Override
                    public void onPendingOperationalDatasetChanged(
                            PendingOperationalDataset pendingDataset) {
                        if (pendingDataset == null) {
                            pendingDatasetIsRemoved.complete(true);
                        }
                    }
                };
        mController.registerOperationalDatasetCallback(directExecutor(), datasetCallback);
        try {
            assertThat(dataset2IsApplied.get(MIGRATION_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
            assertThat(pendingDatasetIsRemoved.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
        } finally {
            mController.unregisterOperationalDatasetCallback(datasetCallback);
        }
    }

    @Test
    public void scheduleMigration_whenNotAttached_failWithPreconditionError() throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, THREAD_NETWORK_PRIVILEGED);
        PendingOperationalDataset pendingDataset =
                new PendingOperationalDataset(
                        newRandomizedDataset("TestNet", mController),
                        OperationalDatasetTimestamp.fromInstant(Instant.now()),
                        Duration.ofSeconds(30));
        CompletableFuture<Void> migrateFuture = new CompletableFuture<>();

        mController.scheduleMigration(pendingDataset, mExecutor, newOutcomeReceiver(migrateFuture));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, migrateFuture::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_FAILED_PRECONDITION);
    }

    @Test
    public void scheduleMigration_secondRequestHasSmallerTimestamp_rejectedByLeader()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, THREAD_NETWORK_PRIVILEGED);
        final ActiveOperationalDataset activeDataset =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("testNet", mController))
                        .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                        .build();
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                        .setNetworkName("testNet1")
                        .build();
        PendingOperationalDataset pendingDataset1 =
                new PendingOperationalDataset(
                        activeDataset1,
                        new OperationalDatasetTimestamp(100, 0, false),
                        Duration.ofSeconds(30));
        ActiveOperationalDataset activeDataset2 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(3L, 0, false))
                        .setNetworkName("testNet2")
                        .build();
        PendingOperationalDataset pendingDataset2 =
                new PendingOperationalDataset(
                        activeDataset2,
                        new OperationalDatasetTimestamp(20, 0, false),
                        Duration.ofSeconds(30));
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        CompletableFuture<Void> migrateFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> migrateFuture2 = new CompletableFuture<>();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

        mController.scheduleMigration(
                pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
        migrateFuture1.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        mController.scheduleMigration(
                pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, migrateFuture2::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_REJECTED_BY_PEER);
    }

    @Test
    public void scheduleMigration_secondRequestHasLargerTimestamp_newDatasetApplied()
            throws Exception {
        grantPermissions(ACCESS_NETWORK_STATE, THREAD_NETWORK_PRIVILEGED);
        final ActiveOperationalDataset activeDataset =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("validName", mController))
                        .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                        .build();
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                        .setNetworkName("testNet1")
                        .build();
        PendingOperationalDataset pendingDataset1 =
                new PendingOperationalDataset(
                        activeDataset1,
                        new OperationalDatasetTimestamp(100, 0, false),
                        Duration.ofSeconds(30));
        ActiveOperationalDataset activeDataset2 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(3L, 0, false))
                        .setNetworkName("testNet2")
                        .build();
        PendingOperationalDataset pendingDataset2 =
                new PendingOperationalDataset(
                        activeDataset2,
                        new OperationalDatasetTimestamp(200, 0, false),
                        Duration.ofSeconds(30));
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        CompletableFuture<Void> migrateFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> migrateFuture2 = new CompletableFuture<>();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);

        mController.scheduleMigration(
                pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
        migrateFuture1.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        mController.scheduleMigration(
                pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));
        migrateFuture2.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);

        CompletableFuture<Boolean> dataset2IsApplied = new CompletableFuture<>();
        CompletableFuture<Boolean> pendingDatasetIsRemoved = new CompletableFuture<>();
        OperationalDatasetCallback datasetCallback =
                new OperationalDatasetCallback() {
                    @Override
                    public void onActiveOperationalDatasetChanged(
                            ActiveOperationalDataset activeDataset) {
                        if (activeDataset.equals(activeDataset2)) {
                            dataset2IsApplied.complete(true);
                        }
                    }

                    @Override
                    public void onPendingOperationalDatasetChanged(
                            PendingOperationalDataset pendingDataset) {
                        if (pendingDataset == null) {
                            pendingDatasetIsRemoved.complete(true);
                        }
                    }
                };
        mController.registerOperationalDatasetCallback(directExecutor(), datasetCallback);
        try {
            assertThat(dataset2IsApplied.get(MIGRATION_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
            assertThat(pendingDatasetIsRemoved.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
        } finally {
            mController.unregisterOperationalDatasetCallback(datasetCallback);
        }
    }

    @Test
    public void scheduleMigration_threadDisabled_failsWithErrorThreadDisabled() throws Exception {
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        PendingOperationalDataset pendingDataset =
                new PendingOperationalDataset(
                        activeDataset,
                        OperationalDatasetTimestamp.fromInstant(Instant.now()),
                        Duration.ofSeconds(30));
        joinRandomizedDatasetAndWait(mController);
        CompletableFuture<Void> migrationFuture = new CompletableFuture<>();

        setEnabledAndWait(mController, false);

        scheduleMigration(mController, pendingDataset, newOutcomeReceiver(migrationFuture));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, migrationFuture::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void createRandomizedDataset_wrongNetworkNameLength_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mController.createRandomizedDataset("", mExecutor, dataset -> {}));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mController.createRandomizedDataset(
                                "ANetNameIs17Bytes", mExecutor, dataset -> {}));
    }

    @Test
    public void createRandomizedDataset_validNetworkName_success() throws Exception {
        ActiveOperationalDataset dataset = newRandomizedDataset("validName", mController);

        assertThat(dataset.getNetworkName()).isEqualTo("validName");
        assertThat(dataset.getPanId()).isLessThan(0xffff);
        assertThat(dataset.getChannelMask().size()).isAtLeast(1);
        assertThat(dataset.getExtendedPanId()).hasLength(8);
        assertThat(dataset.getNetworkKey()).hasLength(16);
        assertThat(dataset.getPskc()).hasLength(16);
        assertThat(dataset.getMeshLocalPrefix().getPrefixLength()).isEqualTo(64);
        assertThat(dataset.getMeshLocalPrefix().getRawAddress()[0]).isEqualTo((byte) 0xfd);
    }

    @Test
    public void setEnabled_permissionsGranted_succeeds() throws Exception {
        CompletableFuture<Void> setFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> setFuture2 = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> mController.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture1)));
        setFuture1.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        waitForEnabledState(mController, booleanToEnabledState(false));

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> mController.setEnabled(true, mExecutor, newOutcomeReceiver(setFuture2)));
        setFuture2.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        waitForEnabledState(mController, booleanToEnabledState(true));
    }

    @Test
    public void setEnabled_noPermissions_throwsSecurityException() throws Exception {
        CompletableFuture<Void> setFuture = new CompletableFuture<>();
        assertThrows(
                SecurityException.class,
                () -> mController.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture)));
    }

    @Test
    public void setEnabled_disable_leavesThreadNetwork() throws Exception {
        joinRandomizedDatasetAndWait(mController);
        setEnabledAndWait(mController, false);
        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void setEnabled_enableFollowedByDisable_allSucceed() throws Exception {
        joinRandomizedDatasetAndWait(mController);
        CompletableFuture<Void> setFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> setFuture2 = new CompletableFuture<>();
        EnabledStateListener listener = new EnabledStateListener(mController);
        listener.expectThreadEnabledState(STATE_ENABLED);

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mController.setEnabled(true, mExecutor, newOutcomeReceiver(setFuture1));
                    mController.setEnabled(false, mExecutor, newOutcomeReceiver(setFuture2));
                });
        setFuture1.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        setFuture2.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);

        listener.expectThreadEnabledState(STATE_DISABLING);
        listener.expectThreadEnabledState(STATE_DISABLED);
        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
        // FIXME: this is not called when a exception is thrown after the creation of `listener`
        listener.unregisterStateCallback();
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void getMaxEphemeralKeyLifetime_isLargerThanZero() {
        assertThat(mController.getMaxEphemeralKeyLifetime()).isGreaterThan(Duration.ZERO);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void activateEphemeralKeyMode_withPrivilegedPermission_succeeds() throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        joinRandomizedDatasetAndWait(mController);
        CompletableFuture<Void> startFuture = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () ->
                        mController.activateEphemeralKeyMode(
                                EPHEMERAL_KEY_LIFETIME,
                                mExecutor,
                                newOutcomeReceiver(startFuture)));

        startFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void activateEphemeralKeyMode_withoutPrivilegedPermission_throwsSecurityException()
            throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        dropAllPermissions();

        assertThrows(
                SecurityException.class,
                () ->
                        mController.activateEphemeralKeyMode(
                                EPHEMERAL_KEY_LIFETIME, mExecutor, v -> {}));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void activateEphemeralKeyMode_withZeroLifetime_throwsIllegalArgumentException()
            throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        grantPermissions(THREAD_NETWORK_PRIVILEGED);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.activateEphemeralKeyMode(Duration.ZERO, mExecutor, v -> {}));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void activateEphemeralKeyMode_withInvalidLargeLifetime_throwsIllegalArgumentException()
            throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        grantPermissions(THREAD_NETWORK_PRIVILEGED);
        Duration lifetime = mController.getMaxEphemeralKeyLifetime().plusMillis(1);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.activateEphemeralKeyMode(lifetime, Runnable::run, v -> {}));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void activateEphemeralKeyMode_concurrentRequests_secondOneFailsWithBusyError()
            throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        joinRandomizedDatasetAndWait(mController);
        CompletableFuture<Void> future1 = new CompletableFuture<>();
        CompletableFuture<Void> future2 = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mController.activateEphemeralKeyMode(
                            EPHEMERAL_KEY_LIFETIME, mExecutor, newOutcomeReceiver(future1));
                    mController.activateEphemeralKeyMode(
                            EPHEMERAL_KEY_LIFETIME, mExecutor, newOutcomeReceiver(future2));
                });

        var thrown =
                assertThrows(
                        ExecutionException.class,
                        () -> {
                            future2.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
                        });
        var threadException = (ThreadNetworkException) thrown.getCause();
        assertThat(threadException.getErrorCode()).isEqualTo(ERROR_BUSY);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void activateEphemeralKeyMode_notBorderRouter_failsWithFailedPrecondition()
            throws Exception {
        setConfigurationAndWait(
                mController,
                new ThreadConfiguration.Builder().setBorderRouterEnabled(false).build());
        grantPermissions(THREAD_NETWORK_PRIVILEGED);
        CompletableFuture<Void> future = new CompletableFuture<>();

        mController.activateEphemeralKeyMode(
                Duration.ofSeconds(1), mExecutor, newOutcomeReceiver(future));

        var thrown =
                assertThrows(
                        ExecutionException.class,
                        () -> future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS));
        var threadException = (ThreadNetworkException) thrown.getCause();
        assertThat(threadException.getErrorCode()).isEqualTo(ERROR_FAILED_PRECONDITION);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void deactivateEphemeralKeyMode_withoutPrivilegedPermission_throwsSecurityException()
            throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        dropAllPermissions();

        assertThrows(
                SecurityException.class,
                () -> mController.deactivateEphemeralKeyMode(mExecutor, v -> {}));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void deactivateEphemeralKeyMode_notBorderRouter_failsWithFailedPrecondition()
            throws Exception {
        assumeFalse(mDefaultConfig.isBorderRouterEnabled());
        grantPermissions(THREAD_NETWORK_PRIVILEGED);
        CompletableFuture<Void> future = new CompletableFuture<>();

        mController.deactivateEphemeralKeyMode(mExecutor, newOutcomeReceiver(future));

        var thrown =
                assertThrows(
                        ExecutionException.class,
                        () -> future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS));
        var threadException = (ThreadNetworkException) thrown.getCause();
        assertThat(threadException.getErrorCode()).isEqualTo(ERROR_FAILED_PRECONDITION);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void subscribeEpskcState_permissionsGranted_returnsCurrentState() throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        CompletableFuture<Integer> stateFuture = new CompletableFuture<>();
        CompletableFuture<String> ephemeralKeyFuture = new CompletableFuture<>();
        CompletableFuture<Instant> expiryFuture = new CompletableFuture<>();
        StateCallback callback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onEphemeralKeyStateChanged(
                            int state, String ephemeralKey, Instant expiry) {
                        stateFuture.complete(state);
                        ephemeralKeyFuture.complete(ephemeralKey);
                        expiryFuture.complete(expiry);
                    }
                };

        runAsShell(
                ACCESS_NETWORK_STATE,
                THREAD_NETWORK_PRIVILEGED,
                () -> mController.registerStateCallback(mExecutor, callback));

        try {
            assertThat(stateFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS))
                    .isEqualTo(EPHEMERAL_KEY_DISABLED);
            assertThat(ephemeralKeyFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNull();
            assertThat(expiryFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNull();
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(callback));
        }
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void subscribeEpskcState_withoutThreadPriviledgedPermission_returnsNullEphemeralKey()
            throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        CompletableFuture<Integer> stateFuture = new CompletableFuture<>();
        CompletableFuture<String> ephemeralKeyFuture = new CompletableFuture<>();
        CompletableFuture<Instant> expiryFuture = new CompletableFuture<>();
        StateCallback callback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onEphemeralKeyStateChanged(
                            int state, String ephemeralKey, Instant expiry) {
                        stateFuture.complete(state);
                        ephemeralKeyFuture.complete(ephemeralKey);
                        expiryFuture.complete(expiry);
                    }
                };
        joinRandomizedDatasetAndWait(mController);
        activateEphemeralKeyModeAndWait(mController);

        runAsShell(
                ACCESS_NETWORK_STATE, () -> mController.registerStateCallback(mExecutor, callback));

        try {
            assertThat(stateFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS))
                    .isEqualTo(EPHEMERAL_KEY_ENABLED);
            assertThat(ephemeralKeyFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNull();
            assertThat(
                            expiryFuture
                                    .get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)
                                    .isAfter(Instant.now()))
                    .isTrue();
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(callback));
        }
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void subscribeEpskcState_ephemralKeyStateChanged_returnsUpdatedState() throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        EphemeralKeyStateListener listener = new EphemeralKeyStateListener(mController);
        joinRandomizedDatasetAndWait(mController);

        try {
            activateEphemeralKeyModeAndWait(mController);
            deactivateEphemeralKeyModeAndWait(mController);

            listener.expectThreadEphemeralKeyMode(EPHEMERAL_KEY_DISABLED);
            listener.expectThreadEphemeralKeyMode(EPHEMERAL_KEY_ENABLED);
            listener.expectThreadEphemeralKeyMode(EPHEMERAL_KEY_DISABLED);
        } finally {
            listener.unregisterStateCallback();
        }
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_EPSKC_ENABLED})
    public void subscribeEpskcState_epskcEnabled_returnsSameExpiry() throws Exception {
        assumeTrue(mDefaultConfig.isBorderRouterEnabled());
        EphemeralKeyStateListener listener1 = new EphemeralKeyStateListener(mController);
        Triple<Integer, String, Instant> epskc1;
        try {
            joinRandomizedDatasetAndWait(mController);
            activateEphemeralKeyModeAndWait(mController);
            epskc1 = listener1.expectThreadEphemeralKeyMode(EPHEMERAL_KEY_ENABLED);
        } finally {
            listener1.unregisterStateCallback();
        }

        EphemeralKeyStateListener listener2 = new EphemeralKeyStateListener(mController);
        try {
            Triple<Integer, String, Instant> epskc2 =
                    listener2.expectThreadEphemeralKeyMode(EPHEMERAL_KEY_ENABLED);

            assertThat(epskc2.getSecond()).isEqualTo(epskc1.getSecond());
            // allow time precision loss of a second since the value is passed via IPC
            assertThat(epskc2.getThird()).isGreaterThan(epskc1.getThird().minusSeconds(1));
            assertThat(epskc2.getThird()).isLessThan(epskc1.getThird().plusSeconds(1));
        } finally {
            listener2.unregisterStateCallback();
        }
    }

    // TODO (b/322437869): add test case to verify when Thread is in DISABLING state, any commands
    // (join/leave/scheduleMigration/setEnabled) fail with ERROR_BUSY. This is not currently tested
    // because DISABLING has very short lifecycle, it's not possible to guarantee the command can be
    // sent before state changes to DISABLED.

    @Test
    public void threadNetworkCallback_deviceAttached_threadNetworkIsAvailable() throws Exception {
        CompletableFuture<Network> networkFuture = new CompletableFuture<>();
        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        NetworkRequest.Builder networkRequestBuilder =
                new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_THREAD);
        // Before V, we need to explicitly set `NET_CAPABILITY_LOCAL_NETWORK` capability to request
        // a Thread network.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            networkRequestBuilder.addCapability(NET_CAPABILITY_LOCAL_NETWORK);
        }
        NetworkRequest networkRequest = networkRequestBuilder.build();
        ConnectivityManager.NetworkCallback networkCallback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        networkFuture.complete(network);
                    }
                };

        joinRandomizedDatasetAndWait(mController);
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> cm.registerNetworkCallback(networkRequest, networkCallback));

        assertThat(isAttached(mController)).isTrue();
        assertThat(networkFuture.get(NETWORK_CALLBACK_TIMEOUT_MILLIS, MILLISECONDS)).isNotNull();
        NetworkCapabilities caps =
                runAsShell(
                        ACCESS_NETWORK_STATE, () -> cm.getNetworkCapabilities(networkFuture.get()));
        assertThat(caps).isNotNull();
        assertThat(caps.hasTransport(NetworkCapabilities.TRANSPORT_THREAD)).isTrue();
        assertThat(caps.getCapabilities())
                .asList()
                .containsAtLeast(
                        NET_CAPABILITY_LOCAL_NETWORK,
                        NET_CAPABILITY_NOT_METERED,
                        NET_CAPABILITY_NOT_RESTRICTED,
                        NET_CAPABILITY_NOT_VCN_MANAGED,
                        NET_CAPABILITY_NOT_VPN,
                        NET_CAPABILITY_TRUSTED);
    }

    @Test
    public void setConfiguration_null_throwsNullPointerException() throws Exception {
        CompletableFuture<Void> setConfigFuture = new CompletableFuture<>();
        assertThrows(
                NullPointerException.class,
                () ->
                        mController.setConfiguration(
                                null, mExecutor, newOutcomeReceiver(setConfigFuture)));
    }

    @Test
    public void setConfiguration_noPermissions_throwsSecurityException() throws Exception {
        ThreadConfiguration configuration =
                new ThreadConfiguration.Builder().setNat64Enabled(true).build();
        CompletableFuture<Void> setConfigFuture = new CompletableFuture<>();
        assertThrows(
                SecurityException.class,
                () -> {
                    mController.setConfiguration(
                            configuration, mExecutor, newOutcomeReceiver(setConfigFuture));
                });
    }

    @Test
    public void registerConfigurationCallback_permissionsGranted_returnsCurrentStatus()
            throws Exception {
        CompletableFuture<ThreadConfiguration> getConfigFuture = new CompletableFuture<>();
        Consumer<ThreadConfiguration> callback = getConfigFuture::complete;

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> registerConfigurationCallback(mController, mExecutor, callback));
        assertThat(getConfigFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS))
                .isEqualTo(mDefaultConfig);
    }

    @Test
    public void registerConfigurationCallback_noPermissions_throwsSecurityException()
            throws Exception {
        dropAllPermissions();

        assertThrows(
                SecurityException.class,
                () -> registerConfigurationCallback(mController, mExecutor, config -> {}));
    }

    @Test
    public void registerConfigurationCallback_returnsUpdatedConfigurations() throws Exception {
        CompletableFuture<Void> setFuture1 = new CompletableFuture<>();
        CompletableFuture<Void> setFuture2 = new CompletableFuture<>();
        ConfigurationListener listener = new ConfigurationListener(mController);
        ThreadConfiguration config1 =
                new ThreadConfiguration.Builder()
                        .setBorderRouterEnabled(true)
                        .setNat64Enabled(true)
                        .build();
        ThreadConfiguration config2 =
                new ThreadConfiguration.Builder()
                        .setBorderRouterEnabled(false)
                        .setNat64Enabled(false)
                        .build();

        try {
            runAsShell(
                    THREAD_NETWORK_PRIVILEGED,
                    () ->
                            mController.setConfiguration(
                                    config1, mExecutor, newOutcomeReceiver(setFuture1)));
            runAsShell(
                    THREAD_NETWORK_PRIVILEGED,
                    () ->
                            mController.setConfiguration(
                                    config2, mExecutor, newOutcomeReceiver(setFuture2)));
            setFuture1.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
            setFuture2.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);

            listener.expectConfiguration(mDefaultConfig);
            listener.expectConfiguration(config1);
            listener.expectConfiguration(config2);
            listener.expectNoMoreConfiguration();
        } finally {
            listener.unregisterConfigurationCallback();
        }
    }

    @Test
    public void registerConfigurationCallback_alreadyRegistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(THREAD_NETWORK_PRIVILEGED);

        Consumer<ThreadConfiguration> callback = config -> {};
        registerConfigurationCallback(mController, mExecutor, callback);

        assertThrows(
                IllegalArgumentException.class,
                () -> registerConfigurationCallback(mController, mExecutor, callback));
    }

    @Test
    public void unregisterConfigurationCallback_noPermissions_throwsSecurityException()
            throws Exception {
        Consumer<ThreadConfiguration> callback = config -> {};
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> registerConfigurationCallback(mController, mExecutor, callback));

        assertThrows(
                SecurityException.class,
                () -> mController.unregisterConfigurationCallback(callback));
    }

    @Test
    public void unregisterConfigurationCallback_callbackRegistered_success() throws Exception {
        Consumer<ThreadConfiguration> callback = config -> {};
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    registerConfigurationCallback(mController, mExecutor, callback);
                    mController.unregisterConfigurationCallback(callback);
                });
    }

    @Test
    public void
            unregisterConfigurationCallback_callbackNotRegistered_throwsIllegalArgumentException()
                    throws Exception {
        Consumer<ThreadConfiguration> callback = config -> {};
        assertThrows(
                IllegalArgumentException.class,
                () -> mController.unregisterConfigurationCallback(callback));
    }

    @Test
    public void unregisterConfigurationCallback_alreadyUnregistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(THREAD_NETWORK_PRIVILEGED);

        Consumer<ThreadConfiguration> callback = config -> {};
        registerConfigurationCallback(mController, mExecutor, callback);
        mController.unregisterConfigurationCallback(callback);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.unregisterConfigurationCallback(callback));
    }

    private void grantPermissions(String... permissions) {
        for (String permission : permissions) {
            mGrantedPermissions.add(permission);
        }
        String[] allPermissions = new String[mGrantedPermissions.size()];
        mGrantedPermissions.toArray(allPermissions);
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(allPermissions);
    }

    @Test
    @Ignore("b/333649897, b/332195449: The 3 meshcop tests are flaky in different environments")
    public void meshcopService_threadEnabledButNotJoined_discoveredButNoNetwork() throws Exception {
        setUpTestNetwork();

        setEnabledAndWait(mController, true);
        leaveAndWait(mController);

        NsdServiceInfo serviceInfo =
                expectServiceResolved(
                        MESHCOP_SERVICE_TYPE,
                        SERVICE_DISCOVERY_TIMEOUT_MILLIS,
                        s -> s.getAttributes().get("at") == null);

        Map<String, byte[]> txtMap = serviceInfo.getAttributes();

        assertThat(txtMap.get("rv")).isNotNull();
        assertThat(txtMap.get("tv")).isNotNull();
        // Border Agent State Bitmap is 32 bits
        assertThat(txtMap.get("sb").length).isEqualTo(4);
        // The 12th bit (4th bit of the second byte) for ePSKc support should be set to 1.
        assertThat(txtMap.get("sb")[2] & 8).isEqualTo(8);
    }

    @Test
    @Ignore("b/333649897: Enable this when it's not flaky at all")
    public void meshcopService_joinedNetwork_discoveredHasNetwork() throws Exception {
        setUpTestNetwork();

        String networkName = "TestNet" + new Random().nextInt(10_000);
        joinRandomizedDatasetAndWait(mController, networkName);

        Predicate<NsdServiceInfo> predicate =
                serviceInfo ->
                        serviceInfo.getAttributes().get("at") != null
                                && Arrays.equals(
                                        serviceInfo.getAttributes().get("nn"),
                                        networkName.getBytes(StandardCharsets.UTF_8));

        NsdServiceInfo resolvedService =
                expectServiceResolved(
                        MESHCOP_SERVICE_TYPE, SERVICE_DISCOVERY_TIMEOUT_MILLIS, predicate);

        Map<String, byte[]> txtMap = resolvedService.getAttributes();
        assertThat(txtMap.get("rv")).isNotNull();
        assertThat(txtMap.get("tv")).isNotNull();
        // Border Agent State Bitmap is 32 bits
        assertThat(txtMap.get("sb").length).isEqualTo(4);
        // The 12th bit (4th bit of the second byte) for ePSKc support should be set to 1.
        assertThat(txtMap.get("sb")[2] & 8).isEqualTo(8);
        assertThat(txtMap.get("id").length).isEqualTo(16);
    }

    @Test
    @Ignore("b/333649897, b/332195449: The 3 meshcop tests are flaky in different environments")
    public void meshcopService_threadDisabled_notDiscovered() throws Exception {
        setUpTestNetwork();
        CompletableFuture<NsdServiceInfo> serviceLostFuture = new CompletableFuture<>();
        NsdManager.DiscoveryListener listener =
                discoverForServiceLost(MESHCOP_SERVICE_TYPE, serviceLostFuture);

        setEnabledAndWait(mController, false);

        try {
            serviceLostFuture.get(SERVICE_LOST_TIMEOUT_MILLIS, MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException ignored) {
            // It's fine if the service lost event didn't show up. The service may not ever be
            // advertised.
        } finally {
            mNsdManager.stopServiceDiscovery(listener);
        }
        assertThrows(
                TimeoutException.class,
                () -> discoverService(MESHCOP_SERVICE_TYPE, SERVICE_LOST_TIMEOUT_MILLIS));
    }

    private static void dropAllPermissions() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    private static ActiveOperationalDataset newRandomizedDataset(
            String networkName, ThreadNetworkController controller) throws Exception {
        CompletableFuture<ActiveOperationalDataset> future = new CompletableFuture<>();
        controller.createRandomizedDataset(networkName, directExecutor(), future::complete);
        return future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private static boolean isAttached(ThreadNetworkController controller) throws Exception {
        return ThreadNetworkController.isAttached(getDeviceRole(controller));
    }

    private static int getDeviceRole(ThreadNetworkController controller) throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback = future::complete;
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> controller.registerStateCallback(directExecutor(), callback));
        try {
            return future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> controller.unregisterStateCallback(callback));
        }
    }

    private static int waitForAttachedState(ThreadNetworkController controller) throws Exception {
        List<Integer> attachedRoles = new ArrayList<>();
        attachedRoles.add(DEVICE_ROLE_CHILD);
        attachedRoles.add(DEVICE_ROLE_ROUTER);
        attachedRoles.add(DEVICE_ROLE_LEADER);
        return waitForStateAnyOf(controller, attachedRoles);
    }

    private static int waitForStateAnyOf(
            ThreadNetworkController controller, List<Integer> deviceRoles) throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback =
                newRole -> {
                    if (deviceRoles.contains(newRole)) {
                        future.complete(newRole);
                    }
                };
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterStateCallback(callback);
        return role;
    }

    private static void waitForEnabledState(ThreadNetworkController controller, int state)
            throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onThreadEnableStateChanged(int enabled) {
                        if (enabled == state) {
                            future.complete(enabled);
                        }
                    }
                };
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> controller.registerStateCallback(directExecutor(), callback));
        future.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        runAsShell(ACCESS_NETWORK_STATE, () -> controller.unregisterStateCallback(callback));
    }

    private void leave(
            ThreadNetworkController controller,
            OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        runAsShell(THREAD_NETWORK_PRIVILEGED, () -> controller.leave(mExecutor, receiver));
    }

    private void leaveAndWait(ThreadNetworkController controller) throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        leave(controller, future::complete);
        future.get(LEAVE_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private void scheduleMigration(
            ThreadNetworkController controller,
            PendingOperationalDataset pendingDataset,
            OutcomeReceiver<Void, ThreadNetworkException> receiver) {
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> controller.scheduleMigration(pendingDataset, mExecutor, receiver));
    }

    private class EnabledStateListener {
        private ArrayTrackRecord<Integer> mEnabledStates = new ArrayTrackRecord<>();
        private final ArrayTrackRecord<Integer>.ReadHead mReadHead = mEnabledStates.newReadHead();
        ThreadNetworkController mController;
        StateCallback mCallback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onThreadEnableStateChanged(int enabled) {
                        mEnabledStates.add(enabled);
                    }
                };

        EnabledStateListener(ThreadNetworkController controller) {
            this.mController = controller;
            runAsShell(
                    ACCESS_NETWORK_STATE,
                    () -> controller.registerStateCallback(mExecutor, mCallback));
        }

        public void expectThreadEnabledState(int enabled) {
            assertThat(mReadHead.poll(ENABLED_TIMEOUT_MILLIS, e -> (e == enabled))).isNotNull();
        }

        public void expectCallbackNotCalled() {
            assertThat(mReadHead.poll(CALLBACK_TIMEOUT_MILLIS, e -> true)).isNull();
        }

        public void unregisterStateCallback() {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(mCallback));
        }
    }

    private class ConfigurationListener {
        private ArrayTrackRecord<ThreadConfiguration> mConfigurations = new ArrayTrackRecord<>();
        private final ArrayTrackRecord<ThreadConfiguration>.ReadHead mReadHead =
                mConfigurations.newReadHead();
        ThreadNetworkController mController;
        Consumer<ThreadConfiguration> mCallback = (config) -> mConfigurations.add(config);

        ConfigurationListener(ThreadNetworkController controller) {
            this.mController = controller;
            runAsShell(
                    THREAD_NETWORK_PRIVILEGED,
                    () -> controller.registerConfigurationCallback(mExecutor, mCallback));
        }

        public void expectConfiguration(ThreadConfiguration config) {
            assertThat(mReadHead.poll(CALLBACK_TIMEOUT_MILLIS, c -> c.equals(config))).isNotNull();
        }

        public void expectNoMoreConfiguration() {
            assertThat(mReadHead.poll(CALLBACK_TIMEOUT_MILLIS, c -> true)).isNull();
        }

        public void unregisterConfigurationCallback() {
            runAsShell(
                    THREAD_NETWORK_PRIVILEGED,
                    () -> mController.unregisterConfigurationCallback(mCallback));
        }
    }

    private int booleanToEnabledState(boolean enabled) {
        return enabled ? STATE_ENABLED : STATE_DISABLED;
    }

    private void setEnabledAndWait(ThreadNetworkController controller, boolean enabled)
            throws Exception {
        CompletableFuture<Void> setFuture = new CompletableFuture<>();
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> controller.setEnabled(enabled, mExecutor, newOutcomeReceiver(setFuture)));
        setFuture.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        waitForEnabledState(controller, booleanToEnabledState(enabled));
    }

    private void setConfigurationAndWait(
            ThreadNetworkController controller, ThreadConfiguration configuration)
            throws Exception {
        CompletableFuture<Void> setFuture = new CompletableFuture<>();
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () ->
                        controller.setConfiguration(
                                configuration, mExecutor, newOutcomeReceiver(setFuture)));
        setFuture.get(SET_CONFIGURATION_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private void deactivateEphemeralKeyModeAndWait(ThreadNetworkController controller)
            throws Exception {
        CompletableFuture<Void> clearFuture = new CompletableFuture<>();
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () ->
                        controller.deactivateEphemeralKeyMode(
                                mExecutor, newOutcomeReceiver(clearFuture)));
        clearFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private void activateEphemeralKeyModeAndWait(ThreadNetworkController controller)
            throws Exception {
        CompletableFuture<Void> startFuture = new CompletableFuture<>();
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () ->
                        controller.activateEphemeralKeyMode(
                                EPHEMERAL_KEY_LIFETIME,
                                mExecutor,
                                newOutcomeReceiver(startFuture)));
        startFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private class EphemeralKeyStateListener {
        private ArrayTrackRecord<Triple<Integer, String, Instant>> mEphemeralKeyStates =
                new ArrayTrackRecord<>();
        private final ArrayTrackRecord<Triple<Integer, String, Instant>>.ReadHead mReadHead =
                mEphemeralKeyStates.newReadHead();
        ThreadNetworkController mController;
        StateCallback mCallback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onEphemeralKeyStateChanged(
                            int state, String ephemeralKey, Instant expiry) {
                        mEphemeralKeyStates.add(new Triple<>(state, ephemeralKey, expiry));
                    }
                };

        EphemeralKeyStateListener(ThreadNetworkController controller) {
            this.mController = controller;
            runAsShell(
                    ACCESS_NETWORK_STATE,
                    THREAD_NETWORK_PRIVILEGED,
                    () -> controller.registerStateCallback(mExecutor, mCallback));
        }

        // Expect that EphemeralKey has the expected state, and return a Triple of <state,
        // passcode, expiry>.
        public Triple<Integer, String, Instant> expectThreadEphemeralKeyMode(int state) {
            Triple<Integer, String, Instant> epskc =
                    mReadHead.poll(
                            ENABLED_TIMEOUT_MILLIS, e -> Objects.equals(e.getFirst(), state));
            assertThat(epskc).isNotNull();
            return epskc;
        }

        public void unregisterStateCallback() {
            runAsShell(ACCESS_NETWORK_STATE, () -> mController.unregisterStateCallback(mCallback));
        }
    }

    private CompletableFuture joinRandomizedDataset(
            ThreadNetworkController controller, String networkName) throws Exception {
        ActiveOperationalDataset activeDataset = newRandomizedDataset(networkName, controller);
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture)));
        return joinFuture;
    }

    private void joinRandomizedDatasetAndWait(ThreadNetworkController controller) throws Exception {
        joinRandomizedDatasetAndWait(controller, "TestNet");
    }

    private void joinRandomizedDatasetAndWait(
            ThreadNetworkController controller, String networkName) throws Exception {
        CompletableFuture<Void> joinFuture = joinRandomizedDataset(controller, networkName);
        joinFuture.get(JOIN_TIMEOUT_MILLIS, MILLISECONDS);
        assertThat(isAttached(controller)).isTrue();
    }

    private static ActiveOperationalDataset getActiveOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        CompletableFuture<ActiveOperationalDataset> future = new CompletableFuture<>();
        OperationalDatasetCallback callback = future::complete;
        runAsShell(
                ACCESS_NETWORK_STATE,
                THREAD_NETWORK_PRIVILEGED,
                () -> controller.registerOperationalDatasetCallback(directExecutor(), callback));
        try {
            return future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        } finally {
            runAsShell(
                    ACCESS_NETWORK_STATE,
                    THREAD_NETWORK_PRIVILEGED,
                    () -> controller.unregisterOperationalDatasetCallback(callback));
        }
    }

    private static PendingOperationalDataset getPendingOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        CompletableFuture<ActiveOperationalDataset> activeFuture = new CompletableFuture<>();
        CompletableFuture<PendingOperationalDataset> pendingFuture = new CompletableFuture<>();
        controller.registerOperationalDatasetCallback(
                directExecutor(), newDatasetCallback(activeFuture, pendingFuture));
        return pendingFuture.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private static OperationalDatasetCallback newDatasetCallback(
            CompletableFuture<ActiveOperationalDataset> activeFuture,
            CompletableFuture<PendingOperationalDataset> pendingFuture) {
        return new OperationalDatasetCallback() {
            @Override
            public void onActiveOperationalDatasetChanged(
                    ActiveOperationalDataset activeOpDataset) {
                activeFuture.complete(activeOpDataset);
            }

            @Override
            public void onPendingOperationalDatasetChanged(
                    PendingOperationalDataset pendingOpDataset) {
                pendingFuture.complete(pendingOpDataset);
            }
        };
    }

    private void registerConfigurationCallback(
            ThreadNetworkController controller,
            Executor executor,
            Consumer<ThreadConfiguration> callback) {
        controller.registerConfigurationCallback(executor, callback);
        mConfigurationCallbacksToCleanUp.add(callback);
    }

    private static void assertDoesNotThrow(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            fail("Should not have thrown " + e);
        }
    }

    // Return the first discovered service instance.
    private NsdServiceInfo discoverService(String serviceType) throws Exception {
        return discoverService(serviceType, SERVICE_DISCOVERY_TIMEOUT_MILLIS);
    }

    // Return the first discovered service instance.
    private NsdServiceInfo discoverService(String serviceType, int timeoutMilliseconds)
            throws Exception {
        CompletableFuture<NsdServiceInfo> serviceInfoFuture = new CompletableFuture<>();
        NsdManager.DiscoveryListener listener =
                new DefaultDiscoveryListener() {
                    @Override
                    public void onServiceFound(NsdServiceInfo serviceInfo) {
                        serviceInfoFuture.complete(serviceInfo);
                    }
                };
        mNsdManager.discoverServices(
                serviceType,
                NsdManager.PROTOCOL_DNS_SD,
                mTestNetworkTracker.getNetwork(),
                mExecutor,
                listener);
        try {
            serviceInfoFuture.get(timeoutMilliseconds, MILLISECONDS);
        } finally {
            mNsdManager.stopServiceDiscovery(listener);
        }

        return serviceInfoFuture.get();
    }

    private NsdManager.DiscoveryListener discoverForServiceLost(
            String serviceType, CompletableFuture<NsdServiceInfo> serviceInfoFuture) {
        NsdManager.DiscoveryListener listener =
                new DefaultDiscoveryListener() {
                    @Override
                    public void onServiceLost(NsdServiceInfo serviceInfo) {
                        serviceInfoFuture.complete(serviceInfo);
                    }
                };
        mNsdManager.discoverServices(
                serviceType,
                NsdManager.PROTOCOL_DNS_SD,
                mTestNetworkTracker.getNetwork(),
                mExecutor,
                listener);
        return listener;
    }

    private NsdServiceInfo expectServiceResolved(
            String serviceType, int timeoutMilliseconds, Predicate<NsdServiceInfo> predicate)
            throws Exception {
        NsdServiceInfo discoveredServiceInfo = discoverService(serviceType);
        CompletableFuture<NsdServiceInfo> future = new CompletableFuture<>();
        NsdManager.ServiceInfoCallback callback =
                new DefaultServiceInfoCallback() {
                    @Override
                    public void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {
                        if (predicate.test(serviceInfo)) {
                            future.complete(serviceInfo);
                        }
                    }
                };
        mNsdManager.registerServiceInfoCallback(discoveredServiceInfo, mExecutor, callback);
        try {
            return future.get(timeoutMilliseconds, MILLISECONDS);
        } finally {
            mNsdManager.unregisterServiceInfoCallback(callback);
        }
    }

    private void setUpTestNetwork() {
        assertThat(mTestNetworkTracker).isNull();
        mTestNetworkTracker = new TapTestNetworkTracker(mContext, mHandlerThread.getLooper());
    }

    private void tearDownTestNetwork() throws InterruptedException {
        if (mTestNetworkTracker != null) {
            mTestNetworkTracker.tearDown();
        }
        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }

    private static class DefaultDiscoveryListener implements NsdManager.DiscoveryListener {
        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {}

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

        @Override
        public void onDiscoveryStarted(String serviceType) {}

        @Override
        public void onDiscoveryStopped(String serviceType) {}

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {}

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {}
    }

    private static class DefaultServiceInfoCallback implements NsdManager.ServiceInfoCallback {
        @Override
        public void onServiceInfoCallbackRegistrationFailed(int errorCode) {}

        @Override
        public void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {}

        @Override
        public void onServiceLost() {}

        @Override
        public void onServiceInfoCallbackUnregistered() {}
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_CHANNEL_MAX_POWERS_ENABLED})
    public void setChannelMaxPowers_withPrivilegedPermission_success() throws Exception {
        CompletableFuture<Void> powerFuture = new CompletableFuture<>();

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () ->
                        mController.setChannelMaxPowers(
                                CHANNEL_MAX_POWERS, mExecutor, newOutcomeReceiver(powerFuture)));

        try {
            assertThat(powerFuture.get()).isNull();
        } catch (ExecutionException exception) {
            ThreadNetworkException thrown = (ThreadNetworkException) exception.getCause();
            assertThat(thrown.getErrorCode()).isEqualTo(ERROR_UNSUPPORTED_FEATURE);
        }
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_CHANNEL_MAX_POWERS_ENABLED})
    public void setChannelMaxPowers_withoutPrivilegedPermission_throwsSecurityException()
            throws Exception {
        dropAllPermissions();

        assertThrows(
                SecurityException.class,
                () -> mController.setChannelMaxPowers(CHANNEL_MAX_POWERS, mExecutor, v -> {}));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_CHANNEL_MAX_POWERS_ENABLED})
    public void setChannelMaxPowers_invalidChannel_throwsIllegalArgumentException() {
        final SparseIntArray INVALID_CHANNEL_ARRAY =
                new SparseIntArray() {
                    {
                        put(INVALID_CHANNEL, VALID_POWER);
                    }
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.setChannelMaxPowers(new SparseIntArray(), mExecutor, v -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () -> mController.setChannelMaxPowers(INVALID_CHANNEL_ARRAY, mExecutor, v -> {}));
    }
}
