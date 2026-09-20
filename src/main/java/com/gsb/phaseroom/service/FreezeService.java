package com.gsb.phaseroom.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FreezeService {

    public record StationOrder(String stationCode, double rawTime, double correctedTime,
                               double shift) {
    }

    public record ConflictPair(String earlierStation, String laterStation) {
    }

    private final JdbcTemplate jdbc;
    private final Interpretations interpretations;
    private final Waveforms waveforms;
    private final Stations stations;

    public FreezeService(JdbcTemplate jdbc, Interpretations interpretations,
                         Waveforms waveforms, Stations stations) {
        this.jdbc = jdbc;
        this.interpretations = interpretations;
        this.waveforms = waveforms;
        this.stations = stations;
    }

    /**
     * Effective observed P ordering after applying per-station clock corrections.
     * One selected P pick per station; the raw time includes any segment clock offset.
     */
    public List<StationOrder> correctedOrder(long interpretationId) {
        Interpretations.Interpretation interp = interpretations.getById(interpretationId);
        Map<String, Double> shifts = interpretations.corrections(interpretationId);
        Map<String, Double> earliest = new LinkedHashMap<>();
        for (Interpretations.Pick pick : interpretations.selectedPicks(interpretationId)) {
            if (!"P".equals(pick.phase())) {
                continue;
            }
            double offset = waveforms.clockOffsetAt(pick.stationCode(), "BHZ", pick.time());
            double shift = shifts.getOrDefault(pick.stationCode(), 0.0);
            double corrected = pick.time() + offset + shift;
            earliest.merge(pick.stationCode(), corrected, Math::min);
        }
        List<StationOrder> order = new ArrayList<>();
        for (Map.Entry<String, Double> entry : earliest.entrySet()) {
            String code = entry.getKey();
            double corrected = entry.getValue();
            double shift = shifts.getOrDefault(code, 0.0);
            double raw = corrected - shift;
            order.add(new StationOrder(code, raw, corrected, shift));
        }
        order.sort(Comparator.comparingDouble(StationOrder::rawTime)
                .thenComparing(StationOrder::stationCode));
        return order;
    }

    /**
     * A reversal exists when raw chronological order (ties broken by station code) differs
     * from corrected chronological order (ties broken identically). Returns the ordered
     * station order plus all inverted pairs.
     */
    public Map<String, Object> analyse(long interpretationId) {
        List<StationOrder> rawOrder = correctedOrder(interpretationId);
        List<StationOrder> correctedSorted = new ArrayList<>(rawOrder);
        correctedSorted.sort(Comparator.comparingDouble(StationOrder::correctedTime)
                .thenComparing(StationOrder::stationCode));
        List<String> rawCodes = rawOrder.stream().map(StationOrder::stationCode).toList();
        List<String> correctedCodes = correctedSorted.stream()
                .map(StationOrder::stationCode).toList();

        Map<String, Integer> correctedRank = new LinkedHashMap<>();
        for (int i = 0; i < correctedCodes.size(); i++) {
            correctedRank.put(correctedCodes.get(i), i);
        }
        List<ConflictPair> conflicts = new ArrayList<>();
        for (int i = 0; i < rawCodes.size(); i++) {
            for (int j = i + 1; j < rawCodes.size(); j++) {
                String a = rawCodes.get(i);
                String b = rawCodes.get(j);
                if (correctedRank.get(a) > correctedRank.get(b)) {
                    conflicts.add(new ConflictPair(a, b));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawOrder", rawOrder);
        result.put("correctedOrder", correctedSorted);
        result.put("reversed", !conflicts.isEmpty());
        result.put("conflictPairs", conflicts);
        result.put("minimumConflictStations",
                conflicts.isEmpty() ? List.of() : minimumConflictStations(rawCodes, correctedRank));
        return result;
    }

    /**
     * Minimum number of stations whose removal leaves a corrected order that is
     * non-decreasing relative to raw order. This is n - length of longest non-decreasing
     * subsequence (LNDS); ties are allowed (simultaneous arrivals do not reverse).
     * Ties among equal-length subsequences are broken deterministically by station code.
     */
    static List<String> minimumConflictStations(List<String> rawCodes,
                                                Map<String, Integer> correctedRank) {
        int n = rawCodes.size();
        int[] length = new int[n];
        int[] predecessor = new int[n];
        int bestEnd = 0;
        for (int i = 0; i < n; i++) {
            length[i] = 1;
            predecessor[i] = -1;
            for (int j = 0; j < i; j++) {
                if (correctedRank.get(rawCodes.get(j)) <= correctedRank.get(rawCodes.get(i))
                        && length[j] + 1 > length[i]) {
                    length[i] = length[j] + 1;
                    predecessor[i] = j;
                }
            }
            if (length[i] > length[bestEnd]
                    || (length[i] == length[bestEnd]
                    && rawCodes.get(i).compareTo(rawCodes.get(bestEnd)) < 0)) {
                bestEnd = i;
            }
        }
        boolean[] keep = new boolean[n];
        for (int at = bestEnd; at >= 0; at = predecessor[at]) {
            keep[at] = true;
        }
        List<String> remove = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!keep[i]) {
                remove.add(rawCodes.get(i));
            }
        }
        remove.sort(Comparator.naturalOrder());
        return remove;
    }

    @Transactional
    public Map<String, Object> freeze(String interpCode, Map<String, Object> bundleBuilder) {
        Interpretations.Interpretation interp = interpretations.get(interpCode);
        interpretations.requireOpen(interp);
        Map<String, Object> analysis = analyse(interp.id());
        if (Boolean.TRUE.equals(analysis.get("reversed"))) {
            Map<String, Object> blocked = new LinkedHashMap<>();
            blocked.put("blocked", true);
            blocked.put("reason", "校时修正会反转台站 P 震相先后次序");
            blocked.put("analysis", analysis);
            return blocked;
        }
        jdbc.update(
                "UPDATE interpretation SET frozen = 1, frozen_at = ?, frozen_bundle = ? WHERE id = ?",
                Clock.now(), Json.write(bundleBuilder), interp.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("blocked", false);
        result.put("frozen", true);
        result.put("frozenAt", Clock.now());
        return result;
    }
}
