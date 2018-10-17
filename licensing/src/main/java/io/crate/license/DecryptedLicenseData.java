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

import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class DecryptedLicenseData {

    public static final String EXPIRATION_DATE_IN_MS = "expirationDateInMs";
    public static final String ISSUED_TO = "issuedTo";
    private static final String SIGNATURE = "signature";

    private final long expirationDateInMs;
    private final String issuedTo;
    @Nullable
    private String signature;

    DecryptedLicenseData(long expirationDateInMs, String issuedTo) {
        this.expirationDateInMs = expirationDateInMs;
        this.issuedTo = issuedTo;
    }

    DecryptedLicenseData(long expirationDateInMs, String issuedTo, @Nullable String signature) {
        this.expirationDateInMs = expirationDateInMs;
        this.issuedTo = issuedTo;
        this.signature = signature;
    }

    public long expirationDateInMs() {
        return expirationDateInMs;
    }

    public String issuedTo() {
        return issuedTo;
    }

    String signature() {
        return signature != null ? signature : "";
    }

    void setSignature(String signature) {
        this.signature = signature;
    }

    boolean isExpired() {
        return expirationDateInMs < System.currentTimeMillis();
    }

    /*
     * Creates the json representation of the license information with the following structure:
     *
     * <pre>
     *      {
     *          "expirationDateInMs": "XXX",
     *          "issuedTo": "YYY",
     *          "signature": "ZZZ"
     *      }
     * </pre>
     *
     * If no signature is set (eg. for Self Generated Licenses), the signature part is omitted e.g.
     * <pre>
     *      {
     *          "expirationDateInMs": "XXX",
     *          "issuedTo": "YYY",
     *      }
     * </pre>
     */
    byte[] formatLicenseData() {
        // by default include signature if already set
        return formatLicenseData(signature != null);
    }

    /*
     * Creates the json representation of the license information for signature generation.
     * This is always have the following structure:
     *
     * <pre>
     *      {
     *          "expirationDateInMs": "XXX",
     *          "issuedTo": "YYY",
     *      }
     * </pre>
     */
    byte[] formatLicenseDataForSignature() {
        // never include signature
        return formatLicenseData(false);
    }

    private byte[] formatLicenseData(boolean includeSignature) {
        try {
            XContentBuilder contentBuilder = XContentFactory.contentBuilder(XContentType.JSON);
            contentBuilder.startObject()
                .field(EXPIRATION_DATE_IN_MS, expirationDateInMs)
                .field(ISSUED_TO, issuedTo);
            if (includeSignature) {
                contentBuilder.field(SIGNATURE, signature());
            }
            contentBuilder.endObject();
            return contentBuilder.string().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static DecryptedLicenseData fromFormattedLicenseData(byte[] licenseData) throws IOException {
        try (XContentParser parser = XContentFactory.xContent(XContentType.JSON)
            .createParser(NamedXContentRegistry.EMPTY, licenseData)) {
            XContentParser.Token token;
            long expirationDate = 0;
            String issuedTo = null;
            String signature = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    String currentFieldName = parser.currentName();
                    parser.nextToken();
                    if (currentFieldName.equals(EXPIRATION_DATE_IN_MS)) {
                        expirationDate = parser.longValue();
                    } else if (currentFieldName.equals(ISSUED_TO)) {
                        issuedTo = parser.text();
                    } else if (currentFieldName.equals(SIGNATURE)) {
                        signature = parser.text();
                    }
                }
            }
            return new DecryptedLicenseData(expirationDate, issuedTo, signature);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DecryptedLicenseData that = (DecryptedLicenseData) o;
        return expirationDateInMs == that.expirationDateInMs &&
               Objects.equals(issuedTo, that.issuedTo) &&
               signature().equals(that.signature());
    }

    @Override
    public int hashCode() {
        return Objects.hash(expirationDateInMs, issuedTo, signature());
    }
}
