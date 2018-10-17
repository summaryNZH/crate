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

package io.crate.expression.reference.sys.check.cluster;

import io.crate.expression.reference.sys.check.SysCheck;
import io.crate.license.DecryptedLicenseData;
import io.crate.license.LicenseExpiryNotification;
import io.crate.license.LicenseService;
import io.crate.test.integration.CrateUnitTest;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.elasticsearch.mock.orig.Mockito.when;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

public class LicenseExpiryCheckTest extends CrateUnitTest {

    private LicenseService licenseService;
    private LicenseExpiryCheck expirationWarningCheck;

    @Before
    public void setupLicenseCheck() {
        licenseService = mock(LicenseService.class);
        expirationWarningCheck = new LicenseExpiryCheck(licenseService);
    }

    @Test
    public void testSysCheckMetadata() {
        assertThat(expirationWarningCheck.id(), is(5));
    }

    @Test
    public void testValidLicense() {
        DecryptedLicenseData thirtyDaysLicense = new DecryptedLicenseData(
            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30), "test");
        when(licenseService.currentLicense()).thenReturn(thirtyDaysLicense);
        when(licenseService.getLicenseExpiryNotification(thirtyDaysLicense)).thenReturn(null);
        assertThat(expirationWarningCheck.validate(), is(true));
    }

    @Test
    public void testLessThanFifteenDaysToExpiryTriggersMediumCheck() {
        DecryptedLicenseData sevenDaysLicense = new DecryptedLicenseData(
            System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7), "test");

        when(licenseService.currentLicense()).thenReturn(sevenDaysLicense);
        when(licenseService.getLicenseExpiryNotification(sevenDaysLicense)).thenReturn(LicenseExpiryNotification.MODERATE);
        assertThat(expirationWarningCheck.validate(), is(false));
        assertThat(expirationWarningCheck.severity(), is(SysCheck.Severity.MEDIUM));
    }

    @Test
    public void testLessThanOneDayToExpiryTriggersSevereCheck() {
        DecryptedLicenseData sevenDaysLicense = new DecryptedLicenseData(
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15), "test");

        when(licenseService.currentLicense()).thenReturn(sevenDaysLicense);
        when(licenseService.getLicenseExpiryNotification(sevenDaysLicense)).thenReturn(LicenseExpiryNotification.SEVERE);
        assertThat(expirationWarningCheck.validate(), is(false));
        assertThat(expirationWarningCheck.severity(), is(SysCheck.Severity.HIGH));
    }
}
