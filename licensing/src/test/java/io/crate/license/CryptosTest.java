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

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.assertThat;

public class CryptosTest {

    @Test
    public void testEncryption() {
        String data = "data";
        byte[] encrypt = Cryptos.encrypt(data.getBytes());
        assertThat(encrypt, is(notNullValue()));

        byte[] decrypt = Cryptos.decrypt(encrypt);
        assertThat(decrypt, is(notNullValue()));
        assertThat(new String(decrypt), is(data));
    }

    @Test
    public void testCreateAsymmetricKeys() throws IOException {
        final Path publicKeyPath = Paths.get("public.key");
        final Path privateKeyPath = Paths.get("private.key");

        Cryptos.generateAndWriteAsymmetricKeysToFiles(publicKeyPath, privateKeyPath);

        assertThat(Files.exists(publicKeyPath), is(true));
        assertThat(Files.exists(privateKeyPath), is(true));

        Files.deleteIfExists(publicKeyPath);
        Files.deleteIfExists(privateKeyPath);

        assertThat(Files.exists(publicKeyPath), is(false));
        assertThat(Files.exists(privateKeyPath), is(false));
    }
}
