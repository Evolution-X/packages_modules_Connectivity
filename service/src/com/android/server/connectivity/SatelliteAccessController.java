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

package com.android.server.connectivity;

import static android.os.Process.INVALID_UID;

import static com.android.server.connectivity.ConnectivityFlags.CONSTRAINED_DATA_SATELLITE_OPTIN;

import android.Manifest;
import android.annotation.NonNull;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.DeviceConfigUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import javax.annotation.CheckReturnValue;

/**
 * Tracks the uid of all the default messaging application which are role_sms role and
 * satellite_communication permission complaint and requests ConnectivityService to create multi
 * layer request with satellite internet access support for the default message application.
 */
public class SatelliteAccessController {
    private static final String TAG = SatelliteAccessController.class.getSimpleName();
    // Shamelessly copied from telephony/satellite/SatelliteManager.java.
    @VisibleForTesting
    public static final String PROPERTY_SATELLITE_DATA_OPTIMIZED =
            "android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED";
    // This value is taken from android.os.UserHandle#PER_USER_RANGE.
    @VisibleForTesting
    public static final int PER_USER_RANGE = 100000;
    private final Context mContext;
    private final Dependencies mDeps;
    private final DefaultMessageRoleListener mDefaultMessageRoleListener;
    private final BiConsumer<Set<Integer>, Set<Integer>> mCallback;
    private final Handler mConnectivityServiceHandler;
    private final PackageManager mPackageManager;
    private final boolean mSupportConstrainedDataSatelliteOptIn;

    // At this sparseArray, Key is userId and values are uids of SMS apps that are allowed
    // to use satellite network as fallback.
    private final SparseArray<Set<Integer>> mAllUsersSatelliteNetworkFallbackUidCache =
            new SparseArray<>();

    // Set of UIDs that have declared the
    // {@code android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED} property
    // with a value of package name in their manifest file. This variable will only be
    // accessed on the handler thread.
    private final Set<Integer> mSatelliteDataOptimizedUids = new ArraySet<>();

    private final ArrayMap<UserHandle, PackageManager> mUserPackageManagers = new ArrayMap<>();

    /**
     *  Monitor {@link android.app.role.OnRoleHoldersChangedListener#onRoleHoldersChanged(String,
     *  UserHandle)},
     *
     */
    private final class DefaultMessageRoleListener
            implements OnRoleHoldersChangedListener {
        @Override
        public void onRoleHoldersChanged(String role, UserHandle userHandle) {
            if (RoleManager.ROLE_SMS.equals(role)) {
                Log.i(TAG, "ROLE_SMS Change detected ");
                onRoleSmsChanged(userHandle);
            }
        }

        public void register() {
            try {
                mDeps.addOnRoleHoldersChangedListenerAsUser(
                        mConnectivityServiceHandler::post, this, UserHandle.ALL);
            } catch (RuntimeException e) {
                Log.wtf(TAG, "Could not register satellite controller listener due to " + e);
            }
        }
    }

    public SatelliteAccessController(@NonNull final Context c,
            BiConsumer<Set<Integer>, Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, new Dependencies(c), callback, connectivityServiceInternalHandler);
    }

    public static class Dependencies {
        private final RoleManager mRoleManager;

        private Dependencies(Context context) {
            mRoleManager = context.getSystemService(RoleManager.class);
        }

        /** See {@link RoleManager#getRoleHoldersAsUser(String, UserHandle)} */
        public List<String> getRoleHoldersAsUser(String roleName, UserHandle userHandle) {
            return mRoleManager.getRoleHoldersAsUser(roleName, userHandle);
        }

        /** See {@link RoleManager#addOnRoleHoldersChangedListenerAsUser} */
        public void addOnRoleHoldersChangedListenerAsUser(@NonNull Executor executor,
                @NonNull OnRoleHoldersChangedListener listener, UserHandle user) {
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(executor, listener, user);
        }

        /** Return whether constrained data satellite opt-in is supported. */
        public boolean supportConstrainedDataSatelliteOptIn(Context context) {
            return DeviceConfigUtils.isTetheringFeatureNotChickenedOut(context,
                    CONSTRAINED_DATA_SATELLITE_OPTIN);
        }
    }

    @VisibleForTesting
    SatelliteAccessController(@NonNull final Context c, @NonNull final Dependencies deps,
            BiConsumer<Set<Integer>, Set<Integer>> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mContext = c;
        mDeps = deps;
        mDefaultMessageRoleListener = new DefaultMessageRoleListener();
        mCallback = callback;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
        mPackageManager = mContext.getPackageManager();
        mSupportConstrainedDataSatelliteOptIn = mDeps.supportConstrainedDataSatelliteOptIn(c);
    }

    // TODO: Rename to updateSatelliteSmsRoleUidListCache since Opt-In apps are also
    //  fallback uids.
    private Set<Integer> updateSatelliteNetworkFallbackUidListCache(List<String> packageNames,
            @NonNull UserHandle userHandle) {
        Set<Integer> fallbackUids = new ArraySet<>();
        PackageManager pm =
                mContext.createContextAsUser(userHandle, 0).getPackageManager();
        if (pm != null) {
            for (String packageName : packageNames) {
                // Check if SATELLITE_COMMUNICATION permission is enabled for default sms
                // application package before adding it part of satellite network fallback uid
                // cache list.
                if (isSatellitePermissionEnabled(pm, packageName)) {
                    int uid = getUidForPackage(pm, packageName);
                    if (uid != INVALID_UID) {
                        fallbackUids.add(uid);
                    }
                }
            }
        } else {
            Log.wtf(TAG, "package manager found null");
        }
        return fallbackUids;
    }

    //Check if satellite communication is enabled for the package
    private boolean isSatellitePermissionEnabled(PackageManager packageManager,
            String packageName) {
        return packageManager.checkPermission(
                Manifest.permission.SATELLITE_COMMUNICATION, packageName)
                == PackageManager.PERMISSION_GRANTED;
    }

    private int getUidForPackage(PackageManager packageManager, String pkgName) {
        if (pkgName == null) {
            return INVALID_UID;
        }
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(pkgName, 0);
            return applicationInfo.uid;
        } catch (PackageManager.NameNotFoundException exception) {
            Log.e(TAG, "Unable to find uid for package: " + pkgName);
        }
        return INVALID_UID;
    }

    // on Role sms change triggered by OnRoleHoldersChangedListener()
    private void onRoleSmsChanged(@NonNull UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        if (userId == INVALID_UID) {
            Log.wtf(TAG, "Invalid User Id");
            return;
        }

        //Returns empty list if no package exists
        final List<String> packageNames =
                mDeps.getRoleHoldersAsUser(RoleManager.ROLE_SMS, userHandle);

        // Store previous satellite fallback uid available
        final Set<Integer> prevUidsForUser =
                mAllUsersSatelliteNetworkFallbackUidCache.get(userId, new ArraySet<>());

        Log.i(TAG, "currentUser : role_sms_packages: " + userId + " : " + packageNames);
        final Set<Integer> newUidsForUser =
                updateSatelliteNetworkFallbackUidListCache(packageNames, userHandle);
        Log.i(TAG, "satellite_fallback_uid: " + newUidsForUser);

        // on Role change, update the multilayer request at ConnectivityService with updated
        // satellite network fallback uid cache list of multiple users as applicable
        if (newUidsForUser.equals(prevUidsForUser)) {
            return;
        }

        mAllUsersSatelliteNetworkFallbackUidCache.put(userId, newUidsForUser);

        // Update all users fallback cache for user, send cs fallback to update ML request
        reportSatelliteNetworkFallbackUids();
    }

    private void reportSatelliteNetworkFallbackUids() {
        // Merge all uids of multiple users available
        Set<Integer> mergedSatelliteNetworkFallbackUidCache = new ArraySet<>();
        for (int i = 0; i < mAllUsersSatelliteNetworkFallbackUidCache.size(); i++) {
            mergedSatelliteNetworkFallbackUidCache.addAll(
                    mAllUsersSatelliteNetworkFallbackUidCache.valueAt(i));
        }
        Log.i(TAG, "SmsRoleUids: " + mergedSatelliteNetworkFallbackUidCache
                + " Opt-InUids:" + mSatelliteDataOptimizedUids);

        // trigger multiple layer request for satellite network fallback of multi user uids
        final ArraySet<Integer> optimizedApps = new ArraySet(mSatelliteDataOptimizedUids);
        optimizedApps.removeAll(mergedSatelliteNetworkFallbackUidCache);
        mCallback.accept(mergedSatelliteNetworkFallbackUidCache, optimizedApps);
    }

    public void start() {
        // register sms OnRoleHoldersChangedListener
        mDefaultMessageRoleListener.register();
    }

    @CheckReturnValue
    private boolean updateSatelliteFallbackUidListOnUserRemoval(int userIdRemoved) {
        Log.i(TAG, "user id removed:" + userIdRemoved);
        if (mAllUsersSatelliteNetworkFallbackUidCache.contains(userIdRemoved)) {
            mAllUsersSatelliteNetworkFallbackUidCache.remove(userIdRemoved);
            return true; // Changed.
        }
        return false; // Unchanged.
    }

    /**
     * Called when a user is added. See {link #ACTION_USER_ADDED}.
     *
     * Note that this method will also be called at start up once on the handler thread
     * to iterate through all existing users.
     *
     * @param userHandle The userHandle of the added user. See {@link #EXTRA_USER_HANDLE}.
     * @param apps The list of packages which is installed on the user.
     */
    public void onUserAddedWithInstalledPackageList(@NonNull UserHandle userHandle,
            @NonNull List<PackageInfo> apps) {
        // Obtain uids with role sms and satellite communication permission for the added user.
        onRoleSmsChanged(userHandle);

        // Store PackageManager for user for later use.
        final PackageManager pmForUser =
                mContext.createContextAsUser(userHandle, 0 /* flag */).getPackageManager();
        mUserPackageManagers.put(userHandle, pmForUser);

        if (!mSupportConstrainedDataSatelliteOptIn) return;

        final Set<Integer> satelliteDataOptimizedAppsForUser =
                getSatelliteDataOptimizedAppsForUser(apps);
        Log.i(TAG, "Add SatelliteDataOptimizedApps + for user " + userHandle + ": "
                + satelliteDataOptimizedAppsForUser);
        if (satelliteDataOptimizedAppsForUser.size() > 0) {
            mSatelliteDataOptimizedUids.addAll(satelliteDataOptimizedAppsForUser);
            reportSatelliteNetworkFallbackUids();
        }
    }

    /**
     * Called when a user is removed. See {link #ACTION_USER_REMOVED}.
     *
     * @param userHandle The integer userHandle of the removed user. See {@link #EXTRA_USER_HANDLE}.
     */
    public void onUserRemoved(@NonNull UserHandle userHandle) {
        final boolean smsRoleUidsChanged =
                updateSatelliteFallbackUidListOnUserRemoval(userHandle.getIdentifier());
        final boolean mDataOptimizedUidChanged;
        mUserPackageManagers.remove(userHandle);
        if (mSupportConstrainedDataSatelliteOptIn) {
            mDataOptimizedUidChanged =
                    removeSatelliteDataOptimizedUidsForUser(userHandle.getIdentifier());
        } else {
            mDataOptimizedUidChanged = false;
        }
        if (smsRoleUidsChanged || mDataOptimizedUidChanged) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    /**
     * Called when a package is added.
     *
     * @param packageName The name of the new package.
     * @param uid The uid of the new package.
     */
    public void onPackageAdded(@NonNull final String packageName, final int uid) {
        if (!mSupportConstrainedDataSatelliteOptIn) return;
        if (addSatelliteDataOptimizedUid(packageName, uid)) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    @CheckReturnValue
    private boolean addSatelliteDataOptimizedUid(@NonNull final String packageName, final int uid) {
        if (mSupportConstrainedDataSatelliteOptIn && isSatelliteDataOptimizedApp(packageName)) {
            mSatelliteDataOptimizedUids.add(uid);
            return true;
        }
        return false;
    }

    /**
     * Called when the availability of external applications changes.
     *
     * @param pkgList An array of package names that have become available.
     */
    public void onExternalApplicationsAvailable(String[] pkgList) {
        if (!mSupportConstrainedDataSatelliteOptIn) return;
        if (CollectionUtils.isEmpty(pkgList)) {
            Log.e(TAG, "No available external application.");
            return;
        }

        boolean added = false;
        for (String app : pkgList) {
            for (final PackageManager pm : mUserPackageManagers.values()) {
                final int uid = getUidForPackage(pm, app);
                if (uid != INVALID_UID && addSatelliteDataOptimizedUid(app, uid)) {
                    added = true;
                }
            }
        }
        if (added) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    /**
     * Called when a package is removed.
     *
     * @param packageName The name of the removed package or null.
     * @param uid containing the integer uid previously assigned to the package.
     */
    public void onPackageRemoved(@NonNull final String packageName, final int uid) {
        if (!mSupportConstrainedDataSatelliteOptIn) return;

        // Scan for all apps sharing the same uid.
        final String [] pkgs = mPackageManager.getPackagesForUid(uid);
        if (pkgs != null) {
            for (String pkg : pkgs) {
                if (!pkg.equals(packageName) && isSatelliteDataOptimizedApp(pkg)) {
                    return; // Early return if another satellite-optimized app shares the UID
                }
            }
        }
        // If the loop completes without returning, it means no other
        // satellite-optimized app shares the UID.
        final boolean removed = mSatelliteDataOptimizedUids.remove(uid);
        if (removed) {
            reportSatelliteNetworkFallbackUids();
        }
    }

    @NonNull
    private Set<Integer> getSatelliteDataOptimizedAppsForUser(@NonNull List<PackageInfo> apps) {
        final ArraySet<Integer> uids = new ArraySet<>();
        for (PackageInfo app : apps) {
            if (null == app.applicationInfo || app.applicationInfo.uid < 0) continue;
            if (isSatelliteDataOptimizedApp(app.packageName)) uids.add(app.applicationInfo.uid);
        }
        return uids;
    }

    private boolean isSatelliteDataOptimizedApp(@NonNull String packageName) {
        try {
            final PackageManager.Property property = mPackageManager
                    .getProperty(PROPERTY_SATELLITE_DATA_OPTIMIZED, packageName);
            return property.isString() && TextUtils.equals(property.getString(), packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // Return true if changed.
    @CheckReturnValue
    private boolean removeSatelliteDataOptimizedUidsForUser(int userIdToRemove) {
        return mSatelliteDataOptimizedUids.removeIf(uid -> uid / PER_USER_RANGE == userIdToRemove);
    }

    /** Dump info to dumpsys */
    public void dump(@NonNull IndentingPrintWriter pw) {
        pw.println("SatelliteAccessController:");
        pw.increaseIndent();
        pw.print("Sms-Role Uids: ");
        pw.print(mAllUsersSatelliteNetworkFallbackUidCache);
        pw.println();
        pw.print("Opt-In Uids: ");
        pw.print(mSatelliteDataOptimizedUids);
        pw.println();
        pw.decreaseIndent();
        pw.println();
    }
}
