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

package android.net.thread;

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;

/** Tests for {@link ThreadNetworkSpecifier}. */
@SmallTest
@RunWith(Parameterized.class)
public final class ThreadNetworkSpecifierTest {
    public final byte[] mExtendedPanId;
    public final OperationalDatasetTimestamp mActiveTimestamp;
    public final boolean mRouterEligibleForLeader;

    @Parameterized.Parameters
    public static Collection specifierArguments() {
        var timestampNow = OperationalDatasetTimestamp.fromInstant(Instant.now());
        return Arrays.asList(
                new Object[][] {
                    {new byte[] {0, 1, 2, 3, 4, 5, 6, 7}, null, false},
                    {new byte[] {1, 1, 1, 1, 2, 2, 2, 2}, timestampNow, true},
                    {new byte[] {1, 1, 1, 1, 2, 2, 2, 2}, timestampNow, false},
                });
    }

    public ThreadNetworkSpecifierTest(
            byte[] extendedPanId,
            OperationalDatasetTimestamp activeTimestamp,
            boolean routerEligibleForLeader) {
        mExtendedPanId = extendedPanId.clone();
        mActiveTimestamp = activeTimestamp;
        mRouterEligibleForLeader = routerEligibleForLeader;
    }

    @Test
    public void parcelable_parcelingIsLossLess() {
        ThreadNetworkSpecifier specifier =
                new ThreadNetworkSpecifier.Builder(mExtendedPanId)
                        .setActiveTimestamp(mActiveTimestamp)
                        .setRouterEligibleForLeader(mRouterEligibleForLeader)
                        .build();
        assertParcelingIsLossless(specifier);
    }

    @Test
    public void builder_correctValuesAreSet() {
        ThreadNetworkSpecifier specifier =
                new ThreadNetworkSpecifier.Builder(mExtendedPanId)
                        .setActiveTimestamp(mActiveTimestamp)
                        .setRouterEligibleForLeader(mRouterEligibleForLeader)
                        .build();

        assertThat(specifier.getExtendedPanId()).isEqualTo(mExtendedPanId);
        assertThat(specifier.getActiveTimestamp()).isEqualTo(mActiveTimestamp);
        assertThat(specifier.isRouterEligibleForLeader()).isEqualTo(mRouterEligibleForLeader);
    }

    @Test
    public void builderConstructor_specifiersAreEqual() {
        ThreadNetworkSpecifier specifier1 =
                new ThreadNetworkSpecifier.Builder(mExtendedPanId)
                        .setActiveTimestamp(mActiveTimestamp)
                        .setRouterEligibleForLeader(mRouterEligibleForLeader)
                        .build();

        ThreadNetworkSpecifier specifier2 = new ThreadNetworkSpecifier.Builder(specifier1).build();

        assertThat(specifier1).isEqualTo(specifier2);
    }

    @Test
    public void equalsTester() {
        var timestampNow = OperationalDatasetTimestamp.fromInstant(Instant.now());
        new EqualsTester()
                .addEqualityGroup(
                        new ThreadNetworkSpecifier.Builder(new byte[] {0, 1, 2, 3, 4, 5, 6, 7})
                                .setActiveTimestamp(timestampNow)
                                .setRouterEligibleForLeader(true)
                                .build(),
                        new ThreadNetworkSpecifier.Builder(new byte[] {0, 1, 2, 3, 4, 5, 6, 7})
                                .setActiveTimestamp(timestampNow)
                                .setRouterEligibleForLeader(true)
                                .build())
                .addEqualityGroup(
                        new ThreadNetworkSpecifier.Builder(new byte[] {0, 1, 2, 3, 4, 5, 6, 7})
                                .setActiveTimestamp(null)
                                .setRouterEligibleForLeader(false)
                                .build(),
                        new ThreadNetworkSpecifier.Builder(new byte[] {0, 1, 2, 3, 4, 5, 6, 7})
                                .setActiveTimestamp(null)
                                .setRouterEligibleForLeader(false)
                                .build())
                .addEqualityGroup(
                        new ThreadNetworkSpecifier.Builder(new byte[] {1, 1, 1, 1, 2, 2, 2, 2})
                                .setActiveTimestamp(null)
                                .setRouterEligibleForLeader(false)
                                .build(),
                        new ThreadNetworkSpecifier.Builder(new byte[] {1, 1, 1, 1, 2, 2, 2, 2})
                                .setActiveTimestamp(null)
                                .setRouterEligibleForLeader(false)
                                .build())
                .testEquals();
    }
}
