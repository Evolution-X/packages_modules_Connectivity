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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.Properties;

/** Class to persist data needed by CT. */
class DataStore extends Properties {

    private static final String TAG = "CertificateTransparency";

    private final File mPropertyFile;

    DataStore(File file) {
        super();
        mPropertyFile = file;
    }

    void load() {
        if (!mPropertyFile.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(mPropertyFile)) {
            load(in);
        } catch (IOException e) {
            Log.e(TAG, "Error loading property store", e);
        }
    }

    void store() {
        try (OutputStream out = new FileOutputStream(mPropertyFile)) {
            store(out, "");
        } catch (IOException e) {
            Log.e(TAG, "Error storing property store", e);
        }
    }

    long getPropertyLong(String key, long defaultValue) {
        return Optional.ofNullable(getProperty(key)).map(Long::parseLong).orElse(defaultValue);
    }

    Object setPropertyLong(String key, long value) {
        return setProperty(key, Long.toString(value));
    }

    int getPropertyInt(String key, int defaultValue) {
        return Optional.ofNullable(getProperty(key)).map(Integer::parseInt).orElse(defaultValue);
    }

    Object setPropertyInt(String key, int value) {
        return setProperty(key, Integer.toString(value));
    }
}
