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

package com.android.networkstack.tethering.util;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Utils class for checking permissions related to Tethering APIs.
 */
public class TetheringPermissionsUtils {
    private static final String TAG = "TetherPermUtils";

    @NonNull private final Context mContext;

    public TetheringPermissionsUtils(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Checks if the package name is a Device Owner.
     */
    public boolean isDeviceOwner(final int uid, @NonNull final String packageName) {
        Context userContext;
        try {
            // There is no safe way to invoke this method since tethering package might not be
            // installed for a certain user on the OEM devices, refer to b/382628161.
            userContext = mContext.createContextAsUser(UserHandle.getUserHandleForUid(uid),
                    0 /* flags */);
        } catch (IllegalStateException e) {
            // TODO: Add a terrible error metric for this case.
            Log.e(TAG, "createContextAsUser failed, skipping Device Owner check", e);
            return false;
        }
        DevicePolicyManager devicePolicyManager =
                retrieveDevicePolicyManagerFromContext(userContext);
        if (devicePolicyManager == null) return false;
        return devicePolicyManager.isDeviceOwnerApp(packageName);
    }

    private DevicePolicyManager retrieveDevicePolicyManagerFromContext(
            @NonNull final Context context) {
        DevicePolicyManager devicePolicyManager =
                context.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager == null
                && context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_DEVICE_ADMIN)) {
            Log.w(TAG, "Error retrieving DPM service");
        }
        return devicePolicyManager;
    }
}
