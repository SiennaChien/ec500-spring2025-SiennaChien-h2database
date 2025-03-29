package org.h2.command.query;

import org.h2.engine.SessionLocal;
import org.h2.table.TableFilter;
import org.h2.table.Table;
import org.h2.expression.Expression;


import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
/**
 * Determines the best join order by following rules rather than considering every possible permutation.
 */
public class RuleBasedJoinOrderPicker {
    final SessionLocal session;
    final TableFilter[] filters;

    public RuleBasedJoinOrderPicker(SessionLocal session, TableFilter[] filters) {
        this.session = session;
        this.filters = filters;
    }

    public TableFilter[] bestOrder() {
        // rule 1
        TableFilter[] nonCartesianTables = Arrays.stream(filters)
                .filter(tf -> tf.getFullCondition() != null)
                .toArray(TableFilter[]::new);

        // rule 2
        Arrays.sort(nonCartesianTables, (tf1, tf2) -> {
            long rowCount1 = tf1.getTable().getRowCountApproximation(session);
            long rowCount2 = tf2.getTable().getRowCountApproximation(session);
            return Long.compare(rowCount1, rowCount2);
        });

        return nonCartesianTables;
    }

//    public TableFilter[] bestOrder() {
//        List<TableFilter> sortedFilters = new ArrayList<>();
//        Set<Table> joinedTables = new HashSet<>(); // Track tables that have been joined
//
//        // rule 1, no cartesian products
//        // rule 2, choose table with the lowest number of rows out of the remaining ones
//        while (joinedTables.size() < filters.length) {
//            TableFilter nextTableFilter = null;
//            long minRowCount = Long.MAX_VALUE;
//
//            for (TableFilter tableFilter : filters) {
//                Table table = tableFilter.getTable();
//
//                //if table is already in the lsit
//                if (joinedTables.contains(table)) {
//                    continue;
//                }
//
//                //ignore tables if they are a cartesian join
//                Expression expressionObj = tableFilter.getFullCondition();
//
//                if (tableFilter.getFullCondition() != null) {
//                    long rowCount = table.getRowCountApproximation(session);
//
//                    //choose the shortest table
//                    if (rowCount < minRowCount) {
//                        nextTableFilter = tableFilter;
//                        minRowCount = rowCount;
//                    }
//                }
//            }
//            // no table found
//            if (nextTableFilter != null) {
//                sortedFilters.add(nextTableFilter);
//                joinedTables.add(nextTableFilter.getTable());
//            } else {
//                break; //can't find a join
//            }
//
//        }
//
//        return sortedFilters.toArray(new TableFilter[0]);
//    }

}