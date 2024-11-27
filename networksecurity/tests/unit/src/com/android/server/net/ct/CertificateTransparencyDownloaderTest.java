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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/** Tests for the {@link CertificateTransparencyDownloader}. */
@RunWith(JUnit4.class)
public class CertificateTransparencyDownloaderTest {

    @Mock private DownloadManager mDownloadManager;
    @Mock private CertificateTransparencyInstaller mCertificateTransparencyInstaller;

    private PrivateKey mPrivateKey;
    private PublicKey mPublicKey;
    private Context mContext;
    private File mTempFile;
    private DataStore mDataStore;
    private SignatureVerifier mSignatureVerifier;
    private CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    private long mNextDownloadId = 666;

    @Before
    public void setUp() throws IOException, GeneralSecurityException {
        MockitoAnnotations.initMocks(this);
        KeyPairGenerator instance = KeyPairGenerator.getInstance("RSA");
        KeyPair keyPair = instance.generateKeyPair();
        mPrivateKey = keyPair.getPrivate();
        mPublicKey = keyPair.getPublic();

        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mTempFile = File.createTempFile("datastore-test", ".properties");
        mDataStore = new DataStore(mTempFile);
        mSignatureVerifier = new SignatureVerifier(mContext);
        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(
                        mContext,
                        mDataStore,
                        new DownloadHelper(mDownloadManager),
                        mSignatureVerifier,
                        mCertificateTransparencyInstaller);

        prepareDataStore();
        prepareDownloadManager();
    }

    @After
    public void tearDown() {
        mTempFile.delete();
        mSignatureVerifier.resetPublicKey();
    }

    @Test
    public void testDownloader_startPublicKeyDownload() {
        assertThat(mCertificateTransparencyDownloader.hasPublicKeyDownloadId()).isFalse();
        long downloadId = mCertificateTransparencyDownloader.startPublicKeyDownload();

        assertThat(mCertificateTransparencyDownloader.hasPublicKeyDownloadId()).isTrue();
        assertThat(mCertificateTransparencyDownloader.isPublicKeyDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_startMetadataDownload() {
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        long downloadId = mCertificateTransparencyDownloader.startMetadataDownload();

        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isTrue();
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_startContentDownload() {
        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();
        long downloadId = mCertificateTransparencyDownloader.startContentDownload();

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isTrue();
        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_publicKeyDownloadSuccess_updatePublicKey_startMetadataDownload()
            throws Exception {
        long publicKeyId = mCertificateTransparencyDownloader.startPublicKeyDownload();
        setSuccessfulDownload(publicKeyId, writePublicKeyToFile(mPublicKey));

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        assertThat(mSignatureVerifier.getPublicKey()).hasValue(mPublicKey);
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isTrue();
    }

    @Test
    public void
            testDownloader_publicKeyDownloadSuccess_updatePublicKeyFail_doNotStartMetadataDownload()
                    throws Exception {
        long publicKeyId = mCertificateTransparencyDownloader.startPublicKeyDownload();
        setSuccessfulDownload(
                publicKeyId, writeToFile("i_am_not_a_base64_encoded_public_key".getBytes()));

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
    }

    @Test
    public void testDownloader_publicKeyDownloadFail_doNotUpdatePublicKey() throws Exception {
        long publicKeyId = mCertificateTransparencyDownloader.startPublicKeyDownload();
        setFailedDownload(
                publicKeyId, // Failure cases where we give up on the download.
                DownloadManager.ERROR_INSUFFICIENT_SPACE,
                DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(publicKeyId);

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.hasMetadataDownloadId()).isFalse();
    }

    @Test
    public void testDownloader_metadataDownloadSuccess_startContentDownload() {
        long metadataId = mCertificateTransparencyDownloader.startMetadataDownload();
        setSuccessfulDownload(metadataId, new File("log_list.sig"));

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(metadataId));

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isTrue();
    }

    @Test
    public void testDownloader_metadataDownloadFail_doNotStartContentDownload() {
        long metadataId = mCertificateTransparencyDownloader.startMetadataDownload();
        setFailedDownload(
                metadataId,
                // Failure cases where we give up on the download.
                DownloadManager.ERROR_INSUFFICIENT_SPACE,
                DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(metadataId);

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        assertThat(mCertificateTransparencyDownloader.hasContentDownloadId()).isFalse();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_installSuccess_updateDataStore()
            throws Exception {
        String newVersion = "456";
        File logListFile = makeLogListFile(newVersion);
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        long metadataId = mCertificateTransparencyDownloader.startMetadataDownload();
        setSuccessfulDownload(metadataId, metadataFile);
        long contentId = mCertificateTransparencyDownloader.startContentDownload();
        setSuccessfulDownload(contentId, logListFile);
        when(mCertificateTransparencyInstaller.install(
                        eq(Config.COMPATIBILITY_VERSION), any(), anyString()))
                .thenReturn(true);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        assertInstallSuccessful(newVersion);
    }

    @Test
    public void testDownloader_contentDownloadFail_doNotInstall() throws Exception {
        long contentId = mCertificateTransparencyDownloader.startContentDownload();
        setFailedDownload(
                contentId,
                // Failure cases where we give up on the download.
                DownloadManager.ERROR_INSUFFICIENT_SPACE,
                DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(contentId);

        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        verify(mCertificateTransparencyInstaller, never()).install(any(), any(), any());
        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_installFail_doNotUpdateDataStore()
            throws Exception {
        File logListFile = makeLogListFile("456");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        long metadataId = mCertificateTransparencyDownloader.startMetadataDownload();
        setSuccessfulDownload(metadataId, metadataFile);
        long contentId = mCertificateTransparencyDownloader.startContentDownload();
        setSuccessfulDownload(contentId, logListFile);
        when(mCertificateTransparencyInstaller.install(
                        eq(Config.COMPATIBILITY_VERSION), any(), anyString()))
                .thenReturn(false);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_verificationFail_doNotInstall()
            throws Exception {
        File logListFile = makeLogListFile("456");
        File metadataFile = File.createTempFile("log_list-wrong_metadata", "sig");
        mSignatureVerifier.setPublicKey(mPublicKey);
        long metadataId = mCertificateTransparencyDownloader.startMetadataDownload();
        setSuccessfulDownload(metadataId, metadataFile);
        long contentId = mCertificateTransparencyDownloader.startContentDownload();
        setSuccessfulDownload(contentId, logListFile);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        verify(mCertificateTransparencyInstaller, never())
                .install(eq(Config.COMPATIBILITY_VERSION), any(), anyString());
        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_missingVerificationPublicKey_doNotInstall()
            throws Exception {
        File logListFile = makeLogListFile("456");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.resetPublicKey();
        long metadataId = mCertificateTransparencyDownloader.startMetadataDownload();
        setSuccessfulDownload(metadataId, metadataFile);
        long contentId = mCertificateTransparencyDownloader.startContentDownload();
        setSuccessfulDownload(contentId, logListFile);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        verify(mCertificateTransparencyInstaller, never())
                .install(eq(Config.COMPATIBILITY_VERSION), any(), anyString());
        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_endToEndSuccess_installNewVersion() throws Exception {
        String newVersion = "456";
        File logListFile = makeLogListFile(newVersion);
        File metadataFile = sign(logListFile);
        File publicKeyFile = writePublicKeyToFile(mPublicKey);

        assertNoVersionIsInstalled();

        // 1. Start download of public key.
        long publicKeyId = mCertificateTransparencyDownloader.startPublicKeyDownload();

        // 2. On successful public key download, set the key and start the metatadata download.
        setSuccessfulDownload(publicKeyId, publicKeyFile);

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        // 3. On successful metadata download, start the content download.
        long metadataId = mCertificateTransparencyDownloader.getMetadataDownloadId();
        setSuccessfulDownload(metadataId, metadataFile);

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(metadataId));

        // 4. On successful content download, verify the signature and install the new version.
        long contentId = mCertificateTransparencyDownloader.getContentDownloadId();
        setSuccessfulDownload(contentId, logListFile);
        when(mCertificateTransparencyInstaller.install(
                        eq(Config.COMPATIBILITY_VERSION), any(), anyString()))
                .thenReturn(true);

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        assertInstallSuccessful(newVersion);
    }

    private void assertNoVersionIsInstalled() {
        assertThat(mDataStore.getProperty(Config.VERSION)).isNull();
    }

    private void assertInstallSuccessful(String version) {
        assertThat(mDataStore.getProperty(Config.VERSION)).isEqualTo(version);
    }

    private Intent makeDownloadCompleteIntent(long downloadId) {
        return new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
    }

    private void prepareDataStore() {
        mDataStore.load();
        mDataStore.setProperty(Config.CONTENT_URL, Config.URL_LOG_LIST);
        mDataStore.setProperty(Config.METADATA_URL, Config.URL_SIGNATURE);
        mDataStore.setProperty(Config.PUBLIC_KEY_URL, Config.URL_PUBLIC_KEY);
    }

    private void prepareDownloadManager() {
        when(mDownloadManager.enqueue(any(Request.class)))
                .thenAnswer(invocation -> mNextDownloadId++);
    }

    private Cursor makeSuccessfulDownloadCursor() {
        MatrixCursor cursor =
                new MatrixCursor(
                        new String[] {
                            DownloadManager.COLUMN_STATUS, DownloadManager.COLUMN_REASON
                        });
        cursor.addRow(new Object[] {DownloadManager.STATUS_SUCCESSFUL, -1});
        return cursor;
    }

    private void setSuccessfulDownload(long downloadId, File file) {
        when(mDownloadManager.query(any(Query.class))).thenReturn(makeSuccessfulDownloadCursor());
        when(mDownloadManager.getUriForDownloadedFile(downloadId)).thenReturn(Uri.fromFile(file));
    }

    private Cursor makeFailedDownloadCursor(int error) {
        MatrixCursor cursor =
                new MatrixCursor(
                        new String[] {
                            DownloadManager.COLUMN_STATUS, DownloadManager.COLUMN_REASON
                        });
        cursor.addRow(new Object[] {DownloadManager.STATUS_FAILED, error});
        return cursor;
    }

    private void setFailedDownload(long downloadId, int... downloadManagerErrors) {
        Cursor first = makeFailedDownloadCursor(downloadManagerErrors[0]);
        Cursor[] others = new Cursor[downloadManagerErrors.length - 1];
        for (int i = 1; i < downloadManagerErrors.length; i++) {
            others[i - 1] = makeFailedDownloadCursor(downloadManagerErrors[i]);
        }
        when(mDownloadManager.query(any())).thenReturn(first, others);
        when(mDownloadManager.getUriForDownloadedFile(downloadId)).thenReturn(null);
    }

    private File writePublicKeyToFile(PublicKey publicKey)
            throws IOException, GeneralSecurityException {
        return writeToFile(Base64.getEncoder().encode(publicKey.getEncoded()));
    }

    private File writeToFile(byte[] bytes) throws IOException, GeneralSecurityException {
        File file = File.createTempFile("temp_file", "tmp");

        try (OutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(bytes);
        }

        return file;
    }

    private File makeLogListFile(String version) throws IOException, JSONException {
        File logListFile = File.createTempFile("log_list", "json");

        try (OutputStream outputStream = new FileOutputStream(logListFile)) {
            outputStream.write(new JSONObject().put("version", version).toString().getBytes(UTF_8));
        }

        return logListFile;
    }

    private File sign(File file) throws IOException, GeneralSecurityException {
        File signatureFile = File.createTempFile("log_list-metadata", "sig");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(mPrivateKey);

        try (InputStream fileStream = new FileInputStream(file);
                OutputStream outputStream = new FileOutputStream(signatureFile)) {
            signer.update(fileStream.readAllBytes());
            outputStream.write(signer.sign());
        }

        return signatureFile;
    }
}
