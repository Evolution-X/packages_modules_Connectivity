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

package com.android.cts.net.hostside.app2;


import static android.net.TetheringManager.TETHERING_WIFI;

import android.app.Service;
import android.content.Intent;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.os.IBinder;

import androidx.annotation.NonNull;

import com.android.cts.net.hostside.ITetheringHelper;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TetheringHelperService extends Service {
    private static final String TAG = TetheringHelperService.class.getSimpleName();

    private ITetheringHelper.Stub mBinder = new ITetheringHelper.Stub() {
        public TetheringInterface getTetheredWifiInterface() {
            ArrayBlockingQueue<TetheringInterface> queue = new ArrayBlockingQueue<>(1);
            TetheringManager.TetheringEventCallback callback =
                    new TetheringManager.TetheringEventCallback() {
                        @Override
                        public void onTetheredInterfacesChanged(
                                @NonNull Set<TetheringInterface> interfaces) {
                            for (TetheringInterface iface : interfaces) {
                                if (iface.getType() == TETHERING_WIFI) {
                                    queue.offer(iface);
                                    break;
                                }
                            }
                        }
                    };
            TetheringManager tm =
                    TetheringHelperService.this.getSystemService(TetheringManager.class);
            tm.registerTetheringEventCallback(Runnable::run, callback);
            TetheringInterface iface;
            try {
                iface = queue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException("Wait for wifi TetheredInterface interrupted");
            } finally {
                tm.unregisterTetheringEventCallback(callback);
            }
            if (iface == null) {
                throw new IllegalStateException(
                        "No wifi TetheredInterface received after 5 seconds");
            }
            return iface;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
