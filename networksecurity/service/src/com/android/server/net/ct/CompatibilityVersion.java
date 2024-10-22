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

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** Represents a compatibility version directory. */
class CompatibilityVersion {

    static final String LOGS_DIR_PREFIX = "logs-";
    static final String LOGS_LIST_FILE_NAME = "log_list.json";

    private static final String CURRENT_LOGS_DIR_SYMLINK_NAME = "current";

    private final File mRootDirectory;
    private final File mCurrentLogsDirSymlink;

    private File mCurrentLogsDir = null;

    CompatibilityVersion(File rootDirectory) {
        mRootDirectory = rootDirectory;
        mCurrentLogsDirSymlink = new File(mRootDirectory, CURRENT_LOGS_DIR_SYMLINK_NAME);
    }

    /**
     * Installs a log list within this compatibility version directory.
     *
     * @param newContent an input stream providing the log list
     * @param version the version number of the log list
     * @return true if the log list was installed successfully, false otherwise.
     * @throws IOException if the list cannot be saved in the CT directory.
     */
    boolean install(InputStream newContent, String version) throws IOException {
        // To support atomically replacing the old configuration directory with the new there's a
        // bunch of steps. We create a new directory with the logs and then do an atomic update of
        // the current symlink to point to the new directory.
        // 1. Ensure that the root directory exists and is readable.
        DirectoryUtils.makeDir(mRootDirectory);

        File newLogsDir = new File(mRootDirectory, LOGS_DIR_PREFIX + version);
        // 2. Handle the corner case where the new directory already exists.
        if (newLogsDir.exists()) {
            // If the symlink has already been updated then the update died between steps 6 and 7
            // and so we cannot delete the directory since it is in use.
            if (newLogsDir.getCanonicalPath().equals(mCurrentLogsDirSymlink.getCanonicalPath())) {
                deleteOldLogDirectories();
                return false;
            }
            // If the symlink has not been updated then the previous installation failed and this is
            // a re-attempt. Clean-up leftover files and try again.
            DirectoryUtils.removeDir(newLogsDir);
        }
        try {
            // 3. Create a new logs-<new_version>/ directory to store the new list.
            DirectoryUtils.makeDir(newLogsDir);

            // 4. Move the log list json file in logs-<new_version>/ .
            File logListFile = new File(newLogsDir, LOGS_LIST_FILE_NAME);
            if (Files.copy(newContent, logListFile.toPath()) == 0) {
                throw new IOException("The log list appears empty");
            }
            DirectoryUtils.setWorldReadable(logListFile);

            // 5. Create temp symlink. We rename this to the target symlink to get an atomic update.
            File tempSymlink = new File(mRootDirectory, "new_symlink");
            try {
                Os.symlink(newLogsDir.getCanonicalPath(), tempSymlink.getCanonicalPath());
            } catch (ErrnoException e) {
                throw new IOException("Failed to create symlink", e);
            }

            // 6. Update the symlink target, this is the actual update step.
            tempSymlink.renameTo(mCurrentLogsDirSymlink.getAbsoluteFile());
        } catch (IOException | RuntimeException e) {
            DirectoryUtils.removeDir(newLogsDir);
            throw e;
        }
        // 7. Cleanup
        mCurrentLogsDir = newLogsDir;
        deleteOldLogDirectories();
        return true;
    }

    File getRootDir() {
        return mRootDirectory;
    }

    File getLogsDir() {
        return mCurrentLogsDir;
    }

    File getLogsDirSymlink() {
        return mCurrentLogsDirSymlink;
    }

    File getLogsFile() {
        return new File(mCurrentLogsDir, LOGS_LIST_FILE_NAME);
    }

    boolean delete() {
        return DirectoryUtils.removeDir(mRootDirectory);
    }

    private void deleteOldLogDirectories() throws IOException {
        if (!mRootDirectory.exists()) {
            return;
        }
        File currentTarget = mCurrentLogsDirSymlink.getCanonicalFile();
        for (File file : mRootDirectory.listFiles()) {
            if (!currentTarget.equals(file.getCanonicalFile())
                    && file.getName().startsWith(LOGS_DIR_PREFIX)) {
                DirectoryUtils.removeDir(file);
            }
        }
    }
}
