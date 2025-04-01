package org.h2.command.query;

import org.h2.engine.SessionLocal;
import org.h2.table.TableFilter;
import org.h2.table.Table;
import org.h2.expression.Expression;
import java.util.HashMap;
import java.util.Map;


import java.util.*;

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
        // rule 1, no cartesian joins

        //make map of if valid joins between tables
        Expression[] expressions = new Expression[filters.length];
        Map<String, List<String>> expressionMap = new HashMap<>();
        if (filters.length == 1) {
            return filters;
        }

        for (int i = 0; i < filters.length; i++) {
            expressions[i] = filters[i].getFullCondition();
            String tableName = filters[i].getTableAlias();
//            String tableName = filters[i].getTable().getName();
            int subExpressionCount = expressions[i].getSubexpressionCount();
            List<String> validJoins = new ArrayList<>(List.of());


            for (int j = 0; j < subExpressionCount; j++) {
                Expression subExpression = expressions[i].getSubexpression(j);
                int subSubExpressionCount = subExpression.getSubexpressionCount();
                List<String> potentialValidJoins = new ArrayList<>(List.of());

                for (int k = 0; k < subSubExpressionCount; k++) {
                    //access the comparisons inside subexpressions,
                    Expression subSubExpression = subExpression.getSubexpression(k);
                    String joinTableName = subSubExpression.getTableName();
                    System.out.println("Table: " + tableName + " may join with: " + joinTableName);

                    //add valid joins to the list
                    potentialValidJoins.add(joinTableName);

                }
                if (potentialValidJoins.contains(tableName)) {
                    validJoins.addAll(potentialValidJoins);
                }
                System.out.println("Table: " + tableName + " can join with: " + potentialValidJoins);

            }
            System.out.println("Table: " + tableName + " can join with: " + validJoins);
            expressionMap.put(tableName, validJoins);

        }

        for (Map.Entry<String, List<String>> entry : expressionMap.entrySet()) {
            String table = entry.getKey();
            List<String> validJoins = entry.getValue();
            System.out.println("Table: " + table + " can join with: " + validJoins);
        }



        // rule 2, order all table from shortest to longest
        Arrays.sort(filters, (tf1, tf2) -> {
            long rowCount1 = tf1.getTable().getRowCountApproximation(session);
            long rowCount2 = tf2.getTable().getRowCountApproximation(session);
            return Long.compare(rowCount1, rowCount2);
        });

        // try to build a valid join order, try smallest table first
        for (int i = 0; i < filters.length; i++) {
            List<TableFilter> joinOrder = new ArrayList<>();
            Set<String> addedTables = new HashSet<>();

            TableFilter startTable = filters[i];
            joinOrder.add(startTable);
            addedTables.add(startTable.getTableAlias());

            boolean addedNewTable;
            do {
                addedNewTable = false;
                TableFilter lastAdded = joinOrder.get(joinOrder.size() - 1);

                for (TableFilter filter : filters) {
                    if (!addedTables.contains(filter.getTableAlias())) {
                        // check if this table can legally join with the last added table
                        List<String> legalJoins = expressionMap.get(lastAdded.getTableAlias());
                        if (legalJoins != null && legalJoins.contains(filter.getTableAlias())) {
                            joinOrder.add(filter);
                            addedTables.add(filter.getTableAlias());
                            addedNewTable = true;
                            break;
                        }
                    }
                }
            } while (addedNewTable);

            // if a valid join order is found, return it
            if (joinOrder.size() == filters.length) {
                return joinOrder.toArray(new TableFilter[0]);
            }
        }

        // If no valid order is found, return empty list
        return new TableFilter[0];
    }
}