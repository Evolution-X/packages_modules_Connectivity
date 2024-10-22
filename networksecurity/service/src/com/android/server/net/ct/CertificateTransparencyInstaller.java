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

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Installer of CT log lists. */
public class CertificateTransparencyInstaller {

    private static final String TAG = "CertificateTransparencyInstaller";

    private final Map<String, CompatibilityVersion> mCompatVersions = new HashMap<>();

    // The CT root directory.
    private final File mRootDirectory;

    public CertificateTransparencyInstaller(File rootDirectory) {
        mRootDirectory = rootDirectory;
    }

    public CertificateTransparencyInstaller(String rootDirectoryPath) {
        this(new File(rootDirectoryPath));
    }

    public CertificateTransparencyInstaller() {
        this(Config.CT_ROOT_DIRECTORY_PATH);
    }

    void addCompatibilityVersion(String versionName) {
        removeCompatibilityVersion(versionName);
        CompatibilityVersion newCompatVersion =
                new CompatibilityVersion(new File(mRootDirectory, versionName));
        mCompatVersions.put(versionName, newCompatVersion);
    }

    void removeCompatibilityVersion(String versionName) {
        CompatibilityVersion compatVersion = mCompatVersions.remove(versionName);
        if (compatVersion != null && !compatVersion.delete()) {
            Log.w(TAG, "Could not delete compatibility version directory.");
        }
    }

    CompatibilityVersion getCompatibilityVersion(String versionName) {
        return mCompatVersions.get(versionName);
    }

    /**
     * Install a new log list to use during SCT verification.
     *
     * @param compatibilityVersion the compatibility version of the new log list
     * @param newContent an input stream providing the log list
     * @param version the minor version of the new log list
     * @return true if the log list was installed successfully, false otherwise.
     * @throws IOException if the list cannot be saved in the CT directory.
     */
    public boolean install(String compatibilityVersion, InputStream newContent, String version)
            throws IOException {
        CompatibilityVersion compatVersion = mCompatVersions.get(compatibilityVersion);
        if (compatVersion == null) {
            Log.e(TAG, "No compatibility version for " + compatibilityVersion);
            return false;
        }
        // Ensure root directory exists and is readable.
        DirectoryUtils.makeDir(mRootDirectory);

        if (!compatVersion.install(newContent, version)) {
            Log.e(TAG, "Failed to install logs for compatibility version " + compatibilityVersion);
            return false;
        }
        Log.i(TAG, "New logs installed at " + compatVersion.getLogsDir());
        return true;
    }
}
