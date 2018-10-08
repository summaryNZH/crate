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

import io.crate.data.Input;
import io.crate.expression.operator.EqOperator;
import io.crate.expression.operator.GtOperator;
import io.crate.expression.operator.GteOperator;
import io.crate.expression.operator.LtOperator;
import io.crate.expression.operator.LteOperator;
import io.crate.expression.operator.Operators;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.Reference;
import io.crate.types.CollectionType;
import io.crate.types.DataType;
import io.crate.types.IntegerType;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.mapper.MappedFieldType;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import static io.crate.lucene.LuceneQueryBuilder.genericFunctionFilter;

public final class ArrayLengthQuery implements InnerFunctionToQuery {

    /**
     * <pre>
     * {@code
     *  array_length(arr, dim) > 0
     *      |                  |
     *    inner              parent
     * }
     * </pre>
     */
    @Nullable
    @Override
    public Query apply(Function parent, Function arrayLength, LuceneQueryBuilder.Context context) {
        String parentName = parent.info().ident().name();
        if (!Operators.COMPARISON_OPERATORS.contains(parentName)) {
            return null;
        }
        List<Symbol> parentArgs = parent.arguments();
        Symbol cmpSymbol = parentArgs.get(1);
        if (!(cmpSymbol instanceof Input)) {
            return null;
        }
        Number cmpNumber = (Number) ((Input) cmpSymbol).value();
        if (cmpNumber == null) {
            // TODO: this case is never reached due to early normalization + no-match detection
            return Queries.newMatchNoDocsQuery(parentName + " on NULL doesn't match");
        }
        List<Symbol> arrayLengthArgs = arrayLength.arguments();
        Symbol arraySymbol = arrayLengthArgs.get(0);
        if (!(arraySymbol instanceof Reference)) {
            return null;
        }
        Reference arrayRef = (Reference) arraySymbol;
        int cmpVal = cmpNumber.intValue();

        // TODO:
        // Values are stored set, so the docValueCounts represent the number of unique values
        // [a, a, a]
        //      -> docValueCount 1
        //      -> arrayLength   3
        //  array_length(arr, 1) >  0       docValueCount >  0  TODO: OR exists?
        //  array_length(arr, 1) >= 0       exists
        //  array_length(arr, 1) >  1       genericFunctionFilter
        //  array_length(arr, 1) >= 1       docValueCount >= 1
        //  array_length(arr, 1) >  20      genericFunctionFilter
        //  array_length(arr, 1) >= 20      genericFunctionFilter
        //  array_length(arr, 1) <  0       noMatch
        //  array_length(arr, 1) <= 0       docValueCount == 0
        //  array_length(arr, 1) <  1       docValueCount == 0
        //  array_length(arr, 1) <= 1       genericFunctionFilter
        //  array_length(arr, 1) <  20      genericFunctionFilter
        //  array_length(arr, 1) <= 20      genericFunctionFilter

        // genericFunctionFilter --> always run bool of both NumTermsPerDoc & genericFunctionFilter?

        IntPredicate valueCountIsMatch = predicateForFunction(parentName, cmpVal);
        if (cmpVal == 0 && parentName.equals(GteOperator.NAME)) {
            MappedFieldType fieldType = context.getFieldTypeOrNull(arrayRef.column().fqn());
            if (fieldType == null) {
                return Queries.newMatchNoDocsQuery("MappedFieldType missing");
            }
            return fieldType.existsQuery(context.queryShardContext());
        } else if (cmpVal == 0 && parentName.equals(GtOperator.NAME)) {
            return new NumTermsPerDocQuery(
                leafReaderContext -> getNumTermsPerDocFunction(leafReaderContext, arrayRef),
                valueCountIsMatch
            );
        }
        if (true) {
            return genericFunctionFilter(parent, context);
        }
        return new BooleanQuery.Builder()
            .add(
                new NumTermsPerDocQuery(
                    leafReaderContext -> getNumTermsPerDocFunction(leafReaderContext, arrayRef),
                    valueCountIsMatch
                ),
                BooleanClause.Occur.MUST
            )
            .add(genericFunctionFilter(parent, context), BooleanClause.Occur.FILTER)
            .build();
    }

    private static IntPredicate predicateForFunction(String cmpFuncName, int cmpValue) {
        switch (cmpFuncName) {
            case LtOperator.NAME:
                return x -> x < cmpValue;

            case LteOperator.NAME:
                return x -> x <= cmpValue;

            case GtOperator.NAME:
                return x -> x > cmpValue;

            case GteOperator.NAME:
                return x -> x >= cmpValue;

            case EqOperator.NAME:
                return x -> x == cmpValue;

            default:
                throw new IllegalArgumentException("Unknown comparison function: " + cmpFuncName);
        }
    }

    private static IntUnaryOperator getNumTermsPerDocFunction(LeafReaderContext context, Reference column) {
        DataType elementType = CollectionType.unnest(column.valueType());
        // TODO: extend
        switch (elementType.id()) {
            case IntegerType.ID:
                final SortedNumericDocValues sortedNumeric;
                try {
                    sortedNumeric = DocValues.getSortedNumeric(context.reader(), column.column().fqn());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return doc -> {
                    try {
                        return sortedNumeric.advanceExact(doc) ? sortedNumeric.docValueCount() : 0;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
            default:
                throw new UnsupportedOperationException("NYI");
        }
    }

    static class NumTermsPerDocQuery extends Query {

        private final java.util.function.Function<LeafReaderContext, IntUnaryOperator> numTermsPerDocFactory;
        private final IntPredicate matches;

        public NumTermsPerDocQuery(java.util.function.Function<LeafReaderContext, IntUnaryOperator> numTermsPerDocFactory,
                                   IntPredicate matches) {
            this.numTermsPerDocFactory = numTermsPerDocFactory;
            this.matches = matches;
        }

        @Override
        public Weight createWeight(IndexSearcher searcher, boolean needsScores, float boost) {
            return new ConstantScoreWeight(this, boost) {
                @Override
                public Scorer scorer(LeafReaderContext context) {
                    return new ConstantScoreScorer(
                        this,
                        0f,
                        new NumTermsPerDocTwoPhaseIterator(context.reader(), numTermsPerDocFactory.apply(context), matches));
                }
            };
        }

        @Override
        public boolean equals(Object obj) {
            return false;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString(String field) {
            return field;
        }
    }

    private static class NumTermsPerDocTwoPhaseIterator extends TwoPhaseIterator {

        private final IntUnaryOperator numTermsOfDoc;
        private final IntPredicate matches;

        NumTermsPerDocTwoPhaseIterator(LeafReader reader,
                                       IntUnaryOperator numTermsOfDoc,
                                       IntPredicate matches) {
            super(DocIdSetIterator.all(reader.maxDoc()));
            this.numTermsOfDoc = numTermsOfDoc;
            this.matches = matches;
        }

        @Override
        public boolean matches() {
            int doc = approximation.docID();
            return matches.test(numTermsOfDoc.applyAsInt(doc));
        }

        @Override
        public float matchCost() {
            return 2;
        }
    }
}
