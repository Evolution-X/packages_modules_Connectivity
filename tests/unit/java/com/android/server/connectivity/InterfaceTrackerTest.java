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

package com.android.server.connectivity;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.ConnectivityModuleTest;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@ConnectivityModuleTest
public class InterfaceTrackerTest {
    private static final String TAG = "InterfaceTrackerTest";
    private static final String TEST_IF_NAME = "wlan10";
    private static final String TEST_INCORRECT_IF_NAME = "wlan20";
    private static final int TEST_IF_INDEX = 7;

    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private InterfaceTracker mInterfaceTracker;

    @Mock Context mContext;
    @Mock InterfaceTracker.Dependencies mDeps;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(TEST_IF_INDEX).when(mDeps).getIfIndex(TEST_IF_NAME);
        mInterfaceTracker = new InterfaceTracker(mContext, mDeps);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void testAddingInterface_InterfaceNameIndexMappingAdded() {
        mInterfaceTracker.addInterface(TEST_IF_NAME);
        assertEquals(TEST_IF_INDEX, mInterfaceTracker.getInterfaceIndex(TEST_IF_NAME));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void testAddingNullInterface_InterfaceNameIndexMappingNotAdded() {
        mInterfaceTracker.addInterface(null);
        assertEquals(0, mInterfaceTracker.getInterfaceIndex(TEST_IF_NAME));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void testAddingIncorrectInterface_InterfaceNameIndexMappingNotAdded() {
        mInterfaceTracker.addInterface(TEST_INCORRECT_IF_NAME);

        assertEquals(0, mInterfaceTracker.getInterfaceIndex(TEST_INCORRECT_IF_NAME));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void testRemovingInterface_InterfaceNameIndexMappingRemoved() {
        mInterfaceTracker.addInterface(TEST_IF_NAME);
        assertEquals(TEST_IF_INDEX, mInterfaceTracker.getInterfaceIndex(TEST_IF_NAME));
        mInterfaceTracker.removeInterface(TEST_IF_NAME);
        assertEquals(0, mInterfaceTracker.getInterfaceIndex(TEST_IF_NAME));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void testRemovingNullInterface_InterfaceNameIndexMappingNotRemoved() {
        mInterfaceTracker.addInterface(TEST_IF_NAME);
        mInterfaceTracker.removeInterface(null);
        assertEquals(TEST_IF_INDEX, mInterfaceTracker.getInterfaceIndex(TEST_IF_NAME));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public void testRemovingIncorrectInterface_InterfaceNameIndexMappingNotRemoved() {
        mInterfaceTracker.addInterface(TEST_IF_NAME);
        mInterfaceTracker.removeInterface(TEST_INCORRECT_IF_NAME);
        assertEquals(TEST_IF_INDEX, mInterfaceTracker.getInterfaceIndex(TEST_IF_NAME));
    }

}
