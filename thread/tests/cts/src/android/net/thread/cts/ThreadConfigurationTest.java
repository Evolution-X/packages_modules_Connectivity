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

package android.net.thread.cts;

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static com.google.common.truth.Truth.assertThat;

import android.net.thread.ThreadConfiguration;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

/** Tests for {@link ThreadConfiguration}. */
@SmallTest
@RequiresThreadFeature
@RunWith(Parameterized.class)
public final class ThreadConfigurationTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    public final boolean mIsBorderRouterEnabled;
    public final boolean mIsNat64Enabled;
    public final boolean mIsDhcpv6PdEnabled;

    @Parameterized.Parameters
    public static Collection configArguments() {
        return Arrays.asList(
                new Object[][] {
                    {false, false, false}, // All disabled
                    {false, true, false}, // NAT64 enabled
                    {false, false, true}, // DHCP6-PD enabled
                    {true, true, true}, // All enabled
                });
    }

    public ThreadConfigurationTest(
            boolean isBorderRouterEnabled, boolean isNat64Enabled, boolean isDhcpv6PdEnabled) {
        mIsBorderRouterEnabled = isBorderRouterEnabled;
        mIsNat64Enabled = isNat64Enabled;
        mIsDhcpv6PdEnabled = isDhcpv6PdEnabled;
    }

    @Test
    public void parcelable_parcelingIsLossLess() {
        ThreadConfiguration config =
                new ThreadConfiguration.Builder()
                        .setBorderRouterEnabled(mIsBorderRouterEnabled)
                        .setNat64Enabled(mIsNat64Enabled)
                        .setDhcpv6PdEnabled(mIsDhcpv6PdEnabled)
                        .build();
        assertParcelingIsLossless(config);
    }

    @Test
    public void builder_correctValuesAreSet() {
        ThreadConfiguration config =
                new ThreadConfiguration.Builder()
                        .setBorderRouterEnabled(mIsBorderRouterEnabled)
                        .setNat64Enabled(mIsNat64Enabled)
                        .setDhcpv6PdEnabled(mIsDhcpv6PdEnabled)
                        .build();

        assertThat(config.isBorderRouterEnabled()).isEqualTo(mIsBorderRouterEnabled);
        assertThat(config.isNat64Enabled()).isEqualTo(mIsNat64Enabled);
        assertThat(config.isDhcpv6PdEnabled()).isEqualTo(mIsDhcpv6PdEnabled);
    }

    @Test
    public void builderConstructor_configsAreEqual() {
        ThreadConfiguration config1 =
                new ThreadConfiguration.Builder()
                        .setBorderRouterEnabled(mIsBorderRouterEnabled)
                        .setNat64Enabled(mIsNat64Enabled)
                        .setDhcpv6PdEnabled(mIsDhcpv6PdEnabled)
                        .build();
        ThreadConfiguration config2 = new ThreadConfiguration.Builder(config1).build();
        assertThat(config1).isEqualTo(config2);
    }
}
