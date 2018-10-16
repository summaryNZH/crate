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
import java.security.NoSuchAlgorithmException;
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
    LicenseKey createLicenseKey(LicenseType licenseType, int version, DecryptedLicenseData decryptedLicenseData) {
        byte[] encryptedContent = encryptLicenseContent(decryptedLicenseData.formatLicenseData());
        return LicenseKey.createLicenseKey(licenseType, version, encryptedContent);
    }

    boolean verifyLicense(LicenseKey licenseKey) {
        try {
            DecodedLicense decodedLicense = decodeLicense(licenseKey);
            DecryptedLicenseData licenseData = licenseData(decodedLicense);
            return System.currentTimeMillis() < licenseData.expirationDateInMs();
        } catch (IOException e) {
            return false;
        }
    }

    DecryptedLicenseData licenseData(DecodedLicense decodedLicense) throws IOException {
        if (decodedLicense.type() == LicenseKey.SELF_GENERATED) {
            return decryptLicenseContent(decodedLicense.encryptedContent());
        } else {
            throw new UnsupportedOperationException("Only self generated licenses are supported.");
        }
    }

    /*
        boolean verifyLicense(LicenseKey licenseKey) {
        try {
            DecodedLicense decodedLicense = decodeLicense(licenseKey);
            if (decodedLicense.type() == LicenseType.SELF_GENERATED) {
                DecryptedLicenseData licenseInfo = decryptLicenseContent(decodedLicense.encryptedContent());
                return System.currentTimeMillis() < licenseInfo.expirationDateInMs();
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
     */

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
                currentLicense.set(licenseKey);
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

    //@VisibleForTesting
    boolean verifyLicense(LicenseKey licenseKey) {
        try {
            DecodedLicense decodedLicense = decodeLicense(licenseKey);
            DecryptedLicenseData licenseInfo = decryptLicenseContent(decodedLicense.encryptedContent());

            if (licenseInfo.isExpired()) {
                //LOGGER.warn("Unable to validate [{}] as it is expired", license);
                return false;
            }
            if (decodedLicense.type() == LicenseType.ENTERPRISE) {
                return verifySignature(licenseInfo);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    //@VisibleForTesting
    static DecryptedLicenseData decryptLicenseContent(byte[] encryptedContent) throws IOException {
        byte[] decryptedContent = Cryptos.decrypt(encryptedContent);
        return DecryptedLicenseData.fromFormattedLicenseData(decryptedContent);
    }


    //@VisibleForTesting
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
            Signature rsa = Signature.getInstance("SHA512withRSA");
            rsa.initVerify(Cryptos.readPublicKey(publicKeyBytes));
            // feed signature data
            byte[] data = Cryptos.digest(licenseData.formatLicenseDataForSignature());
            rsa.update(data);
            // verify signature
            byte[] signatureToVerify = Base64.getDecoder().decode(licenseData.signature());
            return rsa.verify(signatureToVerify);
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] createSignature(DecryptedLicenseData licenseData, byte[] privateKeyBytes) {
        try {
            // init signature with public key
            Signature rsa = Signature.getInstance("SHA512withRSA");
            // todo: private Key should be encrypted
            rsa.initSign(Cryptos.readPrivateKey(privateKeyBytes));
            // feed signature data
            byte[] data = Cryptos.digest(licenseData.formatLicenseDataForSignature());
            rsa.update(data);
            // sign
            return rsa.sign();
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
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
