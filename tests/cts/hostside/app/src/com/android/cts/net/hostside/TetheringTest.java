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

package com.android.cts.net.hostside;

import static android.net.TetheringManager.TETHERING_WIFI;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.net.TetheringInterface;
import android.net.cts.util.CtsTetheringUtils;
import android.net.cts.util.CtsTetheringUtils.TestTetheringEventCallback;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiSsid;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class TetheringTest {
    private CtsTetheringUtils mCtsTetheringUtils;
    private TetheringHelperClient mTetheringHelperClient;
    private TestTetheringEventCallback mTetheringEventCallback;

    @Before
    public void setUp() throws Exception {
        Context targetContext = getInstrumentation().getTargetContext();
        mCtsTetheringUtils = new CtsTetheringUtils(targetContext);
        mTetheringHelperClient = new TetheringHelperClient(targetContext);
        mTetheringHelperClient.bind();
        mTetheringEventCallback = mCtsTetheringUtils.registerTetheringEventCallback();
    }

    @After
    public void tearDown() throws Exception {
        mTetheringHelperClient.unbind();
        mCtsTetheringUtils.unregisterTetheringEventCallback(mTetheringEventCallback);
        mCtsTetheringUtils.stopAllTethering();
    }

    /**
     * Starts Wifi tethering and tests that the SoftApConfiguration is redacted from
     * TetheringEventCallback for other apps.
     */
    @Test
    public void testSoftApConfigurationRedactedForOtherUids() throws Exception {
        mTetheringEventCallback.assumeWifiTetheringSupported(
                getInstrumentation().getTargetContext());
        SoftApConfiguration softApConfig = new SoftApConfiguration.Builder()
                .setWifiSsid(WifiSsid.fromBytes("This is an SSID!"
                        .getBytes(StandardCharsets.UTF_8))).build();
        final TetheringInterface tetheringInterface =
                mCtsTetheringUtils.startWifiTethering(mTetheringEventCallback, softApConfig);
        assertNotNull(tetheringInterface);
        assertEquals(softApConfig, tetheringInterface.getSoftApConfiguration());
        assertEquals(new TetheringInterface(
                TETHERING_WIFI, tetheringInterface.getInterface(), softApConfig),
                tetheringInterface);
        TetheringInterface tetheringInterfaceForApp2 =
                mTetheringHelperClient.getTetheredWifiInterface();
        assertNotNull(tetheringInterfaceForApp2);
        assertNull(tetheringInterfaceForApp2.getSoftApConfiguration());
        assertEquals(
                tetheringInterface.getInterface(), tetheringInterfaceForApp2.getInterface());
    }
}
