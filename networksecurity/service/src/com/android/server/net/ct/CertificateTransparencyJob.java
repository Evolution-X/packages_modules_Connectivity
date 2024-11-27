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

import android.annotation.RequiresApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/** Implementation of the Certificate Transparency job */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class CertificateTransparencyJob extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyJob";

    private static final String ACTION_JOB_START = "com.android.server.net.ct.action.JOB_START";

    private final Context mContext;
    private final DataStore mDataStore;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;
    private final AlarmManager mAlarmManager;

    /** Creates a new {@link CertificateTransparencyJob} object. */
    public CertificateTransparencyJob(
            Context context,
            DataStore dataStore,
            CertificateTransparencyDownloader certificateTransparencyDownloader) {
        mContext = context;
        mDataStore = dataStore;
        mCertificateTransparencyDownloader = certificateTransparencyDownloader;
        mAlarmManager = context.getSystemService(AlarmManager.class);
    }

    void initialize() {
        mDataStore.load();
        mCertificateTransparencyDownloader.initialize();

        mContext.registerReceiver(
                this, new IntentFilter(ACTION_JOB_START), Context.RECEIVER_EXPORTED);
        mAlarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime(), // schedule first job at earliest convenient time.
                AlarmManager.INTERVAL_DAY,
                PendingIntent.getBroadcast(
                        mContext, 0, new Intent(ACTION_JOB_START), PendingIntent.FLAG_IMMUTABLE));

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyJob scheduled successfully.");
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_JOB_START.equals(intent.getAction())) {
            Log.w(TAG, "Received unexpected broadcast with action " + intent);
            return;
        }
        if (Config.DEBUG) {
            Log.d(TAG, "Starting CT daily job.");
        }

        mDataStore.setProperty(Config.CONTENT_URL_PENDING, Config.URL_LOG_LIST);
        mDataStore.setProperty(Config.METADATA_URL_PENDING, Config.URL_SIGNATURE);
        mDataStore.setProperty(Config.PUBLIC_KEY_URL_PENDING, Config.URL_PUBLIC_KEY);
        mDataStore.store();

        mCertificateTransparencyDownloader.startPublicKeyDownload(Config.URL_PUBLIC_KEY);
    }
}
