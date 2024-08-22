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
package com.android.server.net.ct;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;

/** Tests for the {@link DataStore}. */
@RunWith(JUnit4.class)
public class DataStoreTest {

    private File mTempFile;
    private DataStore mDataStore;

    @Before
    public void setUp() throws IOException {
        mTempFile = File.createTempFile("datastore-test", ".properties");
        mDataStore = new DataStore(mTempFile);
    }

    @After
    public void tearDown() {
        mTempFile.delete();
    }

    @Test
    public void testDataStore_propertyFileCreatedSuccessfully() {
        assertThat(mTempFile.exists()).isTrue();
        assertThat(mDataStore.isEmpty()).isTrue();
    }

    @Test
    public void testDataStore_propertySet() {
        String stringProperty = "prop1";
        String stringValue = "i_am_a_string";
        String longProperty = "prop3";
        long longValue = 9000;

        assertThat(mDataStore.getProperty(stringProperty)).isNull();
        assertThat(mDataStore.getPropertyLong(longProperty, -1)).isEqualTo(-1);

        mDataStore.setProperty(stringProperty, stringValue);
        mDataStore.setPropertyLong(longProperty, longValue);

        assertThat(mDataStore.getProperty(stringProperty)).isEqualTo(stringValue);
        assertThat(mDataStore.getPropertyLong(longProperty, -1)).isEqualTo(longValue);
    }

    @Test
    public void testDataStore_propertyStore() {
        String stringProperty = "prop1";
        String stringValue = "i_am_a_string";
        String longProperty = "prop3";
        long longValue = 9000;

        mDataStore.setProperty(stringProperty, stringValue);
        mDataStore.setPropertyLong(longProperty, longValue);
        mDataStore.store();

        mDataStore.clear();
        assertThat(mDataStore.getProperty(stringProperty)).isNull();
        assertThat(mDataStore.getPropertyLong(longProperty, -1)).isEqualTo(-1);

        mDataStore.load();
        assertThat(mDataStore.getProperty(stringProperty)).isEqualTo(stringValue);
        assertThat(mDataStore.getPropertyLong(longProperty, -1)).isEqualTo(longValue);
    }
}
