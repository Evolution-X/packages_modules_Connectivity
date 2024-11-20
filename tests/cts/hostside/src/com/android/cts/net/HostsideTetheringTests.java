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

package com.android.cts.net;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class HostsideTetheringTests extends HostsideNetworkTestCase {
    /**
     * Set up the test once before running all the tests.
     */
    @BeforeClassWithInfo
    public static void setUpOnce(TestInformation testInfo) throws Exception {
        uninstallPackage(testInfo, TEST_APP2_PKG, false);
        installPackage(testInfo, TEST_APP2_APK);
    }

    /**
     * Tear down the test once after running all the tests.
     */
    @AfterClassWithInfo
    public static void tearDownOnce(TestInformation testInfo)
            throws DeviceNotAvailableException {
        uninstallPackage(testInfo, TEST_APP2_PKG, true);
    }

    @Test
    public void testSoftApConfigurationRedactedForOtherApps() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".TetheringTest",
                "testSoftApConfigurationRedactedForOtherUids");
    }
}
