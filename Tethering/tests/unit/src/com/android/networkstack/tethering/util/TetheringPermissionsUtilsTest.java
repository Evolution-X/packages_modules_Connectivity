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

package com.android.networkstack.tethering.util;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.UserHandle;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringPermissionsUtilsTest {
    private static final int TEST_UID = 12345;
    private static final String TEST_PACKAGE = "test.package";

    @Mock Context mContext;
    @Mock Context mUserContext;
    @Mock DevicePolicyManager mDevicePolicyManager;
    @Mock TelephonyManager mTelephonyManager;

    TetheringPermissionsUtils mTetheringPermissionsUtils;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.createContextAsUser(UserHandle.getUserHandleForUid(TEST_UID), 0))
                .thenReturn(mUserContext);
        when(mUserContext.getSystemService(DevicePolicyManager.class))
                .thenReturn(mDevicePolicyManager);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        mTetheringPermissionsUtils = new TetheringPermissionsUtils(mContext);
    }

    @Test
    public void testIsDeviceOwner() {
        when(mDevicePolicyManager.isDeviceOwnerApp(TEST_PACKAGE)).thenReturn(false);
        assertThat(mTetheringPermissionsUtils.isDeviceOwner(TEST_UID, TEST_PACKAGE)).isFalse();

        when(mDevicePolicyManager.isDeviceOwnerApp(TEST_PACKAGE)).thenReturn(true);
        assertThat(mTetheringPermissionsUtils.isDeviceOwner(TEST_UID, TEST_PACKAGE)).isTrue();
    }

    @Test
    public void testHasCarrierPrivilege() {
        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(TEST_PACKAGE))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        assertThat(mTetheringPermissionsUtils.isCarrierPrivileged(TEST_PACKAGE)).isFalse();

        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(TEST_PACKAGE))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        assertThat(mTetheringPermissionsUtils.isCarrierPrivileged(TEST_PACKAGE)).isTrue();
    }
}
