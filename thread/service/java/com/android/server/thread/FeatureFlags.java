/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server.thread;

import com.android.net.module.util.DeviceConfigUtils;

public class FeatureFlags {
    // The namespace for Thread Network feature flags
    private static final String NAMESPACE_THREAD_NETWORK = "thread_network";

    // The prefix for TREL feature flags
    private static final String TREL_FEATURE_PREFIX = "TrelFeature__";

    // The feature flag for TREL enabled state
    private static final String TREL_ENABLED_FLAG = TREL_FEATURE_PREFIX + "enabled";

    private static boolean isFeatureEnabled(String flag, boolean defaultValue) {
        return DeviceConfigUtils.getDeviceConfigPropertyBoolean(
                NAMESPACE_THREAD_NETWORK, flag, defaultValue);
    }

    public static boolean isTrelEnabled() {
        return isFeatureEnabled(TREL_ENABLED_FLAG, false);
    }
}
