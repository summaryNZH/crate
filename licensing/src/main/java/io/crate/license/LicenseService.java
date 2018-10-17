/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.license;

import com.google.common.annotations.VisibleForTesting;
import io.crate.license.exception.InvalidLicenseException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.gateway.Gateway;
import org.elasticsearch.gateway.GatewayService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static io.crate.license.LicenseKey.LicenseType;
import static io.crate.license.LicenseKey.decodeLicense;


public class LicenseService extends AbstractLifecycleComponent implements ClusterStateListener, Gateway.GatewayStateRecoveredListener {

    private final TransportSetLicenseAction transportSetLicenseAction;
    private final ClusterService clusterService;

    private AtomicReference<DecryptedLicenseData> currentLicense = new AtomicReference<>();

    @Inject
    public LicenseService(Settings settings,
                          TransportSetLicenseAction transportSetLicenseAction,
                          ClusterService clusterService) {
        super(settings);
        this.transportSetLicenseAction = transportSetLicenseAction;
        this.clusterService = clusterService;
    }

    public void registerLicense(final LicenseKey licenseKey,
                                final ActionListener<SetLicenseResponse> listener) {
        if (verifyLicense(licenseKey)) {
            transportSetLicenseAction.execute(new SetLicenseRequest(licenseKey), listener);
        } else {
            listener.onFailure(new InvalidLicenseException("Unable to register the provided license key"));
        }
    }

    /**
     * Encrypts the provided license data and creates a #{@link LicenseKey}
     */
    @VisibleForTesting
    LicenseKey createLicenseKey(LicenseType licenseType, int version, DecryptedLicenseData decryptedLicenseData) {
        byte[] encryptedContent = encryptLicenseContent(decryptedLicenseData.formatLicenseData());
        return LicenseKey.createLicenseKey(licenseType, version, encryptedContent);
    }

    static DecryptedLicenseData licenseData(DecodedLicense decodedLicense) throws IOException {
        return decryptLicenseContent(decodedLicense.encryptedContent());
    }


    @Nullable
    public DecryptedLicenseData currentLicense() {
        return currentLicense.get();
    }

    @Override
    protected void doStart() {
        clusterService.addListener(this);
    }

    private LicenseKey getLicenseMetadata(ClusterState clusterState) {
        return clusterState.getMetaData().custom(LicenseKey.WRITEABLE_TYPE);
    }

    private void registerSelfGeneratedLicense(ClusterState clusterState) {
        DiscoveryNodes nodes = clusterState.getNodes();
        if (nodes != null) {
            if (nodes.isLocalNodeElectedMaster()) {
                DecryptedLicenseData licenseData = new DecryptedLicenseData(Long.MAX_VALUE, clusterState.getClusterName().value());
                LicenseKey licenseKey = createLicenseKey(
                    LicenseType.SELF_GENERATED,
                    LicenseKey.VERSION,
                    licenseData
                );
                // todo: check if this is needed or
                // todo: if we need changes in the registerLicense for `set license` statement
                // todo: i.e. is the currentLicense properly set after metadata registration?
                currentLicense.set(licenseData);
                registerLicense(licenseKey,
                    new ActionListener<SetLicenseResponse>() {

                        @Override
                        public void onResponse(SetLicenseResponse setLicenseResponse) {
                        }

                        @Override
                        public void onFailure(Exception e) {
                            logger.error("Unable to register license", e);
                        }
                    });
            }
        }
    }

    @VisibleForTesting
    static boolean verifyLicense(LicenseKey licenseKey) {
        try {
            DecodedLicense decodedLicense = decodeLicense(licenseKey);
            DecryptedLicenseData licenseData = licenseData(decodedLicense);

            if (licenseData.isExpired()) {
                return false;
            }
            if (decodedLicense.type() == LicenseType.ENTERPRISE) {
                return verifySignature(licenseData);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @VisibleForTesting
    static DecryptedLicenseData decryptLicenseContent(byte[] encryptedContent) throws IOException {
        byte[] decryptedContent = Cryptos.decrypt(encryptedContent);
        return DecryptedLicenseData.fromFormattedLicenseData(decryptedContent);
    }


    @VisibleForTesting
    static byte[] encryptLicenseContent(byte[] content) {
        return Cryptos.encrypt(content);
    }

    private static boolean verifySignature(final DecryptedLicenseData license) {
        final byte[] publicKeyBytes;
        try (InputStream is = LicenseService.class.getResourceAsStream("/public.key")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Streams.copy(is, out);
            publicKeyBytes = out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return verifySignature(license, publicKeyBytes);
    }

    private static boolean verifySignature(final DecryptedLicenseData licenseData, byte[] publicKeyBytes) {
        try {
            // init signature with public key
            Signature rsa = Cryptos.rsaSignatureInstance();
            rsa.initVerify(Cryptos.getPublicKey(publicKeyBytes));
            // feed signature data
            byte[] data = Cryptos.sha256Digest(licenseData.formatLicenseDataForSignature());
            rsa.update(data);
            // verify signature
            byte[] signatureToVerify = Base64.getDecoder().decode(licenseData.signature());
            return rsa.verify(signatureToVerify);
        } catch (SignatureException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    @VisibleForTesting
    static DecryptedLicenseData signLicense(DecryptedLicenseData licenseData, byte[] encryptedPrivateKeyBytes) {
        try {
            // init signature with private key
            Signature rsa = Cryptos.rsaSignatureInstance();
            rsa.initSign(Cryptos.getPrivateKey(encryptedPrivateKeyBytes));
            // feed signature data
            byte[] data = Cryptos.sha256Digest(licenseData.formatLicenseDataForSignature());
            rsa.update(data);
            // sign
            byte[] signedContent = rsa.sign();
            licenseData.setSignature(Base64.getEncoder().encodeToString(signedContent));
            return licenseData;
        } catch (SignatureException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected void doStop() {
    }

    @Override
    protected void doClose() {
        clusterService.removeListener(this);
        currentLicense.set(null);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        ClusterState currentState = event.state();

        if (currentState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            return;
        }

        LicenseKey previousLicenseKey = getLicenseMetadata(event.previousState());
        LicenseKey newLicenseKey = getLicenseMetadata(currentState);

        if (previousLicenseKey == null && newLicenseKey == null) {
            registerSelfGeneratedLicense(currentState);
            return;
        }

        if (newLicenseKey != null && !newLicenseKey.equals(previousLicenseKey)) {
            try {
                this.currentLicense.set(licenseData(decodeLicense(newLicenseKey)));
            } catch (IOException e) {
                logger.error("Received invalid license. Unable to read the license data.");
                throw new InvalidLicenseException("Invalid license present in cluster");
            }
        }
    }

    @Override
    public void onSuccess(ClusterState build) {
    }

    @Override
    public void onFailure(String s) {
    }
}
