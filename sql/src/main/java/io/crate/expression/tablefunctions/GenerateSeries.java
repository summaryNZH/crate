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

package io.crate.expression.tablefunctions;

import io.crate.action.sql.SessionContext;
import io.crate.analyze.WhereClause;
import io.crate.data.Bucket;
import io.crate.data.Input;
import io.crate.data.Row;
import io.crate.data.RowN;
import io.crate.metadata.BaseFunctionResolver;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.FunctionIdent;
import io.crate.metadata.FunctionImplementation;
import io.crate.metadata.FunctionInfo;
import io.crate.metadata.Reference;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.RelationName;
import io.crate.metadata.Routing;
import io.crate.metadata.RoutingProvider;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.functions.params.FuncParams;
import io.crate.metadata.functions.params.Param;
import io.crate.metadata.table.StaticTableInfo;
import io.crate.metadata.table.TableInfo;
import io.crate.metadata.tablefunctions.TableFunctionImplementation;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.elasticsearch.cluster.ClusterState;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * <pre>
 * {@code
 *      generate_series :: a -> a -> table a
 *      generate_series(start, stop)
 *
 *      generate_series :: a -> a -> a -> table a
 *      generate_series(start, stop, step)
 *
 *      where: a = Integer or Long
 * }
 * </pre>
 */
public final class GenerateSeries extends TableFunctionImplementation<Long> {

    public static final String NAME = "generate_series";
    private static final RelationName RELATION_NAME = new RelationName("", NAME);
    private final FunctionInfo info;

    public static void register(TableFunctionModule module) {
        FuncParams.Builder paramsBuilder = FuncParams
            .builder(Param.LONG, Param.LONG)
            .withVarArgs(Param.LONG)
            .limitVarArgOccurrences(1);
        module.register(NAME, new BaseFunctionResolver(paramsBuilder.build()) {
            @Override
            public FunctionImplementation getForTypes(List<DataType> types) throws IllegalArgumentException {
                return new GenerateSeries(types);
            }
        });
    }

    private GenerateSeries(List<DataType> dataTypes) {
        FunctionIdent functionIdent = new FunctionIdent(NAME, dataTypes);
        this.info = new FunctionInfo(functionIdent, DataTypes.LONG, FunctionInfo.Type.TABLE);
    }

    @Override
    public TableInfo createTableInfo() {
        ColumnIdent col1 = new ColumnIdent("col1");
        Reference reference = new Reference(new ReferenceIdent(RELATION_NAME, col1), RowGranularity.DOC, info.returnType());
        Map<ColumnIdent, Reference> referenceByColumn = Collections.singletonMap(col1, reference);
        return new StaticTableInfo(RELATION_NAME, referenceByColumn, Collections.singletonList(reference), Collections.emptyList()) {
            @Override
            public Routing getRouting(ClusterState state,
                                      RoutingProvider routingProvider,
                                      WhereClause whereClause,
                                      RoutingProvider.ShardSelection shardSelection,
                                      SessionContext sessionContext) {
                return Routing.forTableOnSingleNode(RELATION_NAME, state.getNodes().getLocalNodeId());
            }

            @Override
            public RowGranularity rowGranularity() {
                return RowGranularity.DOC;
            }
        };
    }

    @Override
    public Bucket evaluate(Input<Long>... args) {
        Long startInclusive = args[0].value();
        Long stopInclusive = args[1].value();
        Long step = args.length == 3 ? args[2].value() : 1L;
        if (startInclusive == null || stopInclusive == null || step == null) {
            return Bucket.EMPTY;
        }
        long diff = (stopInclusive + step) - startInclusive;
        final int numRows = Math.max(0, (int) (diff / step));
        final boolean reverseCompare = startInclusive > stopInclusive && numRows > 0;
        final Object[] cells = new Object[1];
        cells[0] = startInclusive;
        final RowN rowN = new RowN(cells);
        return new Bucket() {
            @Override
            public int size() {
                return numRows;
            }

            @Override
            @Nonnull
            public Iterator<Row> iterator() {
                return new Iterator<Row>() {
                    boolean doStep = false;
                    long val = startInclusive;

                    @Override
                    public boolean hasNext() {
                        if (doStep) {
                            val = val + step;
                            doStep = false;
                        }
                        if (reverseCompare) {
                            return val >= stopInclusive;
                        } else {
                            return val <= stopInclusive;
                        }
                    }

                    @Override
                    public Row next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException("Iterator has no more elements");
                        }
                        doStep = true;
                        cells[0] = val;
                        return rowN;
                    }
                };
            }
        };
    }

    @Override
    public FunctionInfo info() {
        return info;
    }
}
