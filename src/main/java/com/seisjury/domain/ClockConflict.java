package com.seisjury.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects order reversals between predicted P-arrival order and corrected
 * observed P-arrival order, and computes a minimum (deterministic) station set
 * whose removal eliminates every conflict.
 */
public final class ClockConflict {

    public record Observation(String stationCode, long correctedMs, double predictedMs) {
    }

    public record Pair(String nearStation, String farStation) {
    }

    public record Conflict(Pair pair, long nearCorrectedMs, long farCorrectedMs,
                           double nearPredictedMs, double farPredictedMs) {
    }

    public record Report(boolean consistent, List<Conflict> conflicts, List<String> minimumSet) {
    }

    private ClockConflict() {
    }

    public static Report analyze(List<Observation> observations) {
        List<Observation> ordered = observations.stream()
                .sorted(Comparator.comparingDouble(Observation::predictedMs)
                        .thenComparing(Observation::stationCode))
                .toList();
        List<Conflict> conflicts = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = i + 1; j < ordered.size(); j++) {
                Observation near = ordered.get(i);
                Observation far = ordered.get(j);
                if (near.predictedMs() >= far.predictedMs()) {
                    continue;
                }
                if (near.correctedMs() > far.correctedMs()) {
                    conflicts.add(new Conflict(new Pair(near.stationCode(), far.stationCode()),
                            near.correctedMs(), far.correctedMs(),
                            near.predictedMs(), far.predictedMs()));
                }
            }
        }
        List<String> set = minimumVertexCover(conflicts);
        return new Report(conflicts.isEmpty(), conflicts, set);
    }

    static List<String> minimumVertexCover(List<Conflict> conflicts) {
        if (conflicts.isEmpty()) {
            return List.of();
        }
        Set<String> vertices = new LinkedHashSet<>();
        for (Conflict c : conflicts) {
            vertices.add(c.pair().nearStation());
            vertices.add(c.pair().farStation());
        }
        int n = vertices.size();
        Map<String, Integer> index = new HashMap<>();
        List<String> names = new ArrayList<>(vertices);
        names.sort(Comparator.naturalOrder());
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
        List<int[]> edges = new ArrayList<>();
        for (Conflict c : conflicts) {
            int a = index.get(c.pair().nearStation());
            int b = index.get(c.pair().farStation());
            edges.add(new int[] {a, b});
        }

        List<Integer> best = null;
        int limit = Math.min(n, 24);
        for (int k = 1; k <= limit; k++) {
            Set<Integer> chosen = new LinkedHashSet<>();
            if (search(edges, chosen, k)) {
                best = new ArrayList<>(chosen);
                break;
            }
        }
        if (best == null) {
            best = greedyCover(edges);
        }
        best.sort(Integer::compare);
        return best.stream().map(names::get).toList();
    }

    private static boolean search(List<int[]> edges, Set<Integer> chosen, int remaining) {
        int[] uncovered = null;
        for (int[] edge : edges) {
            if (!chosen.contains(edge[0]) && !chosen.contains(edge[1])) {
                uncovered = edge;
                break;
            }
        }
        if (uncovered == null) {
            return true;
        }
        if (remaining == 0) {
            return false;
        }
        int a = uncovered[0];
        int b = uncovered[1];
        int first = Math.min(a, b);
        int second = Math.max(a, b);
        chosen.add(first);
        if (search(edges, chosen, remaining - 1)) {
            return true;
        }
        chosen.remove(first);
        chosen.add(second);
        if (search(edges, chosen, remaining - 1)) {
            return true;
        }
        chosen.remove(second);
        return false;
    }

    private static List<Integer> greedyCover(List<int[]> edges) {
        List<int[]> remaining = new ArrayList<>(edges);
        Set<Integer> cover = new LinkedHashSet<>();
        while (!remaining.isEmpty()) {
            Map<Integer, Integer> degree = new HashMap<>();
            for (int[] edge : remaining) {
                degree.merge(edge[0], 1, Integer::sum);
                degree.merge(edge[1], 1, Integer::sum);
            }
            int pick = degree.entrySet().stream()
                    .max(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparing(Map.Entry::getKey))
                    .orElseThrow().getKey();
            cover.add(pick);
            remaining.removeIf(edge -> edge[0] == pick || edge[1] == pick);
        }
        return new ArrayList<>(cover);
    }

    public static boolean overlap(Long lowA, Long highA, Long lowB, Long highB) {
        if (lowA == null || highA == null || lowB == null || highB == null) {
            return false;
        }
        return Math.max(lowA, lowB) <= Math.min(highA, highB);
    }
}
