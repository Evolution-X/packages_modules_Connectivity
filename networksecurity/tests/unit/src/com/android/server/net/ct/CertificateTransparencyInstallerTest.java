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

import android.system.ErrnoException;
import android.system.Os;

import androidx.test.platform.app.InstrumentationRegistry;

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

    private File mTestDir =
            new File(
                    InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(),
                    "test-dir");
    private File mTestSymlink =
            new File(mTestDir, CertificateTransparencyInstaller.CURRENT_DIR_SYMLINK_NAME);
    private CertificateTransparencyInstaller mCertificateTransparencyInstaller =
            new CertificateTransparencyInstaller(mTestDir);

    @Before
    public void setUp() {
        CertificateTransparencyInstaller.deleteContentsAndDir(mTestDir);
    }

    @Test
    public void testCertificateTransparencyInstaller_installSuccessfully() throws IOException {
        String content = "i_am_a_certificate_and_i_am_transparent";
        String version = "666";
        boolean success = false;

        try (InputStream inputStream = asStream(content)) {
            success = mCertificateTransparencyInstaller.install(inputStream, version);
        }

        assertThat(success).isTrue();
        assertThat(mTestDir.exists()).isTrue();
        assertThat(mTestDir.isDirectory()).isTrue();
        assertThat(mTestSymlink.exists()).isTrue();
        assertThat(mTestSymlink.isDirectory()).isTrue();

        File logsDir =
                new File(mTestDir, CertificateTransparencyInstaller.LOGS_DIR_PREFIX + version);
        assertThat(logsDir.exists()).isTrue();
        assertThat(logsDir.isDirectory()).isTrue();
        assertThat(mTestSymlink.getCanonicalPath()).isEqualTo(logsDir.getCanonicalPath());

        File logsListFile = new File(logsDir, CertificateTransparencyInstaller.LOGS_LIST_FILE_NAME);
        assertThat(logsListFile.exists()).isTrue();
        assertThat(readAsString(logsListFile)).isEqualTo(content);
    }

    @Test
    public void testCertificateTransparencyInstaller_versionIsAlreadyInstalled()
            throws IOException, ErrnoException {
        String existingVersion = "666";
        String existingContent = "i_was_already_installed_successfully";
        File existingLogDir =
                new File(
                        mTestDir,
                        CertificateTransparencyInstaller.LOGS_DIR_PREFIX + existingVersion);
        assertThat(mTestDir.mkdir()).isTrue();
        assertThat(existingLogDir.mkdir()).isTrue();
        Os.symlink(existingLogDir.getCanonicalPath(), mTestSymlink.getCanonicalPath());
        File logsListFile =
                new File(existingLogDir, CertificateTransparencyInstaller.LOGS_LIST_FILE_NAME);
        logsListFile.createNewFile();
        writeToFile(logsListFile, existingContent);
        boolean success = false;

        try (InputStream inputStream = asStream("i_will_be_ignored")) {
            success = mCertificateTransparencyInstaller.install(inputStream, existingVersion);
        }

        assertThat(success).isFalse();
        assertThat(readAsString(logsListFile)).isEqualTo(existingContent);
    }

    @Test
    public void testCertificateTransparencyInstaller_versionInstalledFailed()
            throws IOException, ErrnoException {
        String existingVersion = "666";
        String existingContent = "somebody_tried_to_install_me_but_failed_halfway_through";
        String newContent = "i_am_the_real_certificate";
        File existingLogDir =
                new File(
                        mTestDir,
                        CertificateTransparencyInstaller.LOGS_DIR_PREFIX + existingVersion);
        assertThat(mTestDir.mkdir()).isTrue();
        assertThat(existingLogDir.mkdir()).isTrue();
        File logsListFile =
                new File(existingLogDir, CertificateTransparencyInstaller.LOGS_LIST_FILE_NAME);
        logsListFile.createNewFile();
        writeToFile(logsListFile, existingContent);
        boolean success = false;

        try (InputStream inputStream = asStream(newContent)) {
            success = mCertificateTransparencyInstaller.install(inputStream, existingVersion);
        }

        assertThat(success).isTrue();
        assertThat(mTestSymlink.getCanonicalPath()).isEqualTo(existingLogDir.getCanonicalPath());
        assertThat(readAsString(logsListFile)).isEqualTo(newContent);
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
