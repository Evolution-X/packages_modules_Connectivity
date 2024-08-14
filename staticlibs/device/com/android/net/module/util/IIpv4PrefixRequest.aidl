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

package com.android.net.module.util;

import android.net.IpPrefix;
import android.net.LinkAddress;

/** @hide */
// TODO: b/350630377 - This @Descriptor annotation workaround is to prevent the class from being
// jarjared which changes the DESCRIPTOR and casues "java.lang.SecurityException: Binder invocation
// to an incorrect interface" when calling the IPC.
@Descriptor("value=no.jarjar.com.android.net.module.util.IIpv4PrefixRequest")
interface IIpv4PrefixRequest {
    void onIpv4PrefixConflict(in IpPrefix ipPrefix);
}
