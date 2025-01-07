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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ConfigUpdate;
import android.os.SystemClock;
import android.util.Log;

/** Implementation of the Certificate Transparency job */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class CertificateTransparencyJob extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyJob";
    private static final String UPDATE_CONFIG_PERMISSION = "android.permission.UPDATE_CONFIG";

    private final Context mContext;
    private final DataStore mDataStore;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;
    private final AlarmManager mAlarmManager;
    private final PendingIntent mPendingIntent;

    private boolean mDependenciesReady = false;

    /** Creates a new {@link CertificateTransparencyJob} object. */
    public CertificateTransparencyJob(
            Context context,
            DataStore dataStore,
            CertificateTransparencyDownloader certificateTransparencyDownloader) {
        mContext = context;
        mDataStore = dataStore;
        mCertificateTransparencyDownloader = certificateTransparencyDownloader;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mPendingIntent =
                PendingIntent.getBroadcast(
                        mContext,
                        /* requestCode= */ 0,
                        new Intent(ConfigUpdate.ACTION_UPDATE_CT_LOGS),
                        PendingIntent.FLAG_IMMUTABLE);
    }

    void schedule() {
        mContext.registerReceiver(
                this,
                new IntentFilter(ConfigUpdate.ACTION_UPDATE_CT_LOGS),
                Context.RECEIVER_EXPORTED);
        mAlarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime(), // schedule first job at earliest convenient time.
                AlarmManager.INTERVAL_DAY,
                mPendingIntent);

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyJob scheduled.");
        }
    }

    void cancel() {
        mContext.unregisterReceiver(this);
        mAlarmManager.cancel(mPendingIntent);
        mCertificateTransparencyDownloader.stop();
        mDependenciesReady = false;

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyJob canceled.");
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ConfigUpdate.ACTION_UPDATE_CT_LOGS.equals(intent.getAction())) {
            Log.w(TAG, "Received unexpected broadcast with action " + intent);
            return;
        }
        if (context.checkCallingOrSelfPermission(UPDATE_CONFIG_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Caller does not have UPDATE_CONFIG permission.");
            return;
        }
        if (Config.DEBUG) {
            Log.d(TAG, "Starting CT daily job.");
        }
        if (!mDependenciesReady) {
            mDataStore.load();
            mCertificateTransparencyDownloader.start();
            mDependenciesReady = true;
        }

        mDataStore.setProperty(Config.CONTENT_URL, Config.URL_LOG_LIST);
        mDataStore.setProperty(Config.METADATA_URL, Config.URL_SIGNATURE);
        mDataStore.setProperty(Config.PUBLIC_KEY_URL, Config.URL_PUBLIC_KEY);
        mDataStore.store();

        if (mCertificateTransparencyDownloader.startPublicKeyDownload() == -1) {
            Log.e(TAG, "Public key download not started.");
        } else if (Config.DEBUG) {
            Log.d(TAG, "Public key download started successfully.");
        }
    }
}
