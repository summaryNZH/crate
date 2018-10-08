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

package io.crate.lucene;

import org.apache.lucene.search.Query;
import org.junit.Test;

import static org.hamcrest.core.Is.is;

public class ArrayLengthQueryBuilderTest extends LuceneQueryBuilderTest {

    @Test
    public void testArrayLengthGtColumnIsNotOptimized() {
        Query query = convert("array_length(y_array, 1) > x");
        assertThat(query.toString(), is("Ref{doc.users.x, integer} < array_length(Ref{doc.users.y_array, long_array}, 1)"));
    }

    @Test
    public void testArrayLengthGt0UsesIndex() {
        Query query = convert("array_length(y_array, 1) > 0");
        assertThat(
            query.toString(),
            is("DocValuesFieldExistsQuery [field=y_array]"));
    }

    @Test
    public void testArrayLengthGtNULLDoesNotMatch() {
        Query query = convert("array_length(y_array, 1) > NULL");
        assertThat(query.toString(), is("MatchNoDocsQuery(\"WHERE null -> no match\")"));
    }

    @Test
    public void test0LteArrayLengthUsesIndex() {
        Query query = convert("0 <= array_length(y_array, 1)");
        assertThat(query.toString(), is("foo"));
    }

    @Test
    public void testArrayLengthGte1UsesIndex() {
        Query query = convert("array_length(y_array, 1) >= 1");
        assertThat(
            query.toString(),
            is("+DocValuesFieldExistsQuery [field=y_array] #array_length(Ref{doc.users.y_array, long_array}, 1) >= 1")
        );
    }

    @Test
    public void testArrayLengthGt1UsesIndex() {
        Query query = convert("array_length(y_array, 1) > 1");
        assertThat(
            query.toString(),
            is("+DocValuesFieldExistsQuery [field=y_array] #array_length(Ref{doc.users.y_array, long_array}, 1) > 1")
        );
    }

    @Test
    public void testArrayLengthLt1UsesIndex() {
        Query query = convert("array_length(y_array, 1) < 1");
        assertThat(
            query.toString(),
            is("foo"));
    }

    @Test
    public void testArrayLengthLte0UsesIndex() {
        Query query = convert("array_length(y_array, 1) <= 0");
        assertThat(
            query.toString(),
            is("foo")
        );
    }
}
