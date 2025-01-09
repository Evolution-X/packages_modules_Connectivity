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

import android.annotation.SuppressLint;

import java.io.File;
import java.io.IOException;

/** Utility class to manipulate CT directories. */
class DirectoryUtils {

    static void makeDir(File dir) throws IOException {
        dir.mkdirs();
        if (!dir.isDirectory()) {
            throw new IOException("Unable to make directory " + dir.getCanonicalPath());
        }
        setWorldReadable(dir);
        // Needed for the log list file to be accessible.
        setWorldExecutable(dir);
    }

    // CT files and directories are readable by all apps.
    @SuppressLint("SetWorldReadable")
    static void setWorldReadable(File file) throws IOException {
        if (!file.setReadable(/* readable= */ true, /* ownerOnly= */ false)) {
            throw new IOException("Failed to set " + file.getCanonicalPath() + " readable");
        }
    }

    // CT directories are executable by all apps, to allow access to the log list by anything on the
    // device.
    static void setWorldExecutable(File file) throws IOException {
        if (!file.isDirectory()) {
            // Only directories need to be marked as executable to allow for access
            // to the files inside.
            // See https://www.redhat.com/en/blog/linux-file-permissions-explained for more details.
            return;
        }

        if (!file.setExecutable(/* executable= */ true, /* ownerOnly= */ false)) {
            throw new IOException("Failed to set " + file.getCanonicalPath() + " executable");
        }
    }

    static boolean removeDir(File dir) {
        return deleteContentsAndDir(dir);
    }

    private static boolean deleteContentsAndDir(File dir) {
        if (deleteContents(dir)) {
            return dir.delete();
        } else {
            return false;
        }
    }

    private static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    success = false;
                }
            }
        }
        return success;
    }
}
