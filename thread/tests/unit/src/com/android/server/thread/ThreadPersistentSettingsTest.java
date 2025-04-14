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

package com.android.server.thread;

import static com.android.server.thread.ThreadPersistentSettings.KEY_COUNTRY_CODE;
import static com.android.server.thread.ThreadPersistentSettings.KEY_THREAD_ENABLED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.net.thread.ThreadConfiguration;
import android.os.PersistableBundle;
import android.util.AtomicFile;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.connectivity.resources.R;
import com.android.server.connectivity.ConnectivityResources;
import com.android.server.thread.ThreadPersistentSettings.Key;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;

/** Unit tests for {@link ThreadPersistentSettings}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ThreadPersistentSettingsTest {
    private static final String TEST_COUNTRY_CODE = "CN";

    @Mock Resources mResources;
    @Mock ConnectivityResources mConnectivityResources;

    private AtomicFile mAtomicFile;
    private ThreadPersistentSettings mThreadPersistentSettings;

    @Rule(order = 0)
    public final TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mConnectivityResources.get()).thenReturn(mResources);
        when(mResources.getBoolean(eq(R.bool.config_thread_default_enabled))).thenReturn(true);

        mAtomicFile = createAtomicFile();
        mThreadPersistentSettings =
                new ThreadPersistentSettings(mAtomicFile, mConnectivityResources);
    }

    /** Called after each test */
    @After
    public void tearDown() {
        validateMockitoUsage();
    }

    @Test
    public void initialize_readsFromFile() throws Exception {
        byte[] data = createXmlForParsing(KEY_THREAD_ENABLED, false);
        setupAtomicFileForRead(data);

        mThreadPersistentSettings.initialize();

        assertThat(mThreadPersistentSettings.get(KEY_THREAD_ENABLED)).isFalse();
    }

    @Test
    public void initialize_ThreadDisabledInResources_returnsThreadDisabled() throws Exception {
        when(mResources.getBoolean(eq(R.bool.config_thread_default_enabled))).thenReturn(false);
        mThreadPersistentSettings =
                new ThreadPersistentSettings(mAtomicFile, mConnectivityResources);

        mThreadPersistentSettings.initialize();

        assertThat(mThreadPersistentSettings.get(KEY_THREAD_ENABLED)).isFalse();
    }

    @Test
    public void initialize_ThreadEnabledInResources_returnsThreadEnabled() throws Exception {
        when(mResources.getBoolean(eq(R.bool.config_thread_default_enabled))).thenReturn(true);
        mThreadPersistentSettings =
                new ThreadPersistentSettings(mAtomicFile, mConnectivityResources);

        mThreadPersistentSettings.initialize();

        assertThat(mThreadPersistentSettings.get(KEY_THREAD_ENABLED)).isTrue();
    }

    @Test
    public void initialize_ThreadDisabledInResourcesButEnabledInXml_returnsThreadEnabled()
            throws Exception {
        when(mResources.getBoolean(eq(R.bool.config_thread_default_enabled))).thenReturn(false);
        setupAtomicFileForRead(createXmlForParsing(KEY_THREAD_ENABLED, true));
        mThreadPersistentSettings =
                new ThreadPersistentSettings(mAtomicFile, mConnectivityResources);

        mThreadPersistentSettings.initialize();

        assertThat(mThreadPersistentSettings.get(KEY_THREAD_ENABLED)).isTrue();
    }

    @Test
    public void put_ThreadFeatureEnabledTrue_returnsTrue() throws Exception {
        mThreadPersistentSettings.put(KEY_THREAD_ENABLED, true);

        assertThat(mThreadPersistentSettings.get(KEY_THREAD_ENABLED)).isTrue();
    }

    @Test
    public void put_ThreadFeatureEnabledFalse_returnsFalse() throws Exception {
        mThreadPersistentSettings.put(KEY_THREAD_ENABLED, false);

        assertThat(mThreadPersistentSettings.get(KEY_THREAD_ENABLED)).isFalse();
        mThreadPersistentSettings.initialize();
        assertThat(mThreadPersistentSettings.get(KEY_THREAD_ENABLED)).isFalse();
    }

    @Test
    public void put_ThreadCountryCodeString_returnsString() throws Exception {
        mThreadPersistentSettings.put(KEY_COUNTRY_CODE, TEST_COUNTRY_CODE);

        assertThat(mThreadPersistentSettings.get(KEY_COUNTRY_CODE)).isEqualTo(TEST_COUNTRY_CODE);
        mThreadPersistentSettings.initialize();
        assertThat(mThreadPersistentSettings.get(KEY_COUNTRY_CODE)).isEqualTo(TEST_COUNTRY_CODE);
    }

    @Test
    public void put_ThreadCountryCodeNull_returnsNull() throws Exception {
        mThreadPersistentSettings.put(KEY_COUNTRY_CODE, null);

        assertThat(mThreadPersistentSettings.get(KEY_COUNTRY_CODE)).isNull();
        mThreadPersistentSettings.initialize();
        assertThat(mThreadPersistentSettings.get(KEY_COUNTRY_CODE)).isNull();
    }

    @Test
    public void putConfiguration_sameValues_returnsFalse() {
        ThreadConfiguration configuration =
                new ThreadConfiguration.Builder()
                        .setNat64Enabled(true)
                        .setDhcpv6PdEnabled(true)
                        .build();
        mThreadPersistentSettings.putConfiguration(configuration);

        assertThat(mThreadPersistentSettings.putConfiguration(configuration)).isFalse();
    }

    @Test
    public void putConfiguration_differentValues_returnsTrue() {
        ThreadConfiguration configuration1 =
                new ThreadConfiguration.Builder()
                        .setNat64Enabled(false)
                        .setDhcpv6PdEnabled(false)
                        .build();
        mThreadPersistentSettings.putConfiguration(configuration1);
        ThreadConfiguration configuration2 =
                new ThreadConfiguration.Builder()
                        .setNat64Enabled(true)
                        .setDhcpv6PdEnabled(true)
                        .build();

        assertThat(mThreadPersistentSettings.putConfiguration(configuration2)).isTrue();
    }

    @Test
    public void putConfiguration_nat64Enabled_valuesUpdatedAndPersisted() throws Exception {
        ThreadConfiguration configuration =
                new ThreadConfiguration.Builder().setNat64Enabled(true).build();
        mThreadPersistentSettings.putConfiguration(configuration);

        assertThat(mThreadPersistentSettings.getConfiguration()).isEqualTo(configuration);
        mThreadPersistentSettings.initialize();
        assertThat(mThreadPersistentSettings.getConfiguration()).isEqualTo(configuration);
    }

    @Test
    public void putConfiguration_dhcpv6PdEnabled_valuesUpdatedAndPersisted() throws Exception {
        ThreadConfiguration configuration =
                new ThreadConfiguration.Builder().setDhcpv6PdEnabled(true).build();
        mThreadPersistentSettings.putConfiguration(configuration);

        assertThat(mThreadPersistentSettings.getConfiguration()).isEqualTo(configuration);
        mThreadPersistentSettings.initialize();
        assertThat(mThreadPersistentSettings.getConfiguration()).isEqualTo(configuration);
    }

    private AtomicFile createAtomicFile() throws Exception {
        return new AtomicFile(mTemporaryFolder.newFile());
    }

    private byte[] createXmlForParsing(Key<Boolean> key, Boolean value) throws Exception {
        PersistableBundle bundle = new PersistableBundle();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bundle.putBoolean(key.key, value);
        bundle.writeToStream(outputStream);
        return outputStream.toByteArray();
    }

    private void setupAtomicFileForRead(byte[] dataToRead) throws Exception {
        try (FileOutputStream outputStream = new FileOutputStream(mAtomicFile.getBaseFile())) {
            outputStream.write(dataToRead);
        }
    }
}
