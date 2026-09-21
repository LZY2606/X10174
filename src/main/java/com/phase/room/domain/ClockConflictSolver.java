package com.phase.room.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects whether a clock correction reverses the before/after ordering of a
 * phase relative to the predicted ordering, then returns the deterministic
 * minimum set of stations whose removal clears every reversed pair
 * (minimum vertex cover of the reversal graph, exact branch-and-bound).
 */
public final class ClockConflictSolver {

    private ClockConflictSolver() {
    }

    public static final class StationOrder {
        public final long stationId;
        public final double rawMs;
        public final double correctedMs;
        public final double predictedMs;

        public StationOrder(long stationId, double rawMs, double correctedMs, double predictedMs) {
            this.stationId = stationId;
            this.rawMs = rawMs;
            this.correctedMs = correctedMs;
            this.predictedMs = predictedMs;
        }
    }

    public static ConflictReport analyze(List<StationOrder> stations) {
        int n = stations.size();
        boolean[][] edge = new boolean[n][n];
        List<long[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                StationOrder a = stations.get(i);
                StationOrder b = stations.get(j);
                int raw = compareThenId(a.rawMs, a.stationId, b.rawMs, b.stationId);
                int pred = compareThenId(a.predictedMs, a.stationId, b.predictedMs, b.stationId);
                int corr = compareThenId(a.correctedMs, a.stationId, b.correctedMs, b.stationId);
                if (raw == pred && raw != 0 && corr == -raw) {
                    edge[i][j] = edge[j][i] = true;
                    long first = raw < 0 ? a.stationId : b.stationId;
                    long second = raw < 0 ? b.stationId : a.stationId;
                    pairs.add(new long[]{first, second});
                }
            }
        }
        if (pairs.isEmpty()) {
            return new ConflictReport(false, List.of(), List.of());
        }
        boolean[] cover = minimumCover(edge, n);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (cover[i]) {
                ids.add(stations.get(i).stationId);
            }
        }
        ids.sort(Long::compare);
        pairs.sort((p, q) -> p[0] != q[0] ? Long.compare(p[0], q[0]) : Long.compare(p[1], q[1]));
        return new ConflictReport(true, ids, pairs);
    }

    private static int compareThenId(double t1, long id1, double t2, long id2) {
        if (t1 < t2) {
            return -1;
        }
        if (t1 > t2) {
            return 1;
        }
        return Long.compare(id1, id2);
    }

    private static boolean[] minimumCover(boolean[][] edge, int n) {
        boolean[] chosen = new boolean[n];
        boolean[] best = new boolean[n];
        int[] bestSize = {Integer.MAX_VALUE};
        search(edge, n, chosen, 0, best, bestSize);
        return best;
    }

    private static void search(boolean[][] edge, int n, boolean[] chosen, int from,
                               boolean[] best, int[] bestSize) {
        int chosenCount = 0;
        for (boolean c : chosen) {
            if (c) {
                chosenCount++;
            }
        }
        if (chosenCount >= bestSize[0]) {
            return;
        }
        int u = -1;
        int v = -1;
        outer:
        for (int i = 0; i < n; i++) {
            if (chosen[i]) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                if (!chosen[j] && edge[i][j]) {
                    u = i;
                    v = j;
                    break outer;
                }
            }
        }
        if (u == -1) {
            if (chosenCount < bestSize[0]
                    || chosenCount == bestSize[0] && lexSmaller(chosen, best, n)) {
                bestSize[0] = chosenCount;
                System.arraycopy(chosen, 0, best, 0, n);
            }
            return;
        }
        chosen[u] = true;
        search(edge, n, chosen, from + 1, best, bestSize);
        chosen[u] = false;
        chosen[v] = true;
        search(edge, n, chosen, from + 1, best, bestSize);
        chosen[v] = false;
    }

    private static boolean lexSmaller(boolean[] a, boolean[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return a[i];
            }
        }
        return false;
    }
}
