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
import static android.net.TetheringManager.TETHERING_VIRTUAL;

import static com.android.networkstack.tethering.util.TetheringUtils.createPlaceholderRequest;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.net.TetheringManager.TetheringRequest;
import android.net.ip.IpServer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.networkstack.tethering.RequestTracker.AddResult;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RequestTrackerTest {
    private RequestTracker mRequestTracker;

    @Test
    public void testNoRequestsAdded_noPendingRequests() {
        mRequestTracker = new RequestTracker(false);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testAddRequest_successResultAndBecomesNextPending() {
        mRequestTracker = new RequestTracker(false);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();

        final AddResult result = mRequestTracker.addPendingRequest(request);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_equalRequestExists_successResultAndBecomesNextPending() {
        mRequestTracker = new RequestTracker(false);
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
        mRequestTracker = new RequestTracker(false);
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
    public void testAddRequest_conflictingPendingRequest_returnsFailureConflictingRequestRestart() {
        mRequestTracker = new RequestTracker(false);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final TetheringRequest conflictingRequest = new TetheringRequest.Builder(TETHERING_WIFI)
                .setExemptFromEntitlementCheck(true).build();
        mRequestTracker.addPendingRequest(request);

        final AddResult result = mRequestTracker.addPendingRequest(conflictingRequest);

        assertThat(result).isEqualTo(AddResult.FAILURE_DUPLICATE_REQUEST_RESTART);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_noExistingRequestsFuzzyMatching_returnsSuccess() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();

        final AddResult result = mRequestTracker.addPendingRequest(request);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_conflictingPendingRequestFuzzyMatching_returnsFailure() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final TetheringRequest conflictingRequest = new TetheringRequest.Builder(TETHERING_WIFI)
                .setExemptFromEntitlementCheck(true).build();
        mRequestTracker.addPendingRequest(request);

        final AddResult result = mRequestTracker.addPendingRequest(conflictingRequest);

        assertThat(result).isEqualTo(AddResult.FAILURE_DUPLICATE_REQUEST_ERROR);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testAddRequest_conflictingServingRequestFuzzyMatching_returnsFailure() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        final TetheringRequest conflictingRequest = new TetheringRequest.Builder(TETHERING_WIFI)
                .setExemptFromEntitlementCheck(true).build();
        mRequestTracker.promoteRequestToServing(mock(IpServer.class), request);

        final AddResult result = mRequestTracker.addPendingRequest(conflictingRequest);

        assertThat(result).isEqualTo(AddResult.FAILURE_DUPLICATE_REQUEST_ERROR);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testAddRequest_nonMatchingPendingRequestFuzzyMatching_returnsSuccess() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_VIRTUAL).build();
        final TetheringRequest nonFuzzyMatched = new TetheringRequest.Builder(TETHERING_VIRTUAL)
                .setInterfaceName("iface")
                .build();
        mRequestTracker.addPendingRequest(request);

        final AddResult result = mRequestTracker.addPendingRequest(nonFuzzyMatched);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        // Next request is still the first, but verify RequestTracker contains the second request by
        // seeing if it rejects anything matching the second request
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_VIRTUAL)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_VIRTUAL))
                .isEqualTo(request);
        assertThat(mRequestTracker.addPendingRequestFuzzyMatched(nonFuzzyMatched))
                .isEqualTo(AddResult.FAILURE_DUPLICATE_REQUEST_ERROR);
    }

    @Test
    public void testAddRequest_nonMatchingServingRequestFuzzyMatching_returnsSuccess() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_VIRTUAL).build();
        final TetheringRequest nonFuzzyMatched = new TetheringRequest.Builder(TETHERING_VIRTUAL)
                .setInterfaceName("iface")
                .build();
        mRequestTracker.promoteRequestToServing(mock(IpServer.class), request);

        final AddResult result = mRequestTracker.addPendingRequest(nonFuzzyMatched);

        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_VIRTUAL))
                .isEqualTo(nonFuzzyMatched);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_VIRTUAL))
                .isEqualTo(nonFuzzyMatched);
    }

    @Test
    public void testRemovePendingRequest_removesAllPendingRequestsOfType() {
        mRequestTracker = new RequestTracker(false);
        final TetheringRequest request1 = new TetheringRequest.Builder(TETHERING_WIFI).build();
        request1.setUid(1000);
        request1.setPackageName("package");
        mRequestTracker.addPendingRequest(request1);
        final TetheringRequest request2 = new TetheringRequest.Builder(TETHERING_WIFI).build();
        request2.setUid(2000);
        request2.setPackageName("package2");

        mRequestTracker.removePendingRequest(request2);

        // Verify request1 isn't pending even though we tried to remove a different request
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testRemovePendingRequest_fuzzyMatching_onlyTheEqualRequestIsRemoved() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request1 = new TetheringRequest.Builder(TETHERING_VIRTUAL).build();
        final TetheringRequest request2 = new TetheringRequest.Builder(TETHERING_VIRTUAL)
                .setInterfaceName("iface")
                .build();
        mRequestTracker.addPendingRequest(request1);
        mRequestTracker.addPendingRequest(request2);

        mRequestTracker.removePendingRequest(request2);

        // Verify request1 is still pending.
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_VIRTUAL)).isEqualTo(request1);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_VIRTUAL))
                .isEqualTo(request1);
        assertThat(mRequestTracker.addPendingRequestFuzzyMatched(request1))
                .isEqualTo(AddResult.FAILURE_DUPLICATE_REQUEST_ERROR);
        // Verify we've removed request2 by checking if it can be added back without
        // FAILURE_CONFLICTING_REQUEST_FAIL.
        assertThat(mRequestTracker.addPendingRequestFuzzyMatched(request2))
                .isEqualTo(AddResult.SUCCESS);
    }

    @Test
    public void testRemoveAllPendingRequests_noPendingRequestsLeft() {
        mRequestTracker = new RequestTracker(false);
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
        mRequestTracker = new RequestTracker(false);
        final TetheringRequest differentType = new TetheringRequest.Builder(TETHERING_USB).build();
        mRequestTracker.addPendingRequest(differentType);

        mRequestTracker.removeAllPendingRequests(TETHERING_WIFI);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_USB)).isEqualTo(differentType);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_USB))
                .isEqualTo(differentType);
    }

    @Test
    public void testPromoteRequestToServing_requestIsntPendingAnymore() {
        mRequestTracker = new RequestTracker(false);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        mRequestTracker.addPendingRequest(request);

        mRequestTracker.promoteRequestToServing(mock(IpServer.class), request);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testPromoteRequestToServing_fuzzyMatching_requestIsntPendingAnymore() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        mRequestTracker.addPendingRequest(request);

        mRequestTracker.promoteRequestToServing(mock(IpServer.class), request);

        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isNull();
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI))
                .isEqualTo(createPlaceholderRequest(TETHERING_WIFI));
    }

    @Test
    public void testRemoveServingRequest_fuzzyMatching_requestCanBeAddedAgain() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        mRequestTracker.addPendingRequest(request);
        IpServer ipServer = mock(IpServer.class);
        mRequestTracker.promoteRequestToServing(ipServer, request);

        mRequestTracker.removeServingRequest(ipServer);

        AddResult result = mRequestTracker.addPendingRequest(request);
        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }

    @Test
    public void testRemoveAllServingRequests_fuzzyMatching_requestCanBeAddedAgain() {
        mRequestTracker = new RequestTracker(true);
        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
        mRequestTracker.addPendingRequest(request);
        mRequestTracker.promoteRequestToServing(mock(IpServer.class), request);

        mRequestTracker.removeAllServingRequests(TETHERING_WIFI);

        AddResult result = mRequestTracker.addPendingRequest(request);
        assertThat(result).isEqualTo(AddResult.SUCCESS);
        assertThat(mRequestTracker.getNextPendingRequest(TETHERING_WIFI)).isEqualTo(request);
        assertThat(mRequestTracker.getOrCreatePendingRequest(TETHERING_WIFI)).isEqualTo(request);
    }
}
