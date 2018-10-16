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
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import org.elasticsearch.common.settings.Settings;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static io.crate.license.LicenseKey.LicenseType;

import java.io.IOException;

import static io.crate.license.LicenseKey.VERSION;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;

public class LicenseServiceTest extends CrateDummyClusterServiceUnitTest {

    private LicenseService licenseService;

    @Before
    public void setupLicenseService() {
        licenseService = new LicenseService(Settings.EMPTY, mock(TransportSetLicenseAction.class), clusterService);
    }

    @Test
    public void testVerifyValidSelfGeneratedLicense() {
        LicenseKey licenseKey = licenseService.createLicenseKey(LicenseType.SELF_GENERATED, VERSION,
            new DecryptedLicenseData(Long.MAX_VALUE, "test"));
        assertThat(licenseService.verifyLicense(licenseKey), is(true));
    }

    @Test
    public void testVerifyExpiredSelfGeneratedLicense() {
        LicenseKey expiredLicense = licenseService.createLicenseKey(LicenseType.SELF_GENERATED, VERSION,
            new DecryptedLicenseData(System.currentTimeMillis() - 5 * 60 * 60 * 1000, "test"));

        assertThat(licenseService.verifyLicense(expiredLicense), is(false));
    }

    @Test
    public void testInvalidLicenseTypeThrowsException() {
        expectedException.expect(InvalidLicenseException.class);
        expectedException.expectMessage("Invalid License Type");

        licenseService.createLicenseKey(LicenseType.of(-2), VERSION, new DecryptedLicenseData(Long.MAX_VALUE, "test"));
    }

    @Test
    public void testGetLicenseData() throws IOException {
        LicenseKey licenseKey = licenseService.createLicenseKey(LicenseType.SELF_GENERATED, VERSION,
            new DecryptedLicenseData(Long.MAX_VALUE, "test"));
        DecryptedLicenseData licenseData = licenseService.licenseData(LicenseKey.decodeLicense(licenseKey));

        assertThat(licenseData.expirationDateInMs(), is(Long.MAX_VALUE));
        assertThat(licenseData.issuedTo(), is("test"));
    }

    @Test
    public void testGetLicenseDataOnlySupportsSelfGeneratedLicense() throws IOException {
        //DecodedLicense decodedLicense = new DecodedLicense(-2, VERSION, new byte[]{1,2,3,4});
        DecodedLicense decodedLicense = new DecodedLicense(LicenseType.ENTERPRISE, VERSION, new byte[]{1,2,3,4});

        expectedException.expect(UnsupportedOperationException.class);
        expectedException.expectMessage("Only self generated licenses are supported");
        licenseService.licenseData(decodedLicense);
    }

    @Test
    public void testOnlySelfGeneratedLicenseIsSupported() {
        expectedException.expect(UnsupportedOperationException.class);
        expectedException.expectMessage("Only self generated licenses are supported");

        licenseService.createLicenseKey(LicenseType.ENTERPRISE, VERSION, new DecryptedLicenseData(Long.MAX_VALUE, "test"));
        //licenseService.createLicenseKey(-2, VERSION, new DecryptedLicenseData(Long.MAX_VALUE, "test"));
    }


    @Test
    public void testGenerateSelfGeneratedKey() {
        byte[] encryptedContent = LicenseService.encryptLicenseContent(new DecryptedLicenseData(Long.MAX_VALUE, "test").formatLicenseData());
        assertThat(encryptedContent, Matchers.is(notNullValue()));
    }

    @Test
    public void testDecryptSelfGeneratedLicense() throws IOException {
        byte[] encryptedContent = LicenseService.encryptLicenseContent(new DecryptedLicenseData(Long.MAX_VALUE, "test").formatLicenseData());
        DecryptedLicenseData licenseInfo = LicenseService.decryptLicenseContent(encryptedContent);

        assertThat(licenseInfo.expirationDateInMs(), Matchers.is(Long.MAX_VALUE));
        assertThat(licenseInfo.issuedTo(), Matchers.is("test"));
    }


}
