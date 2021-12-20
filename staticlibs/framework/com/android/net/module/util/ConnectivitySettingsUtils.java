/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Collection of connectivity settings utilities.
 *
 * @hide
 */
public class ConnectivitySettingsUtils {
    public static final String TAG = ConnectivitySettingsUtils.class.getSimpleName();
    public static final int PRIVATE_DNS_MODE_OFF = 1;
    public static final int PRIVATE_DNS_MODE_OPPORTUNISTIC = 2;
    public static final int PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3;
    public static final int PRIVATE_DNS_MODE_ADGUARD = 4;
    public static final int PRIVATE_DNS_MODE_APPLIEDPRIVACY = 5;
    public static final int PRIVATE_DNS_MODE_CLEANBROWSING = 6;
    public static final int PRIVATE_DNS_MODE_CIRA = 7;
    public static final int PRIVATE_DNS_MODE_CZNIC = 8;
    public static final int PRIVATE_DNS_MODE_CLOUDFLARE = 9;
    public static final int PRIVATE_DNS_MODE_GOOGLE = 10;
    public static final int PRIVATE_DNS_MODE_MULLVAD = 11;
    public static final int PRIVATE_DNS_MODE_QUADNINE = 12;
    public static final int PRIVATE_DNS_MODE_RESTENA = 13;
    public static final int PRIVATE_DNS_MODE_SWITCH = 14;
    public static final int PRIVATE_DNS_MODE_TWNIC = 15;
    public static final int PRIVATE_DNS_MODE_UNCENSOREDDNS = 16;

    public static final String PRIVATE_DNS_DEFAULT_MODE = "private_dns_default_mode";
    public static final String PRIVATE_DNS_MODE = "private_dns_mode";
    public static final String PRIVATE_DNS_MODE_OFF_STRING = "off";
    public static final String PRIVATE_DNS_MODE_ADGUARD_STRING = "adguard";
    public static final String PRIVATE_DNS_MODE_APPLIEDPRIVACY_STRING = "appliedprivacy";
    public static final String PRIVATE_DNS_MODE_CLEANBROWSING_STRING = "cleanbrowsing";
    public static final String PRIVATE_DNS_MODE_CIRA_STRING = "cira";
    public static final String PRIVATE_DNS_MODE_CZNIC_STRING = "cznic";
    public static final String PRIVATE_DNS_MODE_CLOUDFLARE_STRING = "cloudflare";
    public static final String PRIVATE_DNS_MODE_GOOGLE_STRING = "google";
    public static final String PRIVATE_DNS_MODE_MULLVAD_STRING = "mullvad";
    public static final String PRIVATE_DNS_MODE_QUADNINE_STRING = "quadnine";
    public static final String PRIVATE_DNS_MODE_RESTENA_STRING = "restena";
    public static final String PRIVATE_DNS_MODE_SWITCH_STRING = "switch";
    public static final String PRIVATE_DNS_MODE_TWNIC_STRING = "twnic";
    public static final String PRIVATE_DNS_MODE_UNCENSOREDDNS_STRING = "uncensoreddns";
    public static final String PRIVATE_DNS_MODE_OPPORTUNISTIC_STRING = "opportunistic";
    public static final String PRIVATE_DNS_MODE_PROVIDER_HOSTNAME_STRING = "hostname";
    public static final String PRIVATE_DNS_SPECIFIER = "private_dns_specifier";

    public static final String NETWORK_AVOID_BAD_WIFI = "network_avoid_bad_wifi";
    public static final String NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI =
            "network_carrier_aware_avoid_bad_wifi";

    /**
     * Get private DNS mode as string.
     *
     * @param mode One of the private DNS values.
     * @return A string of private DNS mode.
     */
    public static String getPrivateDnsModeAsString(int mode) {
        switch (mode) {
            case PRIVATE_DNS_MODE_OFF:
                return PRIVATE_DNS_MODE_OFF_STRING;
            case PRIVATE_DNS_MODE_ADGUARD:
                return PRIVATE_DNS_MODE_ADGUARD_STRING;
            case PRIVATE_DNS_MODE_APPLIEDPRIVACY:
                return PRIVATE_DNS_MODE_APPLIEDPRIVACY_STRING;
            case PRIVATE_DNS_MODE_CLEANBROWSING:
                return PRIVATE_DNS_MODE_CLEANBROWSING_STRING;
            case PRIVATE_DNS_MODE_CIRA:
                return PRIVATE_DNS_MODE_CIRA_STRING;
            case PRIVATE_DNS_MODE_CZNIC:
                return PRIVATE_DNS_MODE_CZNIC_STRING;
            case PRIVATE_DNS_MODE_CLOUDFLARE:
                return PRIVATE_DNS_MODE_CLOUDFLARE_STRING;
            case PRIVATE_DNS_MODE_GOOGLE:
                return PRIVATE_DNS_MODE_GOOGLE_STRING;
            case PRIVATE_DNS_MODE_MULLVAD:
                return PRIVATE_DNS_MODE_MULLVAD_STRING;
            case PRIVATE_DNS_MODE_QUADNINE:
                return PRIVATE_DNS_MODE_QUADNINE_STRING;
            case PRIVATE_DNS_MODE_RESTENA:
                return PRIVATE_DNS_MODE_RESTENA_STRING;
            case PRIVATE_DNS_MODE_SWITCH:
                return PRIVATE_DNS_MODE_SWITCH_STRING;
            case PRIVATE_DNS_MODE_TWNIC:
                return PRIVATE_DNS_MODE_TWNIC_STRING;
            case PRIVATE_DNS_MODE_UNCENSOREDDNS:
                return PRIVATE_DNS_MODE_UNCENSOREDDNS_STRING;
            case PRIVATE_DNS_MODE_OPPORTUNISTIC:
                return PRIVATE_DNS_MODE_OPPORTUNISTIC_STRING;
            case PRIVATE_DNS_MODE_PROVIDER_HOSTNAME:
                return PRIVATE_DNS_MODE_PROVIDER_HOSTNAME_STRING;
            default:
                throw new IllegalArgumentException("Invalid private dns mode: " + mode);
        }
    }

    private static int getPrivateDnsModeAsInt(String mode) {
        // If both PRIVATE_DNS_MODE and PRIVATE_DNS_DEFAULT_MODE are not set, choose
        // PRIVATE_DNS_MODE_OPPORTUNISTIC as default mode.
        if (TextUtils.isEmpty(mode))
            return PRIVATE_DNS_MODE_OPPORTUNISTIC;
        switch (mode) {
            case "off":
                return PRIVATE_DNS_MODE_OFF;
            case "adguard":
                return PRIVATE_DNS_MODE_ADGUARD;
            case "appliedprivacy":
                return PRIVATE_DNS_MODE_APPLIEDPRIVACY;
            case "cleanbrowsing":
                return PRIVATE_DNS_MODE_CLEANBROWSING;
            case "cira":
                return PRIVATE_DNS_MODE_CIRA;
            case "cznic":
                return PRIVATE_DNS_MODE_CZNIC;
            case "cloudflare":
                return PRIVATE_DNS_MODE_CLOUDFLARE;
            case "google":
                return PRIVATE_DNS_MODE_GOOGLE;
            case "mullvad":
                return PRIVATE_DNS_MODE_MULLVAD;
            case "quadnine":
                return PRIVATE_DNS_MODE_QUADNINE;
            case "restena":
                return PRIVATE_DNS_MODE_RESTENA;
            case "switch":
                return PRIVATE_DNS_MODE_SWITCH;
            case "twnic":
                return PRIVATE_DNS_MODE_TWNIC;
            case "uncensoreddns":
                return PRIVATE_DNS_MODE_UNCENSOREDDNS;
            case "hostname":
                return PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
            case "opportunistic":
                return PRIVATE_DNS_MODE_OPPORTUNISTIC;
            default:
                // b/260211513: adb shell settings put global private_dns_mode foo
                // can result in arbitrary strings - treat any unknown value as empty string.
                // throw new IllegalArgumentException("Invalid private dns mode: " + mode);
                return PRIVATE_DNS_MODE_OPPORTUNISTIC;
        }
    }

    /**
     * Generates a unique setting key for the "avoid bad Wi-Fi" feature,
     * specific to a given cellular subscription ID.
     * This key is typically used to store and retrieve a preference
     * that controls how the device manages Wi-Fi connectivity
     * in the context of a particular cellular carrier.
     *
     * @param subId The unique identifier of the cellular subscription.
     * @return A {@code String} representing the unique setting key.
     * The key is constructed by appending the {@code subId} to a
     * base constant string for carrier-aware "avoid bad Wi-Fi" settings,
     * separated by a forward slash.
     */
    public static String getAvoidBadWifiSettingKey(int subId) {
        return NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI + "/" + subId;
    }

    /**
     * Get private DNS mode from settings.
     *
     * @param context The Context to query the private DNS mode from settings.
     * @return An integer of private DNS mode.
     */
    public static int getPrivateDnsMode(@NonNull Context context) {
        final ContentResolver cr = context.getContentResolver();
        String mode = Settings.Global.getString(cr, PRIVATE_DNS_MODE);
        if (TextUtils.isEmpty(mode)) mode = Settings.Global.getString(cr, PRIVATE_DNS_DEFAULT_MODE);
        return getPrivateDnsModeAsInt(mode);
    }

    /**
     * Set private DNS mode to settings.
     *
     * @param context The {@link Context} to set the private DNS mode.
     * @param mode The private dns mode. This should be one of the PRIVATE_DNS_MODE_* constants.
     */
    public static void setPrivateDnsMode(@NonNull Context context, int mode) {
        if (!(mode == PRIVATE_DNS_MODE_OFF
                || mode == PRIVATE_DNS_MODE_ADGUARD
                || mode == PRIVATE_DNS_MODE_APPLIEDPRIVACY
                || mode == PRIVATE_DNS_MODE_CLEANBROWSING
                || mode == PRIVATE_DNS_MODE_CIRA
                || mode == PRIVATE_DNS_MODE_CZNIC
                || mode == PRIVATE_DNS_MODE_CLOUDFLARE
                || mode == PRIVATE_DNS_MODE_GOOGLE
                || mode == PRIVATE_DNS_MODE_MULLVAD
                || mode == PRIVATE_DNS_MODE_QUADNINE
                || mode == PRIVATE_DNS_MODE_RESTENA
                || mode == PRIVATE_DNS_MODE_SWITCH
                || mode == PRIVATE_DNS_MODE_TWNIC
                || mode == PRIVATE_DNS_MODE_UNCENSOREDDNS
                || mode == PRIVATE_DNS_MODE_OPPORTUNISTIC
                || mode == PRIVATE_DNS_MODE_PROVIDER_HOSTNAME)) {
            throw new IllegalArgumentException("Invalid private dns mode: " + mode);
        }
        Settings.Global.putString(context.getContentResolver(), PRIVATE_DNS_MODE,
                getPrivateDnsModeAsString(mode));
    }

    /**
     * Get specific private dns provider name from {@link Settings}.
     *
     * @param context The {@link Context} to query the setting.
     * @return The specific private dns provider name, or null if no setting value.
     */
    @Nullable
    public static String getPrivateDnsHostname(@NonNull Context context) {
        return Settings.Global.getString(context.getContentResolver(), PRIVATE_DNS_SPECIFIER);
    }

    /**
     * Set specific private dns provider name to {@link Settings}.
     *
     * @param context The {@link Context} to set the setting.
     * @param specifier The specific private dns provider name.
     */
    public static void setPrivateDnsHostname(@NonNull Context context, @Nullable String specifier) {
        Settings.Global.putString(context.getContentResolver(), PRIVATE_DNS_SPECIFIER, specifier);
    }

    /**
     * Set legacy global avoid bad wifi to {@link Settings}.
     *
     * @param context The {@link Context} to set the setting.
     * @param setting The desired setting value.
     * "0": Don't avoid bad Wi-Fi.
     * "1": Avoid bad Wi-Fi.
     * {@code null}: Ask the user whether to switch away from bad Wi-Fi.
     * @deprecated Use {@link #setNetworkAvoidBadWifiSetting(Context, String)} instead.
     */
    @Deprecated
    public static void setNetworkLegacyGlobalAvoidBadWifiSetting(
            @NonNull Context context, @Nullable String setting) {
        Settings.Global.putString(context.getContentResolver(), NETWORK_AVOID_BAD_WIFI, setting);
    }

    /**
     * Get legacy global avoid bad wifi to {@link Settings}.
     *
     * @param context The {@link Context} to query the setting.
     * @return The current setting value, which can be "0", "1", or {@code null}.
     * Returns {@code null} if the setting is not found.
     * @deprecated Use {@link #getNetworkAvoidBadWifiSetting(Context)} instead.
     */
    @Deprecated
    @Nullable
    public static String getNetworkLegacyGlobalAvoidBadWifiSetting(@NonNull Context context) {
        return Settings.Global.getString(context.getContentResolver(), NETWORK_AVOID_BAD_WIFI);
    }

    /**
     * Set the carrier-aware avoid bad wifi to {@link Settings}.
     *
     * @param context The {@link Context} to set the setting.
     * @param subId The subscription ID
     * @param setting The desired setting string.
     * subId: The carrier subscription ID (integer).
     * value: "0" to not avoid bad Wi-Fi for this subscription, or "1" to avoid.
     * {@code null}: Ask the user whether to switch away from bad Wi-Fi.
     */
    public static void setNetworkAvoidBadWifiSetting(
                @NonNull Context context, int subId, @Nullable String setting) {
        Settings.Global.putString(
                context.getContentResolver(), getAvoidBadWifiSettingKey(subId), setting);
    }

    /**
     * Get the raw carrier-aware avoid bad wifi string from {@link Settings}.
     * @see #convertCarrierAwareSettingsStringToMap(String)
     *
     * @param context The {@link Context} to set the setting.
     * @param subId The subscription ID.
     * @return The current setting string, formatted as
     * "network_carrier_aware_avoid_bad_wifi/{subId}" or {@code null} if the setting is not set.
     */
    @Nullable
    public static String getNetworkAvoidBadWifiSetting(@NonNull Context context, int subId) {
        return Settings.Global.getString(
            context.getContentResolver(), getAvoidBadWifiSettingKey(subId));
    }
}
