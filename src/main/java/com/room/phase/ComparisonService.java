package com.room.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ComparisonService {
    private static final long TIE_NS = 1000;

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final BranchService branches;
    private final TravelTimeService travel;

    public ComparisonService(JdbcTemplate jdbc, CatalogService catalog, BranchService branches, TravelTimeService travel) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.branches = branches;
        this.travel = travel;
    }

    public Map<String, Object> compare(String branchCode, List<Long> modelIds, Double windowSec) {
        long branchId = branchId(branchCode);
        long windowNs = Math.round((windowSec == null ? 2.0 : windowSec) * 1_000_000_000.0);
        List<Long> ids = modelIds == null || modelIds.isEmpty()
                ? jdbc.queryForList("SELECT id FROM velocity_models ORDER BY id", Long.class)
                : modelIds.stream().sorted().distinct().toList();
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM events WHERE id=?", jdbc.queryForObject("SELECT event_id FROM interpretation_branches WHERE id=?", Long.class, branchId));
        Map<String, Long> corrections = branches.clockMap(branchId);
        Map<String, Map<String, Object>> stations = branches.stationMap(branchId);
        List<Map<String, Object>> picks = branches.activePickRows(branchId);
        List<Map<String, Object>> modelResults = new ArrayList<>();
        for (Long modelId : ids) {
            Map<String, Object> model = catalog.requiredModel(modelId);
            modelResults.add(evaluate(model, event, stations, corrections, picks, windowNs));
        }
        modelResults.sort(Comparator.comparing((Map<String, Object> r) -> (Boolean) r.get("eligible")).reversed()
                .thenComparingDouble(r -> ((Number) r.get("rmsResidualNs")).doubleValue())
                .thenComparingLong(r -> ((Number) r.get("modelId")).longValue()));
        List<Map<String, Object>> eligible = modelResults.stream().filter(row -> Boolean.TRUE.equals(row.get("eligible"))).toList();
        Map<String, Object> winner = eligible.isEmpty() ? null : eligible.get(0);
        List<Long> tiedModelIds = new ArrayList<>();
        if (winner != null) {
            double winningScore = ((Number) winner.get("rmsResidualNs")).doubleValue();
            for (Map<String, Object> row : eligible) {
                if (Math.abs(((Number) row.get("rmsResidualNs")).doubleValue() - winningScore) <= TIE_NS) tiedModelIds.add(((Number) row.get("modelId")).longValue());
            }
        }
        return Map.of("branchCode", branchCode, "windowNs", windowNs, "winnerModelId", winner == null ? null : winner.get("modelId"),
                "tied", tiedModelIds.size() > 1, "tiedModelIds", tiedModelIds, "models", modelResults);
    }


    public Map<String, Object> replay(String branchCode, Map<String, Object> payload) {
        long branchId = branchId(branchCode);
        long modelId = payload.get("modelId") == null
                ? jdbc.queryForObject("SELECT current_model_id FROM interpretation_branches WHERE id=?", Long.class, branchId)
                : ((Number) payload.get("modelId")).longValue();
        Map<String, Object> model = catalog.requiredModel(modelId);
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM events WHERE id=?", jdbc.queryForObject("SELECT event_id FROM interpretation_branches WHERE id=?", Long.class, branchId));
        Map<String, Long> corrections = branches.clockMap(branchId);
        if (payload.get("clockCorrectionNs") instanceof Map<?, ?> overrides) {
            overrides.forEach((station, value) -> corrections.put(String.valueOf(station), ((Number) value).longValue()));
        }
        Map<String, Map<String, Object>> stations = new LinkedHashMap<>(branches.stationMap(branchId));
        if (payload.get("stationVersionIds") instanceof Map<?, ?> stationOverrides) {
            stationOverrides.forEach((station, value) -> stations.put(String.valueOf(station), catalog.requiredStationVersion(((Number) value).longValue())));
        }
        List<Map<String, Object>> picks = new ArrayList<>();
        Map<String, Object> selected = payload.get("pickVersionIds") instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        for (Map<String, Object> current : branches.activePickRows(branchId)) {
            String code = current.get("pick_code").toString();
            long pickVersionId = selected.containsKey(code) ? ((Number) selected.get(code)).longValue() : ((Number) current.get("id")).longValue();
            Map<String, Object> historical = jdbc.queryForList("SELECT * FROM pick_versions WHERE id=?", pickVersionId).stream().findFirst()
                    .orElseThrow(() -> new ApiException(404, "pick version not found: " + pickVersionId));
            if (!"CANDIDATE".equals(historical.get("status"))) continue;
            picks.add(historical);
        }
        double windowSec = payload.get("timeWindowSec") == null ? 2.0 : ((Number) payload.get("timeWindowSec")).doubleValue();
        Map<String, Object> result = evaluate(model, event, stations, corrections, picks, Math.round(windowSec * 1_000_000_000.0));
        result.put("replay", true);
        result.put("branchCode", branchCode);
        result.put("pinnedModelId", modelId);
        return result;
    }

    private Map<String, Object> evaluate(Map<String, Object> model, Map<String, Object> event,
                                         Map<String, Map<String, Object>> stations, Map<String, Long> corrections,
                                         List<Map<String, Object>> picks, long windowNs) {
        Map<String, List<Map<String, Object>>> byStationPhase = new LinkedHashMap<>();
        for (Map<String, Object> pick : picks) {
            byStationPhase.computeIfAbsent(key(pick), ignored -> new ArrayList<>()).add(pick);
        }
        List<Map<String, Object>> stationRows = new ArrayList<>();
        double weightedSquares = 0;
        double totalWeight = 0;
        boolean eligible = true;
        for (Map.Entry<String, Map<String, Object>> stationEntry : stations.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            String station = stationEntry.getKey();
            Map<String, Object> phaseRows = new LinkedHashMap<>();
            long stationCorrection = corrections.getOrDefault(station, 0L);
            for (String phase : List.of("P", "S")) {
                List<Map<String, Object>> candidates = byStationPhase.getOrDefault(station + "|" + phase, List.of());
                if (candidates.isEmpty()) continue;
                double predicted = travel.predictedTimeNs(event, stationEntry.getValue(), model, phase);
                Map<String, Object> selected = candidates.stream()
                        .min(Comparator.comparingDouble((Map<String, Object> p) -> Math.abs(((Number) p.get("time_ns")).longValue() + stationCorrection - predicted))
                                .thenComparingLong(p -> ((Number) p.get("id")).longValue())).orElseThrow();
                long observed = ((Number) selected.get("time_ns")).longValue() + stationCorrection;
                long residual = Math.round(observed - predicted);
                long low = ((Number) selected.get("confidence_low_ns")).longValue() + stationCorrection;
                long high = ((Number) selected.get("confidence_high_ns")).longValue() + stationCorrection;
                boolean inWindow = Math.abs(residual) <= windowNs && high >= predicted - windowNs && low <= predicted + windowNs;
                Map<String, Object> weight = weightFor(station, selected, high - low, windowNs);
                double weightValue = ((Number) weight.get("weight")).doubleValue();
                if (!inWindow) eligible = false;
                else {
                    weightedSquares += weightValue * residual * residual;
                    totalWeight += weightValue;
                }
                Map<String, Object> phaseRow = new LinkedHashMap<>();
                phaseRow.put("phase", phase);
                phaseRow.put("selectedPickCode", selected.get("pick_code"));
                phaseRow.put("predictedTimeNs", Math.round(predicted));
                phaseRow.put("observedTimeNs", observed);
                phaseRow.put("residualNs", residual);
                phaseRow.put("inWindow", inWindow);
                phaseRow.put("confidenceLowNs", low);
                phaseRow.put("confidenceHighNs", high);
                phaseRow.put("candidateCount", candidates.size());
                phaseRow.put("weightSources", weight);
                phaseRows.put(phase, phaseRow);
            }
            if (!phaseRows.isEmpty()) {
                Map<String, Object> stationRow = new LinkedHashMap<>();
                stationRow.put("stationCode", station);
                stationRow.put("phases", phaseRows);
                if (phaseRows.containsKey("P") && phaseRows.containsKey("S")) {
                    Map<String, Object> p = (Map<String, Object>) phaseRows.get("P");
                    Map<String, Object> s = (Map<String, Object>) phaseRows.get("S");
                    stationRow.put("predictedDiffNs", ((Number) s.get("predictedTimeNs")).longValue() - ((Number) p.get("predictedTimeNs")).longValue());
                    stationRow.put("observedDiffNs", ((Number) s.get("observedTimeNs")).longValue() - ((Number) p.get("observedTimeNs")).longValue());
                }
                stationRows.add(stationRow);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelId", model.get("id"));
        result.put("modelCode", model.get("model_code"));
        result.put("modelVersion", model.get("version"));
        result.put("eligible", eligible);
        result.put("rmsResidualNs", totalWeight == 0 ? (eligible ? 0 : Double.POSITIVE_INFINITY) : Math.sqrt(weightedSquares / totalWeight));
        result.put("weightSum", totalWeight);
        result.put("stations", stationRows);
        return result;
    }

    private Map<String, Object> weightFor(String station, Map<String, Object> pick, long intervalNs, long windowNs) {
        Map<String, Object> source = new LinkedHashMap<>();
        double stationWeight = 1.0;
        double sourceWeight = "manual".equals(pick.get("source")) ? 1.0 : 0.8;
        double gapPenalty = 0;
        double clippingPenalty = 0;
        double overlapPenalty = 0;
        List<Map<String, Object>> clips = jdbc.queryForList("""
                SELECT wv.* FROM waveform_clips wc JOIN waveform_versions wv ON wv.id=wc.latest_version_id
                WHERE wc.station_code=? ORDER BY wc.id
                """, station);
        for (Map<String, Object> clip : clips) {
            gapPenalty = Math.max(gapPenalty, coveragePenalty(clip.get("gaps_json").toString(), clip.get("sample_count")));
            overlapPenalty = Math.max(overlapPenalty, coveragePenalty(clip.get("overlaps_json").toString(), clip.get("sample_count")));
            int clipped = read(clip.get("clipped_json").toString(), new TypeReference<List<Integer>>() {}).size();
            int count = ((Number) clip.get("sample_count")).intValue();
            clippingPenalty = Math.max(clippingPenalty, count == 0 ? 0 : clipped / (double) count);
        }
        double confidenceWeight = 1.0 - 0.35 * Math.min(1.0, intervalNs / (double) Math.max(1, windowNs * 2));
        double weight = stationWeight * sourceWeight * (1 - Math.min(0.5, gapPenalty)) * (1 - Math.min(0.25, overlapPenalty))
                * (1 - Math.min(0.25, clippingPenalty)) * confidenceWeight;
        source.put("station", stationWeight);
        source.put("pickSource", sourceWeight);
        source.put("gapPenalty", gapPenalty);
        source.put("overlapPenalty", overlapPenalty);
        source.put("clippingPenalty", clippingPenalty);
        source.put("confidence", confidenceWeight);
        source.put("weight", Math.round(weight * 1_000_000.0) / 1_000_000.0);
        return source;
    }

    private double coveragePenalty(String json, Object sampleCount) {
        List<List<Integer>> ranges = read(json, new TypeReference<List<List<Integer>>>() {});
        int covered = 0;
        int count = ((Number) sampleCount).intValue();
        for (List<Integer> range : ranges) covered += Math.max(0, Math.min(count, range.get(1)) - Math.max(0, range.get(0)));
        return count == 0 ? 0 : Math.min(1.0, covered / (double) count);
    }

    private long branchId(String code) {
        return jdbc.queryForList("SELECT id FROM interpretation_branches WHERE branch_code=?", Long.class, code).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "branch not found: " + code));
    }

    private static String key(Map<String, Object> pick) {
        return pick.get("station_code") + "|" + pick.get("phase");
    }

    private <T> T read(String json, TypeReference<T> type) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
