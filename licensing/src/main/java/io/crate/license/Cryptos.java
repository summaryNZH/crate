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

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

final class Cryptos {

    private static final String PASSPHRASE = "crate_passphrase";
    private static final String CIPHER_ALGORITHM = "AES";
    private static final Key AES_KEY_SPEC = new SecretKeySpec(PASSPHRASE.getBytes(StandardCharsets.UTF_8), "AES");
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String SIGN_ALGORITHM = "SHA512withRSA";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private Cryptos() {
    }

    /**
     * Get a {@link PublicKey} from the key content
     */
    static PublicKey getPublicKey(byte[] publicKeyBytes) {
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(publicKeySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Get a {@link PrivateKey} from the encrypted key content
     */
    static PrivateKey getPrivateKey(byte[] privateKeyBytes) {
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        try {
            return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(privateKeySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }


    static Signature rsaSignatureInstance() {
        try {
            return Signature.getInstance(SIGN_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void generateAndWriteAsymmetricKeysToFiles(final Path publicKeyPath,
                                                      final Path privateKeyPath) {
        try {
            SecureRandom random = new SecureRandom();
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyGen.initialize(KEY_SIZE, random);
            KeyPair keyPair = keyGen.generateKeyPair();

            Files.write(publicKeyPath, getPublicKeyBytes(keyPair.getPublic()));
            Files.write(privateKeyPath, getPrivateKeyBytes(keyPair.getPrivate()));
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Gets the public key content from {@link PublicKey}
     */
    private static byte[] getPublicKeyBytes(PublicKey publicKey) {
        X509EncodedKeySpec encodedKeySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        return encodedKeySpec.getEncoded();
    }

    /**
     *  Gets the private key content from {@link PrivateKey}
     */
    private static byte[] getPrivateKeyBytes(PrivateKey privateKey) {
        PKCS8EncodedKeySpec encodedKeySpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        return encodedKeySpec.getEncoded();
    }

    static byte[] encrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, AES_KEY_SPEC);
            return cipher.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException | BadPaddingException
            | IllegalBlockSizeException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] decrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, AES_KEY_SPEC);
            return cipher.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException | BadPaddingException
            | IllegalBlockSizeException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256Digest(byte[] inputBytes) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance(DIGEST_ALGORITHM);
            sha256.update(inputBytes);
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
