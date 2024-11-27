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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.net.ct.DownloadHelper.DownloadStatus;

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

    @Mock private DownloadHelper mDownloadHelper;
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
        mDataStore.load();
        mSignatureVerifier = new SignatureVerifier(mContext);

        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(
                        mContext,
                        mDataStore,
                        mDownloadHelper,
                        mSignatureVerifier,
                        mCertificateTransparencyInstaller);
    }

    @After
    public void tearDown() {
        mTempFile.delete();
        mSignatureVerifier.resetPublicKey();
    }

    @Test
    public void testDownloader_startPublicKeyDownload() {
        String publicKeyUrl = "http://test-public-key.org";
        long downloadId = preparePublicKeyDownload(publicKeyUrl);

        assertThat(mCertificateTransparencyDownloader.isPublicKeyDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startPublicKeyDownload(publicKeyUrl);
        assertThat(mCertificateTransparencyDownloader.isPublicKeyDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_startMetadataDownload() {
        String metadataUrl = "http://test-metadata.org";
        long downloadId = prepareMetadataDownload(metadataUrl);

        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startMetadataDownload(metadataUrl);
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_startContentDownload() {
        String contentUrl = "http://test-content.org";
        long downloadId = prepareContentDownload(contentUrl);

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startContentDownload(contentUrl);
        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_publicKeyDownloadSuccess_updatePublicKey_startMetadataDownload()
            throws Exception {
        long publicKeyId = prepareSuccessfulPublicKeyDownload(writePublicKeyToFile(mPublicKey));
        long metadataId = prepareMetadataDownload("http://test-metadata.org");

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(metadataId)).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        assertThat(mSignatureVerifier.getPublicKey()).hasValue(mPublicKey);
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(metadataId)).isTrue();
    }

    @Test
    public void
            testDownloader_publicKeyDownloadSuccess_updatePublicKeyFail_doNotStartMetadataDownload()
                    throws Exception {
        long publicKeyId =
                prepareSuccessfulPublicKeyDownload(
                        writeToFile("i_am_not_a_base64_encoded_public_key".getBytes()));
        long metadataId = prepareMetadataDownload("http://test-metadata.org");

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(metadataId)).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(metadataId)).isFalse();
        verify(mDownloadHelper, never()).startDownload(anyString());
    }

    @Test
    public void testDownloader_publicKeyDownloadFail_doNotUpdatePublicKey() throws Exception {
        long publicKeyId =
                prepareFailedPublicKeyDownload(
                        // Failure cases where we give up on the download.
                        DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(publicKeyId);
        long metadataId = prepareMetadataDownload("http://test-metadata.org");

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(metadataId)).isFalse();
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        assertThat(mSignatureVerifier.getPublicKey()).isEmpty();
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(metadataId)).isFalse();
        verify(mDownloadHelper, never()).startDownload(anyString());
    }

    @Test
    public void testDownloader_metadataDownloadSuccess_startContentDownload() {
        long metadataId = prepareSuccessfulMetadataDownload(new File("log_list.sig"));
        long contentId = prepareContentDownload("http://test-content.org");

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(contentId)).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(metadataId));

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(contentId)).isTrue();
    }

    @Test
    public void testDownloader_metadataDownloadFail_doNotStartContentDownload() {
        long metadataId =
                prepareFailedMetadataDownload(
                        // Failure cases where we give up on the download.
                        DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(metadataId);
        long contentId = prepareContentDownload("http://test-content.org");

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(contentId)).isFalse();
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(contentId)).isFalse();
        verify(mDownloadHelper, never()).startDownload(anyString());
    }

    @Test
    public void testDownloader_contentDownloadSuccess_installSuccess_updateDataStore()
            throws Exception {
        String newVersion = "456";
        File logListFile = makeLogListFile(newVersion);
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        prepareSuccessfulMetadataDownload(metadataFile);
        long contentId = prepareSuccessfulContentDownload(logListFile);
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
        long contentId =
                prepareFailedContentDownload(
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
        prepareSuccessfulMetadataDownload(metadataFile);
        long contentId = prepareSuccessfulContentDownload(logListFile);
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
        prepareSuccessfulMetadataDownload(metadataFile);
        long contentId = prepareSuccessfulContentDownload(logListFile);

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
        prepareSuccessfulMetadataDownload(metadataFile);
        long contentId = prepareSuccessfulContentDownload(logListFile);

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
        String publicKeyUrl = "http://test-public-key.org";
        long publicKeyId = preparePublicKeyDownload(publicKeyUrl);

        mCertificateTransparencyDownloader.startPublicKeyDownload(publicKeyUrl);

        // 2. On successful public key download, set the key and start the metatadata download.
        setSuccessfulDownload(publicKeyId, publicKeyFile);
        long metadataId = prepareMetadataDownload("http://test-metadata.org");

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        // 3. On successful metadata download, start the content download.
        setSuccessfulDownload(metadataId, metadataFile);
        long contentId = prepareContentDownload("http://test-content.org");

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(metadataId));

        // 4. On successful content download, verify the signature and install the new version.
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
        assertThat(mDataStore.getProperty(Config.CONTENT_URL)).isNull();
        assertThat(mDataStore.getProperty(Config.METADATA_URL)).isNull();
    }

    private void assertInstallSuccessful(String version) {
        assertThat(mDataStore.getProperty(Config.VERSION)).isEqualTo(version);
        assertThat(mDataStore.getProperty(Config.CONTENT_URL))
                .isEqualTo(mDataStore.getProperty(Config.CONTENT_URL_PENDING));
        assertThat(mDataStore.getProperty(Config.METADATA_URL))
                .isEqualTo(mDataStore.getProperty(Config.METADATA_URL_PENDING));
    }

    private Intent makeDownloadCompleteIntent(long downloadId) {
        return new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
    }

    private long prepareDownloadId(String url) {
        long downloadId = mNextDownloadId++;
        when(mDownloadHelper.startDownload(url)).thenReturn(downloadId);
        return downloadId;
    }

    private long preparePublicKeyDownload(String url) {
        long downloadId = prepareDownloadId(url);
        mDataStore.setProperty(Config.PUBLIC_KEY_URL_PENDING, url);
        return downloadId;
    }

    private long prepareMetadataDownload(String url) {
        long downloadId = prepareDownloadId(url);
        mDataStore.setProperty(Config.METADATA_URL_PENDING, url);
        return downloadId;
    }

    private long prepareContentDownload(String url) {
        long downloadId = prepareDownloadId(url);
        mDataStore.setProperty(Config.CONTENT_URL_PENDING, url);
        return downloadId;
    }

    private long prepareSuccessfulDownload(String propertyKey) {
        long downloadId = mNextDownloadId++;
        mDataStore.setPropertyLong(propertyKey, downloadId);
        when(mDownloadHelper.getDownloadStatus(downloadId))
                .thenReturn(makeSuccessfulDownloadStatus(downloadId));
        return downloadId;
    }

    private long prepareSuccessfulDownload(String propertyKey, File file) {
        long downloadId = prepareSuccessfulDownload(propertyKey);
        when(mDownloadHelper.getUri(downloadId)).thenReturn(Uri.fromFile(file));
        return downloadId;
    }

    private long prepareSuccessfulPublicKeyDownload(File file) {
        long downloadId = prepareSuccessfulDownload(Config.PUBLIC_KEY_URL_KEY, file);
        mDataStore.setProperty(
                Config.METADATA_URL_PENDING, "http://public-key-was-downloaded-here.org");
        return downloadId;
    }

    private long prepareSuccessfulMetadataDownload(File file) {
        long downloadId = prepareSuccessfulDownload(Config.METADATA_URL_KEY, file);
        mDataStore.setProperty(
                Config.METADATA_URL_PENDING, "http://metadata-was-downloaded-here.org");
        return downloadId;
    }

    private long prepareSuccessfulContentDownload(File file) {
        long downloadId = prepareSuccessfulDownload(Config.CONTENT_URL_KEY, file);
        mDataStore.setProperty(
                Config.CONTENT_URL_PENDING, "http://content-was-downloaded-here.org");
        return downloadId;
    }

    private void setSuccessfulDownload(long downloadId, File file) {
        when(mDownloadHelper.getDownloadStatus(downloadId))
                .thenReturn(makeSuccessfulDownloadStatus(downloadId));
        when(mDownloadHelper.getUri(downloadId)).thenReturn(Uri.fromFile(file));
    }

    private long prepareFailedDownload(String propertyKey, int... downloadManagerErrors) {
        long downloadId = mNextDownloadId++;
        mDataStore.setPropertyLong(propertyKey, downloadId);
        DownloadStatus firstError =
                DownloadStatus.builder()
                        .setDownloadId(downloadId)
                        .setStatus(DownloadManager.STATUS_FAILED)
                        .setReason(downloadManagerErrors[0])
                        .build();
        DownloadStatus[] otherErrors = new DownloadStatus[downloadManagerErrors.length - 1];
        for (int i = 1; i < downloadManagerErrors.length; i++) {
            otherErrors[i - 1] =
                    DownloadStatus.builder()
                            .setDownloadId(downloadId)
                            .setStatus(DownloadManager.STATUS_FAILED)
                            .setReason(downloadManagerErrors[i])
                            .build();
        }
        when(mDownloadHelper.getDownloadStatus(downloadId)).thenReturn(firstError, otherErrors);
        return downloadId;
    }

    private long prepareFailedPublicKeyDownload(int... downloadManagerErrors) {
        return prepareFailedDownload(Config.PUBLIC_KEY_URL_KEY, downloadManagerErrors);
    }

    private long prepareFailedMetadataDownload(int... downloadManagerErrors) {
        return prepareFailedDownload(Config.METADATA_URL_KEY, downloadManagerErrors);
    }

    private long prepareFailedContentDownload(int... downloadManagerErrors) {
        return prepareFailedDownload(Config.CONTENT_URL_KEY, downloadManagerErrors);
    }

    private DownloadStatus makeSuccessfulDownloadStatus(long downloadId) {
        return DownloadStatus.builder()
                .setDownloadId(downloadId)
                .setStatus(DownloadManager.STATUS_SUCCESSFUL)
                .build();
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
