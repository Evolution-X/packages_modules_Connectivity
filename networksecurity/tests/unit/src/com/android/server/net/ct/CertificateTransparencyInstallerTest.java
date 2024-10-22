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

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Tests for the {@link CertificateTransparencyInstaller}. */
@RunWith(JUnit4.class)
public class CertificateTransparencyInstallerTest {

    private static final String TEST_VERSION = "test-v1";

    private File mTestDir =
            new File(
                    InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(),
                    "test-dir");
    private CertificateTransparencyInstaller mCertificateTransparencyInstaller =
            new CertificateTransparencyInstaller(mTestDir);

    @Before
    public void setUp() {
        mCertificateTransparencyInstaller.addCompatibilityVersion(TEST_VERSION);
    }

    @After
    public void tearDown() {
        mCertificateTransparencyInstaller.removeCompatibilityVersion(TEST_VERSION);
        DirectoryUtils.removeDir(mTestDir);
    }

    @Test
    public void testCompatibilityVersion_installSuccessful() throws IOException {
        assertThat(mTestDir.mkdir()).isTrue();
        String content = "i_am_compatible";
        String version = "i_am_version";
        CompatibilityVersion compatVersion =
                mCertificateTransparencyInstaller.getCompatibilityVersion(TEST_VERSION);

        try (InputStream inputStream = asStream(content)) {
            assertThat(compatVersion.install(inputStream, version)).isTrue();
        }
        File logsDir = compatVersion.getLogsDir();
        assertThat(logsDir.exists()).isTrue();
        assertThat(logsDir.isDirectory()).isTrue();
        assertThat(logsDir.getAbsolutePath())
                .startsWith(mTestDir.getAbsolutePath() + "/" + TEST_VERSION);
        File logsListFile = compatVersion.getLogsFile();
        assertThat(logsListFile.exists()).isTrue();
        assertThat(logsListFile.getAbsolutePath()).startsWith(logsDir.getAbsolutePath());
        assertThat(readAsString(logsListFile)).isEqualTo(content);
        File logsSymlink = compatVersion.getLogsDirSymlink();
        assertThat(logsSymlink.exists()).isTrue();
        assertThat(logsSymlink.isDirectory()).isTrue();
        assertThat(logsSymlink.getAbsolutePath())
                .startsWith(mTestDir.getAbsolutePath() + "/" + TEST_VERSION + "/current");
        assertThat(logsSymlink.getCanonicalPath()).isEqualTo(logsDir.getCanonicalPath());

        assertThat(compatVersion.delete()).isTrue();
        assertThat(logsDir.exists()).isFalse();
        assertThat(logsSymlink.exists()).isFalse();
        assertThat(logsListFile.exists()).isFalse();
    }

    @Test
    public void testCompatibilityVersion_versionInstalledFailed() throws IOException {
        assertThat(mTestDir.mkdir()).isTrue();

        CompatibilityVersion compatVersion =
                mCertificateTransparencyInstaller.getCompatibilityVersion(TEST_VERSION);
        File rootDir = compatVersion.getRootDir();
        assertThat(rootDir.mkdir()).isTrue();

        String existingVersion = "666";
        File existingLogDir =
                new File(rootDir, CompatibilityVersion.LOGS_DIR_PREFIX + existingVersion);
        assertThat(existingLogDir.mkdir()).isTrue();

        String existingContent = "somebody_tried_to_install_me_but_failed_halfway_through";
        File logsListFile = new File(existingLogDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsListFile.createNewFile()).isTrue();
        writeToFile(logsListFile, existingContent);

        String newContent = "i_am_the_real_content";
        try (InputStream inputStream = asStream(newContent)) {
            assertThat(compatVersion.install(inputStream, existingVersion)).isTrue();
        }

        assertThat(readAsString(logsListFile)).isEqualTo(newContent);
    }

    @Test
    public void testCertificateTransparencyInstaller_installSuccessfully() throws IOException {
        String content = "i_am_a_certificate_and_i_am_transparent";
        String version = "666";

        try (InputStream inputStream = asStream(content)) {
            assertThat(
                            mCertificateTransparencyInstaller.install(
                                    TEST_VERSION, inputStream, version))
                    .isTrue();
        }

        assertThat(mTestDir.exists()).isTrue();
        assertThat(mTestDir.isDirectory()).isTrue();
        CompatibilityVersion compatVersion =
                mCertificateTransparencyInstaller.getCompatibilityVersion(TEST_VERSION);
        File logsDir = compatVersion.getLogsDir();
        assertThat(logsDir.exists()).isTrue();
        assertThat(logsDir.isDirectory()).isTrue();
        assertThat(logsDir.getAbsolutePath())
                .startsWith(mTestDir.getAbsolutePath() + "/" + TEST_VERSION);
        File logsListFile = compatVersion.getLogsFile();
        assertThat(logsListFile.exists()).isTrue();
        assertThat(logsListFile.getAbsolutePath()).startsWith(logsDir.getAbsolutePath());
        assertThat(readAsString(logsListFile)).isEqualTo(content);
    }

    @Test
    public void testCertificateTransparencyInstaller_versionIsAlreadyInstalled()
            throws IOException {
        String existingVersion = "666";
        String existingContent = "i_was_already_installed_successfully";
        CompatibilityVersion compatVersion =
                mCertificateTransparencyInstaller.getCompatibilityVersion(TEST_VERSION);

        DirectoryUtils.makeDir(mTestDir);
        try (InputStream inputStream = asStream(existingContent)) {
            assertThat(compatVersion.install(inputStream, existingVersion)).isTrue();
        }

        try (InputStream inputStream = asStream("i_will_be_ignored")) {
            assertThat(
                            mCertificateTransparencyInstaller.install(
                                    TEST_VERSION, inputStream, existingVersion))
                    .isFalse();
        }

        assertThat(readAsString(compatVersion.getLogsFile())).isEqualTo(existingContent);
    }

    private static InputStream asStream(String string) throws IOException {
        return new ByteArrayInputStream(string.getBytes());
    }

    private static String readAsString(File file) throws IOException {
        return new String(new FileInputStream(file).readAllBytes());
    }

    private static void writeToFile(File file, String string) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(string.getBytes());
        }
    }
}
