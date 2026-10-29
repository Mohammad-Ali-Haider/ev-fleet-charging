package com.moveinsync.evfleet.planner;

import java.util.Arrays;

/**
 * Minimum-cost assignment of rows to distinct columns (Hungarian algorithm with potentials).
 *
 * <p>
 * Time O(n^2 m) and space O(n m) for n rows and m >= n columns. Callers encode "not allowed" as a large finite cost so that minimising the total first
 * minimises the number of disallowed pairs.
 */
final class AssignmentSolver {

    /** No instances; only the static solver. */
    private AssignmentSolver() {
    }

    /**
     * @param cost
     *            cost[row][column], with rows <= columns
     * @return the column assigned to each row
     */
    static int[] solve(long[][] cost) {
        int rows = cost.length;
        int columns = rows == 0 ? 0 : cost[0].length;

        if (rows > columns) {
            throw new IllegalArgumentException("more rows than columns");
        }

        // 1-based arrays as in the classical formulation; index 0 is the virtual start column.
        long[] rowPotential = new long[rows + 1];
        long[] columnPotential = new long[columns + 1];
        int[] rowOfColumn = new int[columns + 1];
        int[] previousColumn = new int[columns + 1];
        long[] slack = new long[columns + 1];
        boolean[] used = new boolean[columns + 1];

        for (int row = 1; row <= rows; row++) {
            rowOfColumn[0] = row;
            int column = 0;
            Arrays.fill(slack, Long.MAX_VALUE);
            Arrays.fill(used, false);

            do {
                used[column] = true;
                int currentRow = rowOfColumn[column];
                long delta = Long.MAX_VALUE;
                int nextColumn = 0;

                for (int candidate = 1; candidate <= columns; candidate++) {
                    if (used[candidate]) {
                        continue;
                    }

                    long reduced = cost[currentRow - 1][candidate - 1] - rowPotential[currentRow] - columnPotential[candidate];

                    if (reduced < slack[candidate]) {
                        slack[candidate] = reduced;
                        previousColumn[candidate] = column;
                    }
                    if (slack[candidate] < delta) {
                        delta = slack[candidate];
                        nextColumn = candidate;
                    }
                }
                for (int candidate = 0; candidate <= columns; candidate++) {
                    if (used[candidate]) {
                        rowPotential[rowOfColumn[candidate]] += delta;
                        columnPotential[candidate] -= delta;
                    } else {
                        slack[candidate] -= delta;
                    }
                }
                column = nextColumn;
            } while (rowOfColumn[column] != 0);

            do {
                int previous = previousColumn[column];
                rowOfColumn[column] = rowOfColumn[previous];
                column = previous;
            } while (column != 0);
        }

        int[] assignment = new int[rows];

        for (int column = 1; column <= columns; column++) {
            if (rowOfColumn[column] != 0) {
                assignment[rowOfColumn[column] - 1] = column - 1;
            }
        }

        return assignment;
    }
}
