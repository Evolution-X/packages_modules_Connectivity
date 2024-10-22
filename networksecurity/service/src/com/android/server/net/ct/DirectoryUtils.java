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
        dir.mkdir();
        if (!dir.isDirectory()) {
            throw new IOException("Unable to make directory " + dir.getCanonicalPath());
        }
        setWorldReadable(dir);
    }

    // CT files and directories are readable by all apps.
    @SuppressLint("SetWorldReadable")
    static void setWorldReadable(File file) throws IOException {
        if (!file.setReadable(true, false)) {
            throw new IOException("Failed to set " + file.getCanonicalPath() + " readable");
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
