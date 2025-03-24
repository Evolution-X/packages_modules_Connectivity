/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.net.dhcp.IDhcpServer.STATUS_UNKNOWN_ERROR;

import android.app.AppOpsManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.net.IIntResultListener;
import android.net.INetworkStackConnector;
import android.net.ITetheringConnector;
import android.net.ITetheringEventCallback;
import android.net.NetworkStack;
import android.net.TetheringManager.TetheringRequest;
import android.net.TetheringRequestParcel;
import android.net.dhcp.DhcpServerCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.ip.IpServer;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.networkstack.apishim.SettingsShimImpl;
import com.android.networkstack.apishim.common.SettingsShim;
import com.android.networkstack.tethering.util.TetheringPermissionsUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Android service used to manage tethering.
 *
 * <p>The service returns a binder for the system server to communicate with the tethering.
 */
public class TetheringService extends Service {
    private static final String TAG = TetheringService.class.getSimpleName();

    private TetheringConnector mConnector;
    private SettingsShim mSettingsShim;
    private TetheringPermissionsUtils mTetheringPermissionsUtils;

    @Override
    public void onCreate() {
        final TetheringDependencies deps = makeTetheringDependencies();
        // The Tethering object needs a fully functional context to start, so this can't be done
        // in the constructor.
        mConnector = new TetheringConnector(makeTethering(deps), TetheringService.this);

        mSettingsShim = SettingsShimImpl.newInstance();
        mTetheringPermissionsUtils = new TetheringPermissionsUtils(deps.getContext());
    }

    /**
     * Make a reference to Tethering object.
     */
    @VisibleForTesting
    public Tethering makeTethering(TetheringDependencies deps) {
        return new Tethering(deps);
    }

    @NonNull
    @Override
    public IBinder onBind(Intent intent) {
        return mConnector;
    }

    private static class TetheringConnector extends ITetheringConnector.Stub {
        private final TetheringService mService;
        private final Tethering mTethering;

        TetheringConnector(Tethering tether, TetheringService service) {
            mTethering = tether;
            mService = service;
        }

        @Override
        public void tether(String iface, String callerPkg, String callingAttributionTag,
                IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, false /* isDeviceOwnerAppAllowed */,
                    listener)) {
                return;
            }

            mTethering.legacyTether(iface, listener);
        }

        @Override
        public void untether(String iface, String callerPkg, String callingAttributionTag,
                IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, false /* isDeviceOwnerAppAllowed */,
                    listener)) {
                return;
            }

            mTethering.legacyUntether(iface, listener);
        }

        @Override
        public void setUsbTethering(boolean enable, String callerPkg, String callingAttributionTag,
                IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, false /* isDeviceOwnerAppAllowed */,
                    listener)) {
                return;
            }

            mTethering.setUsbTethering(enable, listener);
        }

        private boolean isRequestAllowedForDOOrCarrierApp(@NonNull TetheringRequest request) {
            return request.getTetheringType() == TETHERING_WIFI
                    && request.getSoftApConfiguration() != null;
        }

        @Override
        public void startTethering(TetheringRequestParcel requestParcel, String callerPkg,
                String callingAttributionTag, IIntResultListener listener) {
            TetheringRequest request = new TetheringRequest(requestParcel);
            request.setUid(getBinderCallingUid());
            request.setPackageName(callerPkg);
            boolean onlyAllowPrivileged = request.isExemptFromEntitlementCheck()
                    || request.getInterfaceName() != null;
            boolean isDOOrCarrierAppAllowed = mTethering.isTetheringWithSoftApConfigEnabled()
                    && isRequestAllowedForDOOrCarrierApp(request);
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag, onlyAllowPrivileged,
                    isDOOrCarrierAppAllowed, listener)) {
                return;
            }
            mTethering.startTethering(request, callerPkg, listener);
        }

        @Override
        public void stopTethering(int type, String callerPkg, String callingAttributionTag,
                IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, false /* isDeviceOwnerAppAllowed */,
                    listener)) {
                return;
            }

            try {
                mTethering.stopTethering(type);
                listener.onResult(TETHER_ERROR_NO_ERROR);
            } catch (RemoteException e) { }
        }

        @Override
        public void stopTetheringRequest(TetheringRequest request,
                String callerPkg, String callingAttributionTag,
                IIntResultListener listener) {
            if (request == null) return;
            if (listener == null) return;
            request.setUid(getBinderCallingUid());
            request.setPackageName(callerPkg);
            boolean isDOOrCarrierAppAllowed = mTethering.isTetheringWithSoftApConfigEnabled()
                    && isRequestAllowedForDOOrCarrierApp(request);
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, isDOOrCarrierAppAllowed, listener)) {
                return;
            }
            // Note: Whether tethering is actually stopped or not will depend on whether the request
            // matches an active one with the same UID (see RequestTracker#findFuzzyMatchedRequest).
            mTethering.stopTetheringRequest(request, listener);
        }

        @Override
        public void requestLatestTetheringEntitlementResult(int type, ResultReceiver receiver,
                boolean showEntitlementUi, String callerPkg, String callingAttributionTag) {
            // Wrap the app-provided ResultReceiver in an IIntResultListener in order to call
            // checkAndNotifyCommonError with it.
            IIntResultListener listener = new IIntResultListener() {
                @Override
                public void onResult(int i) {
                    receiver.send(i, null);
                }

                @Override
                public IBinder asBinder() {
                    throw new UnsupportedOperationException("asBinder unexpectedly called on"
                            + " internal-only listener");
                }
            };
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, false /* isDeviceOwnerAppAllowed */,
                    listener)) {
                return;
            }

            mTethering.requestLatestTetheringEntitlementResult(type, receiver, showEntitlementUi);
        }

        @Override
        public void registerTetheringEventCallback(ITetheringEventCallback callback,
                String callerPkg) {
            // Silently ignore call if the callback is null. This can only happen via reflection.
            if (callback == null) return;
            try {
                if (!hasTetherAccessPermission()) {
                    callback.onCallbackStopped(TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);
                    return;
                }
                mTethering.registerTetheringEventCallback(callback);
            } catch (RemoteException e) { }
        }

        @Override
        public void unregisterTetheringEventCallback(ITetheringEventCallback callback,
                String callerPkg) {
            // Silently ignore call if the callback is null. This can only happen via reflection.
            if (callback == null) return;
            try {
                if (!hasTetherAccessPermission()) {
                    callback.onCallbackStopped(TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION);
                    return;
                }
                mTethering.unregisterTetheringEventCallback(callback);
            } catch (RemoteException e) { }
        }

        @Override
        public void stopAllTethering(String callerPkg, String callingAttributionTag,
                IIntResultListener listener) {
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, false /* isDeviceOwnerAppAllowed */,
                    listener)) {
                return;
            }

            try {
                mTethering.stopAllTethering();
                listener.onResult(TETHER_ERROR_NO_ERROR);
            } catch (RemoteException e) { }
        }

        @Override
        public void isTetheringSupported(String callerPkg, String callingAttributionTag,
                IIntResultListener listener) {
            boolean isDOOrCarrierAppAllowed = mTethering.isTetheringWithSoftApConfigEnabled();
            if (checkAndNotifyCommonError(callerPkg, callingAttributionTag,
                    false /* onlyAllowPrivileged */, isDOOrCarrierAppAllowed, listener)) {
                return;
            }
            try {
                listener.onResult(TETHER_ERROR_NO_ERROR);
            } catch (RemoteException e) { }
        }

        @Override
        public void setPreferTestNetworks(boolean prefer, IIntResultListener listener) {
            if (!checkCallingOrSelfPermission(NETWORK_SETTINGS)) {
                try {
                    listener.onResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
                } catch (RemoteException e) { }
                return;
            }

            mTethering.setPreferTestNetworks(prefer, listener);
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
                    @Nullable String[] args) {
            mTethering.dump(fd, writer, args);
        }

        private boolean checkAndNotifyCommonError(final String callerPkg,
                final String callingAttributionTag, final boolean onlyAllowPrivileged,
                final boolean isDOOrCarrierAppAllowed, final IIntResultListener listener) {
            try {
                final int uid = getBinderCallingUid();
                if (!checkPackageNameMatchesUid(uid, callerPkg)) {
                    Log.e(TAG, "Package name " + callerPkg + " does not match UID " + uid);
                    listener.onResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
                    return true;
                }
                if (!hasTetherChangePermission(uid, callerPkg, callingAttributionTag,
                        onlyAllowPrivileged, isDOOrCarrierAppAllowed)) {
                    listener.onResult(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION);
                    return true;
                }
                if (!mTethering.isTetheringSupported() || !mTethering.isTetheringAllowed()) {
                    listener.onResult(TETHER_ERROR_UNSUPPORTED);
                    return true;
                }
            } catch (RemoteException e) {
                return true;
            }

            return false;
        }

        private boolean hasNetworkSettingsPermission() {
            return checkCallingOrSelfPermission(NETWORK_SETTINGS);
        }

        private boolean hasNetworkStackPermission() {
            return checkCallingOrSelfPermission(NETWORK_STACK)
                    || checkCallingOrSelfPermission(PERMISSION_MAINLINE_NETWORK_STACK);
        }

        private boolean hasTetherPrivilegedPermission() {
            return checkCallingOrSelfPermission(TETHER_PRIVILEGED);
        }

        private boolean checkCallingOrSelfPermission(final String permission) {
            return mService.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED;
        }

        private boolean hasTetherChangePermission(final int uid, final String callerPkg,
                final String callingAttributionTag, final boolean onlyAllowPrivileged,
                final boolean isDOOrCarrierAppAllowed) {
            if (onlyAllowPrivileged && !hasNetworkStackPermission()
                    && !hasNetworkSettingsPermission()) return false;

            if (hasTetherPrivilegedPermission()) return true;

            // Allow DO and carrier-privileged apps to change tethering even if they don't have
            // TETHER_PRIVILEGED.
            // TODO: Stop tethering if the app loses DO status or carrier-privileges.
            if (isDOOrCarrierAppAllowed
                    && (mService.isDeviceOwner(uid, callerPkg)
                            || mService.isCarrierPrivileged(callerPkg))) {
                return true;
            }

            // After TetheringManager moves to public API, prevent third-party apps from being able
            // to change tethering with only WRITE_SETTINGS permission.
            if (mTethering.isTetheringWithSoftApConfigEnabled()) return false;

            if (mTethering.isTetherProvisioningRequired()) return false;

            // If callerPkg's uid is not same as getBinderCallingUid(),
            // checkAndNoteWriteSettingsOperation will return false and the operation will be
            // denied.
            return mService.checkAndNoteWriteSettingsOperation(mService, uid, callerPkg,
                    callingAttributionTag, false /* throwException */);
        }

        private boolean hasTetherAccessPermission() {
            if (hasTetherPrivilegedPermission()) return true;

            return mService.checkCallingOrSelfPermission(
                    ACCESS_NETWORK_STATE) == PERMISSION_GRANTED;
        }

        private int getBinderCallingUid() {
            return mService.getBinderCallingUid();
        }

        private boolean checkPackageNameMatchesUid(final int uid, final String callerPkg) {
            return mService.checkPackageNameMatchesUid(mService, uid, callerPkg);
        }
    }

    /**
     * Check if the package is a allowed to write settings. This also accounts that such an access
     * happened.
     *
     * @return {@code true} iff the package is allowed to write settings.
     */
    @VisibleForTesting
    boolean checkAndNoteWriteSettingsOperation(@NonNull Context context, int uid,
            @NonNull String callingPackage, @Nullable String callingAttributionTag,
            boolean throwException) {
        return mSettingsShim.checkAndNoteWriteSettingsOperation(context, uid, callingPackage,
                callingAttributionTag, throwException);
    }

    /**
     * Check if the package name matches the uid.
     */
    @VisibleForTesting
    boolean checkPackageNameMatchesUid(@NonNull Context context, int uid,
            @NonNull String callingPackage) {
        try {
            final AppOpsManager mAppOps = context.getSystemService(AppOpsManager.class);
            if (mAppOps == null) {
                return false;
            }
            mAppOps.checkPackage(uid, callingPackage);
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /**
     * Wrapper for the Binder calling UID, used for mocks.
     */
    @VisibleForTesting
    int getBinderCallingUid() {
        return Binder.getCallingUid();
    }

    /**
     * Wrapper for {@link TetheringPermissionsUtils#isDeviceOwner(int, String)}, used for mocks.
     */
    @VisibleForTesting
    boolean isDeviceOwner(final int uid, final String callerPkg) {
        return mTetheringPermissionsUtils.isDeviceOwner(uid, callerPkg);
    }

    /**
     * Wrapper for {@link TetheringPermissionsUtils#isCarrierPrivileged(String)}, used for mocks.
     */
    @VisibleForTesting
    boolean isCarrierPrivileged(final String callerPkg) {
        return mTetheringPermissionsUtils.isCarrierPrivileged(callerPkg);
    }

    /**
     * An injection method for testing.
     */
    @VisibleForTesting
    public TetheringDependencies makeTetheringDependencies() {
        return new TetheringDependencies() {
            @Override
            public Looper makeTetheringLooper() {
                final HandlerThread tetherThread = new HandlerThread("android.tethering");
                tetherThread.start();
                return tetherThread.getLooper();
            }

            @Override
            public Context getContext() {
                return TetheringService.this;
            }

            @Override
            public IpServer.Dependencies makeIpServerDependencies() {
                return new IpServer.Dependencies() {
                    @Override
                    public void makeDhcpServer(String ifName, DhcpServingParamsParcel params,
                            DhcpServerCallbacks cb) {
                        try {
                            final INetworkStackConnector service = getNetworkStackConnector();
                            if (service == null) return;

                            service.makeDhcpServer(ifName, params, cb);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Fail to make dhcp server");
                            try {
                                cb.onDhcpServerCreated(STATUS_UNKNOWN_ERROR, null);
                            } catch (RemoteException re) { }
                        }
                    }
                };
            }

            // TODO: replace this by NetworkStackClient#getRemoteConnector after refactoring
            // networkStackClient.
            static final int NETWORKSTACK_TIMEOUT_MS = 60_000;
            private INetworkStackConnector getNetworkStackConnector() {
                IBinder connector;
                try {
                    final long before = System.currentTimeMillis();
                    while ((connector = NetworkStack.getService()) == null) {
                        if (System.currentTimeMillis() - before > NETWORKSTACK_TIMEOUT_MS) {
                            Log.wtf(TAG, "Timeout, fail to get INetworkStackConnector");
                            return null;
                        }
                        Thread.sleep(200);
                    }
                } catch (InterruptedException e) {
                    Log.wtf(TAG, "Interrupted, fail to get INetworkStackConnector");
                    return null;
                }
                return INetworkStackConnector.Stub.asInterface(connector);
            }

            @Override
            public BluetoothAdapter getBluetoothAdapter() {
                final BluetoothManager btManager = getSystemService(BluetoothManager.class);
                if (btManager == null) {
                    return null;
                }
                return btManager.getAdapter();
            }
        };
    }
}
