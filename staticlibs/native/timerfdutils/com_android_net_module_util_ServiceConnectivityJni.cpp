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

#include <errno.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_utf_chars.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#define MSEC_PER_SEC 1000
#define NSEC_PER_MSEC 1000000

namespace android {

static jint
com_android_net_module_util_ServiceConnectivityJni_createTimerFd(JNIEnv *env,
                                                       jclass clazz) {
  int tfd;
  tfd = timerfd_create(CLOCK_BOOTTIME, 0);
  if (tfd == -1) {
    jniThrowErrnoException(env, "createTimerFd", tfd);
  }
  return tfd;
}

static void
com_android_net_module_util_ServiceConnectivityJni_setTime(JNIEnv *env, jclass clazz,
                                                 jint tfd, jlong milliseconds) {
  struct itimerspec new_value;
  new_value.it_value.tv_sec = milliseconds / MSEC_PER_SEC;
  new_value.it_value.tv_nsec = (milliseconds % MSEC_PER_SEC) * NSEC_PER_MSEC;
  // Set the interval time to 0 because it's designed for repeated timer expirations after the
  // initial expiration, which doesn't fit the current usage.
  new_value.it_interval.tv_sec = 0;
  new_value.it_interval.tv_nsec = 0;

  int ret = timerfd_settime(tfd, 0, &new_value, NULL);
  if (ret == -1) {
    jniThrowErrnoException(env, "setTime", ret);
  }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"createTimerFd", "()I",
     (void *)com_android_net_module_util_ServiceConnectivityJni_createTimerFd},
    {"setTime", "(IJ)V",
     (void *)com_android_net_module_util_ServiceConnectivityJni_setTime},
};

int register_com_android_net_module_util_ServiceConnectivityJni(JNIEnv *env,
                                                      char const *class_name) {
  return jniRegisterNativeMethods(env, class_name, gMethods, NELEM(gMethods));
}

}; // namespace android
