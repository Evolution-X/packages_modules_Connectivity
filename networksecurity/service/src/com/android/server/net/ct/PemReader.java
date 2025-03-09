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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.KeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;

/** Utility class to read keys in PEM format. */
class PemReader {

    private static final String BEGIN = "-----BEGIN";
    private static final String END = "-----END";

    /**
     * Parse the provided input stream and return the list of keys from the stream.
     *
     * @param input the input stream
     * @return the keys
     */
    public static Collection<PublicKey> readKeysFrom(InputStream input)
            throws IOException, GeneralSecurityException {
        KeyFactory instance = KeyFactory.getInstance("RSA");
        Collection<PublicKey> keys = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith(BEGIN)) {
                    keys.add(instance.generatePublic(readNextKey(reader)));
                } else {
                    throw new IOException("Unexpected line in the reader: " + line);
                }
                line = reader.readLine();
            }
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid public key base64 encoding", e);
        }

        return keys;
    }

    private static KeySpec readNextKey(BufferedReader reader) throws IOException {
        StringBuilder publicKeyBuilder = new StringBuilder();

        String line = reader.readLine();
        while (line != null) {
            if (line.startsWith(END)) {
                return new X509EncodedKeySpec(
                        Base64.getDecoder().decode(publicKeyBuilder.toString()));
            } else {
                publicKeyBuilder.append(line);
            }
            line = reader.readLine();
        }

        throw new IOException("Unexpected end of the reader");
    }
}
