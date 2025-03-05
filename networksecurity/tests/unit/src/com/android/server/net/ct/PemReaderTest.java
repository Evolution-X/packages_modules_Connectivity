/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

/** Tests for the {@link PemReader}. */
@RunWith(JUnit4.class)
public class PemReaderTest {

    @Test
    public void testReadKeys_singleKey() throws GeneralSecurityException, IOException {
        PublicKey key = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();

        assertThat(PemReader.readKeysFrom(toInputStream(key))).containsExactly(key);
    }

    @Test
    public void testReadKeys_multipleKeys() throws GeneralSecurityException, IOException {
        KeyPairGenerator instance = KeyPairGenerator.getInstance("RSA");
        PublicKey key1 = instance.generateKeyPair().getPublic();
        PublicKey key2 = instance.generateKeyPair().getPublic();

        assertThat(PemReader.readKeysFrom(toInputStream(key1, key2))).containsExactly(key1, key2);
    }

    @Test
    public void testReadKeys_notSupportedKeyType() throws GeneralSecurityException {
        PublicKey key = KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic();

        assertThrows(
                GeneralSecurityException.class, () -> PemReader.readKeysFrom(toInputStream(key)));
    }

    @Test
    public void testReadKeys_notBase64EncodedKey() throws GeneralSecurityException {
        InputStream inputStream =
                new ByteArrayInputStream(
                        (""
                                        + "-----BEGIN PUBLIC KEY-----\n"
                                        + KeyPairGenerator.getInstance("RSA")
                                                .generateKeyPair()
                                                .getPublic()
                                                .toString()
                                        + "\n-----END PUBLIC KEY-----\n")
                                .getBytes());

        assertThrows(GeneralSecurityException.class, () -> PemReader.readKeysFrom(inputStream));
    }

    @Test
    public void testReadKeys_noPemBegin() throws GeneralSecurityException {
        PublicKey key = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();
        String base64Key = Base64.getEncoder().encodeToString(key.getEncoded());
        String pemNoBegin = base64Key + "\n-----END PUBLIC KEY-----\n";

        assertThrows(
                IOException.class,
                () -> PemReader.readKeysFrom(new ByteArrayInputStream(pemNoBegin.getBytes())));
    }

    @Test
    public void testReadKeys_noPemEnd() throws GeneralSecurityException {
        PublicKey key = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();
        String base64Key = Base64.getEncoder().encodeToString(key.getEncoded());
        String pemNoEnd = "-----BEGIN PUBLIC KEY-----\n" + base64Key;

        assertThrows(
                IOException.class,
                () -> PemReader.readKeysFrom(new ByteArrayInputStream(pemNoEnd.getBytes())));
    }

    private InputStream toInputStream(PublicKey... keys) {
        StringBuilder builder = new StringBuilder();

        for (PublicKey key : keys) {
            builder.append("-----BEGIN PUBLIC KEY-----\n")
                    .append(Base64.getEncoder().encodeToString(key.getEncoded()))
                    .append("\n-----END PUBLIC KEY-----\n");
        }

        return new ByteArrayInputStream(builder.toString().getBytes());
    }
}
