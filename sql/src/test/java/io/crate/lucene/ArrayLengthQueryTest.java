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

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.RAMDirectory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Before;
import org.junit.Test;

public class ArrayLengthQueryTest extends ESSingleNodeTestCase {

    private IndexWriter writer;

    @Before
    public void setUp() throws Exception {
        new LuceneQueryTester.builder()
            .addTable("create table t (xs array(integer))")
            .
        Settings settings = Settings.builder().put("index.fielddata.cache", "none").build();
        IndexService indexService = createIndex("test", settings);
        IndexWriterConfig conf = new IndexWriterConfig(new StandardAnalyzer());
        writer = new IndexWriter(new RAMDirectory(), conf);
    }


    @Test
    public void testGt0MatchesEverythingGt0() {

    }
}
