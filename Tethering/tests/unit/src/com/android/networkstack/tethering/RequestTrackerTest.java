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

package com.android.networkstack.tethering;

import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import static com.google.common.truth.Truth.assertThat;

import android.net.TetheringManager.TetheringRequest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.networkstack.tethering.RequestTracker.AddResult;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RequestTrackerTest {
    private RequestTracker mRequestTracker;

    @Before
    public void setUp() {
        mRequestTracker = new RequestTracker();
    }

    @Test
    public void testNoRequestsAdded_noPendingRequests() {
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testAddRequest_successResultAndBecomesNextPending() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();

        final AddResult result = mRequestTracker.addPendingRequest(request);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_equalRequestExists_successResultAndBecomesNextPending() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        mRequestTracker.addPendingRequest(request);

        final TetheringRequest equalRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final AddResult result = mRequestTracker.addPendingRequest(equalRequest);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_equalButDifferentUidRequest_successResultAndBecomesNextPending() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        request.setUid(1000);
        request.setPackageName("package");
        final TetheringRequest differentUid = new TetheringRequest.Builder(TETHERING_WIFI).build();
        differentUid.setUid(2000);
        differentUid.setPackageName("package2");
        mRequestTracker.addPendingRequest(request);

        final AddResult result = mRequestTracker.addPendingRequest(differentUid);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(differentUid);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(differentUid);
    }

    @Test
    public void testAddConflictingRequest_returnsFailureConflictingPendingRequest() {
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final TetheringRequest conflictingRequest = new TetheringRequest.Builder(TETHERING_WIFI)
                .setExemptFromEntitlementCheck(true).build();
        mRequestTracker.addPendingRequest(request);

        final AddResult result = mRequestTracker.addPendingRequest(conflictingRequest);

        assertThat(result).isEqualTo(AddResult.FAILURE_CONFLICTING_PENDING_REQUEST);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testRemoveAllPendingRequests_noPendingRequestsLeft() {
        final TetheringRequest firstRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        firstRequest.setUid(1000);
        firstRequest.setPackageName("package");
        mRequestTracker.addPendingRequest(firstRequest);
        final TetheringRequest secondRequest = new TetheringRequest.Builder(TETHERING_WIFI).build();
        secondRequest.setUid(2000);
        secondRequest.setPackageName("package2");
        mRequestTracker.addPendingRequest(secondRequest);

        mRequestTracker.removeAllPendingRequests(TETHERING_WIFI);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testRemoveAllPendingRequests_differentTypeExists_doesNotRemoveDifferentType() {
        final TetheringRequest differentType = new TetheringRequest.Builder(TETHERING_USB).build();
        mRequestTracker.addPendingRequest(differentType);

        mRequestTracker.removeAllPendingRequests(TETHERING_WIFI);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_USB)).isEqualTo(differentType);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_USB))
                .isEqualTo(differentType);
    }
}
