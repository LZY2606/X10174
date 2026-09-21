package com.room.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BranchService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CatalogService catalog;
    private final PickService picks;

    public BranchService(JdbcTemplate jdbc, ObjectMapper mapper, CatalogService catalog, PickService picks) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.catalog = catalog;
        this.picks = picks;
    }

    @Transactional
    public Map<String, Object> createBranch(Models.BranchRequest request) {
        CatalogService.requireText(request.branchCode(), "branchCode");
        CatalogService.requireText(request.name(), "name");
        Map<String, Object> event = catalog.eventByCode(request.eventCode());
        long modelId = request.modelId() == null ? latestModelId() : request.modelId();
        catalog.requiredModel(modelId);
        long eventId = ((Number) event.get("id")).longValue();
        jdbc.update("INSERT INTO interpretation_branches(branch_code,name,event_id,current_model_id,created_at) VALUES (?,?,?,?,?)",
                request.branchCode(), request.name(), eventId, modelId, Instant.now().toString());
        long branchId = idByCode(request.branchCode());
        pinStations(branchId, request.stationVersionIds() == null ? Map.of() : request.stationVersionIds());
        attachCandidatePicks(branchId, eventId);
        record(branchId, "createBranch", Map.of("modelId", modelId));
        return branch(branchId);
    }

    @Transactional
    public Map<String, Object> action(String branchCode, Models.ActionRequest request) {
        long branchId = idByCode(branchCode);
        ensureNotFrozen(branchId);
        String type = request.type();
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        return switch (type) {
            case "movePick" -> movePick(branchId, payload);
            case "updatePick" -> updatePick(branchId, payload);
            case "mergeCandidates" -> merge(branchId, payload);
            case "markNoise" -> markNoise(branchId, payload, true);
            case "restoreCandidate" -> markNoise(branchId, payload, false);
            case "setClockCorrection" -> setClock(branchId, payload);
            case "setVelocityModel" -> setModel(branchId, payload);
            default -> throw new IllegalArgumentException("unknown action type: " + type);
        };
    }

    @Transactional
    public Map<String, Object> undo(String branchCode) {
        long branchId = idByCode(branchCode);
        ensureNotFrozen(branchId);
        List<Map<String, Object>> events = jdbc.queryForList("SELECT * FROM branch_events WHERE branch_id=? ORDER BY seq DESC LIMIT 1", branchId);
        if (events.isEmpty()) throw new ApiException(409, "branch has no events to undo");
        Map<String, Object> event = events.get(0);
        String type = event.get("event_type").toString();
        Map<String, Object> payload = readMap(event.get("payload_json").toString());
        switch (type) {
            case "movePick", "updatePick", "mergeCandidates", "markNoise", "restoreCandidate" -> {
                long previous = ((Number) payload.get("previousVersionId")).longValue();
                String pickCode = payload.get("pickCode").toString();
                jdbc.update("INSERT INTO branch_picks(branch_id,pick_code,current_pick_version_id) VALUES (?,?,?) ON CONFLICT(branch_id,pick_code) DO UPDATE SET current_pick_version_id=excluded.current_pick_version_id", branchId, pickCode, previous);
                if ("mergeCandidates".equals(type)) {
                    for (String other : strings(payload.get("mergedCodes"))) {
                        long prev = ((Number) payload.get("previous" + other + "VersionId")).longValue();
                        jdbc.update("UPDATE branch_picks SET current_pick_version_id=? WHERE branch_id=? AND pick_code=?", prev, branchId, other);
                    }
                }
            }
            case "setClockCorrection" -> {
                String station = payload.get("stationCode").toString();
                if (Boolean.TRUE.equals(payload.get("existed"))) jdbc.update("UPDATE branch_clock_corrections SET correction_ns=? WHERE branch_id=? AND station_code=?", ((Number) payload.get("previousCorrectionNs")).longValue(), branchId, station);
                else jdbc.update("DELETE FROM branch_clock_corrections WHERE branch_id=? AND station_code=?", branchId, station);
            }
            case "setVelocityModel" -> jdbc.update("UPDATE interpretation_branches SET current_model_id=? WHERE id=?", ((Number) payload.get("previousModelId")).longValue(), branchId);
            default -> throw new IllegalStateException("cannot undo event: " + type);
        }
        jdbc.update("DELETE FROM branch_events WHERE id=?", event.get("id"));
        return branch(branchId);
    }

    @Transactional
    public Map<String, Object> freeze(String branchCode) {
        long branchId = idByCode(branchCode);
        ensureNotFrozen(branchId);
        ConflictResult conflict = conflict(branchId, currentModelId(branchId), 120_000_000_000L);
        if (!conflict.stations.isEmpty()) {
            throw new ApiException(409, "freeze blocked by clock order conflict: " + String.join(",", conflict.stations));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        long frozenModelId = currentModelId(branchId);
        snapshot.put("model", catalog.requiredModel(frozenModelId));
        snapshot.put("modelId", frozenModelId);
        snapshot.put("stations", jdbc.queryForList("""
                SELECT bsv.branch_id,bsv.station_code,bsv.station_version_id,sv.version,sv.latitude,sv.longitude,sv.elevation_m,sv.content_hash
                FROM branch_station_versions bsv JOIN station_versions sv ON sv.id=bsv.station_version_id
                WHERE bsv.branch_id=? ORDER BY bsv.station_code
                """, branchId));
        snapshot.put("picks", jdbc.queryForList("""
                SELECT bp.branch_id,bp.pick_code,bp.current_pick_version_id,pv.version,pv.phase,pv.time_ns,pv.polarity,pv.status,pv.content_hash
                FROM branch_picks bp JOIN pick_versions pv ON pv.id=bp.current_pick_version_id
                WHERE bp.branch_id=? ORDER BY pv.station_code,pv.phase,pv.time_ns,pv.id
                """, branchId));
        snapshot.put("clockCorrections", jdbc.queryForList("SELECT * FROM branch_clock_corrections WHERE branch_id=? ORDER BY station_code", branchId));
        snapshot.put("frozenAt", Instant.now().toString());
        jdbc.update("UPDATE interpretation_branches SET frozen_at=?, frozen_snapshot_json=? WHERE id=?", snapshot.get("frozenAt"), write(snapshot), branchId);
        return branch(branchId);
    }

    @Transactional
    public Map<String, Object> cloneBranch(String sourceCode, Models.CloneBranchRequest request) {
        long sourceId = idByCode(sourceCode);
        long sourceModelId = currentModelId(sourceId);
        long modelId = request.modelId() == null ? sourceModelId : request.modelId();
        catalog.requiredModel(modelId);
        jdbc.update("INSERT INTO interpretation_branches(branch_code,name,event_id,parent_branch_id,current_model_id,created_at) VALUES (?,?,?,?,?,?)",
                request.branchCode(), request.name(), eventId(sourceId), sourceId, modelId, Instant.now().toString());
        long targetId = idByCode(request.branchCode());
        for (Map<String, Object> row : jdbc.queryForList("SELECT station_code,station_version_id FROM branch_station_versions WHERE branch_id=?", sourceId)) {
            jdbc.update("INSERT INTO branch_station_versions(branch_id,station_code,station_version_id) VALUES (?,?,?)", targetId, row.get("station_code"), row.get("station_version_id"));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT pick_code,current_pick_version_id FROM branch_picks WHERE branch_id=?", sourceId)) {
            jdbc.update("INSERT INTO branch_picks(branch_id,pick_code,current_pick_version_id) VALUES (?,?,?)", targetId, row.get("pick_code"), row.get("current_pick_version_id"));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT station_code,correction_ns FROM branch_clock_corrections WHERE branch_id=?", sourceId)) {
            jdbc.update("INSERT INTO branch_clock_corrections(branch_id,station_code,correction_ns) VALUES (?,?,?)", targetId, row.get("station_code"), row.get("correction_ns"));
        }
        record(targetId, "cloneBranch", Map.of("parentBranchId", sourceId, "modelId", modelId));
        return branch(targetId);
    }

    public ConflictResult conflictReport(String branchCode, double windowSec) {
        long branchId = idByCode(branchCode);
        return conflict(branchId, currentModelId(branchId), Math.round(windowSec * 1_000_000_000.0));
    }

    public ConflictResult conflict(long branchId, long modelId, long windowNs) {
        TravelTimeService travel = new TravelTimeService(catalog);
        Map<String, Long> corrections = clockMap(branchId);
        Map<String, Map<String, Object>> stations = stationMap(branchId);
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM events WHERE id=?", eventId(branchId));
        Map<String, Object> model = catalog.requiredModel(modelId);
        List<long[]> edges = new ArrayList<>();
        List<Map<String, Object>> rows = activePickRows(branchId);
        List<CandidateOrder> orders = new ArrayList<>();
        for (Map<String, Object> pick : rows) {
            String station = pick.get("station_code").toString();
            String phase = pick.get("phase").toString();
            long observed = ((Number) pick.get("time_ns")).longValue();
            long corrected = observed + corrections.getOrDefault(station, 0L);
            double predicted = travel.predictedTimeNs(event, stations.get(station), model, phase);
            orders.add(new CandidateOrder(station, phase, observed, corrected, (long) predicted));
        }
        for (int i = 0; i < orders.size(); i++) for (int j = i + 1; j < orders.size(); j++) {
            CandidateOrder a = orders.get(i), b = orders.get(j);
            if (!a.phase.equals(b.phase)) continue;
            int rawObserved = Long.compare(a.raw, b.raw);
            int correctedObserved = Long.compare(a.corrected, b.corrected);
            int predicted = Long.compare(a.predicted, b.predicted);
            if (rawObserved == predicted && correctedObserved == -predicted && predicted != 0) {
                List<String> codes = new ArrayList<>(stations.keySet());
                edges.add(new long[]{codes.indexOf(a.station), codes.indexOf(b.station)});
            }
        }
        List<String> stationCodes = new ArrayList<>(stations.keySet());
        return new ConflictResult(minVertexCover(edges, stationCodes), edges);
    }

    public Map<String, Object> branch(String code) { return branch(idByCode(code)); }

    public Map<String, Object> branch(long id) {
        Map<String, Object> branch = jdbc.queryForMap("SELECT * FROM interpretation_branches WHERE id=?", id);
        branch.put("stations", jdbc.queryForList("SELECT * FROM branch_station_versions WHERE branch_id=? ORDER BY station_code", id));
        branch.put("picks", jdbc.queryForList("""
                SELECT bp.pick_code,bp.current_pick_version_id,pv.* FROM branch_picks bp JOIN pick_versions pv ON pv.id=bp.current_pick_version_id
                WHERE bp.branch_id=? ORDER BY pv.station_code,pv.phase,pv.time_ns,pv.id
                """, id));
        branch.put("clockCorrections", jdbc.queryForList("SELECT * FROM branch_clock_corrections WHERE branch_id=? ORDER BY station_code", id));
        branch.put("events", jdbc.queryForList("SELECT * FROM branch_events WHERE branch_id=? ORDER BY seq,id", id));
        return branch;
    }

    List<Map<String, Object>> activePickRows(long branchId) {
        return jdbc.queryForList("""
                SELECT pv.* FROM branch_picks bp JOIN pick_versions pv ON pv.id=bp.current_pick_version_id
                WHERE bp.branch_id=? AND pv.status='CANDIDATE' ORDER BY pv.station_code,pv.phase,pv.time_ns,pv.id
                """, branchId);
    }

    Map<String, Long> clockMap(long branchId) {
        Map<String, Long> map = new LinkedHashMap<>();
        jdbc.queryForList("SELECT station_code,correction_ns FROM branch_clock_corrections WHERE branch_id=? ORDER BY station_code", branchId)
                .forEach(row -> map.put(row.get("station_code").toString(), ((Number) row.get("correction_ns")).longValue()));
        return map;
    }

    Map<String, Map<String, Object>> stationMap(long branchId) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        jdbc.queryForList("SELECT sv.* FROM branch_station_versions bsv JOIN station_versions sv ON sv.id=bsv.station_version_id WHERE bsv.branch_id=? ORDER BY bsv.station_code", branchId)
                .forEach(row -> map.put(row.get("station_code").toString(), row));
        return map;
    }

    private Map<String, Object> movePick(long branchId, Map<String, Object> payload) {
        long time = longValue(payload, "timeNs");
        Long low = optionalLong(payload, "confidenceLowNs");
        Long high = optionalLong(payload, "confidenceHighNs");
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("timeNs", String.valueOf(time));
        if (low != null) changes.put("confidenceLowNs", String.valueOf(low));
        if (high != null) changes.put("confidenceHighNs", String.valueOf(high));
        return revisePick(branchId, payload, changes);
    }

    private Map<String, Object> updatePick(long branchId, Map<String, Object> payload) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (payload.containsKey("polarity")) changes.put("polarity", payload.get("polarity").toString());
        if (payload.containsKey("confidenceLowNs")) changes.put("confidenceLowNs", payload.get("confidenceLowNs").toString());
        if (payload.containsKey("confidenceHighNs")) changes.put("confidenceHighNs", payload.get("confidenceHighNs").toString());
        if (payload.containsKey("source")) changes.put("source", payload.get("source").toString());
        return revisePick(branchId, payload, changes);
    }

    private Map<String, Object> revisePick(long branchId, Map<String, Object> payload, Map<String, String> changes) {
        String code = payload.get("pickCode").toString();
        Map<String, Object> old = picks.currentPick(branchId, code);
        long time = changes.containsKey("timeNs") ? Long.parseLong(changes.get("timeNs")) : ((Number) old.get("time_ns")).longValue();
        long low = changes.containsKey("confidenceLowNs") ? Long.parseLong(changes.get("confidenceLowNs")) : ((Number) old.get("confidence_low_ns")).longValue();
        long high = changes.containsKey("confidenceHighNs") ? Long.parseLong(changes.get("confidenceHighNs")) : ((Number) old.get("confidence_high_ns")).longValue();
        String polarity = changes.getOrDefault("polarity", old.get("polarity").toString());
        String source = changes.getOrDefault("source", old.get("source").toString());
        long versionId = picks.insertPick(eventId(branchId), code, picks.nextVersion(code), old.get("station_code").toString(), old.get("phase").toString(),
                time, PickService.normalizePolarity(polarity), low, high, old.get("status").toString(), old.get("merged_into_code") == null ? null : old.get("merged_into_code").toString(), source, null);
        updateCurrent(branchId, code, versionId);
        Map<String, Object> eventPayload = new LinkedHashMap<>(payload);
        eventPayload.put("pickCode", code);
        eventPayload.put("previousVersionId", ((Number) old.get("id")).longValue());
        eventPayload.put("newVersionId", versionId);
        record(branchId, payload.containsKey("timeNs") ? "movePick" : "updatePick", eventPayload);
        return branch(branchId);
    }

    private Map<String, Object> merge(long branchId, Map<String, Object> payload) {
        String target = payload.get("targetPickCode").toString();
        List<String> mergedCodes = strings(payload.get("pickCodes"));
        if (!mergedCodes.contains(target)) throw new IllegalArgumentException("targetPickCode must be included in pickCodes");
        Map<String, Object> targetOld = picks.currentPick(branchId, target);
        Map<String, Long> previous = new LinkedHashMap<>();
        for (String code : mergedCodes) previous.put(code, ((Number) picks.currentPick(branchId, code).get("id")).longValue());
        long targetNewId = copyWithStatus(branchId, targetOld, "CANDIDATE", null);
        updateCurrent(branchId, target, targetNewId);
        for (String code : mergedCodes) {
            if (code.equals(target)) continue;
            Map<String, Object> old = picks.currentPick(branchId, code);
            long newId = copyWithStatus(branchId, old, "MERGED", target);
            updateCurrent(branchId, code, newId);
        }
        Map<String, Object> eventPayload = new LinkedHashMap<>(payload);
        eventPayload.put("pickCode", target);
        eventPayload.put("mergedCodes", mergedCodes.stream().filter(code -> !code.equals(target)).toList());
        eventPayload.put("previousVersionId", previous.get(target));
        eventPayload.put("newVersionId", targetNewId);
        eventPayload.put("previous" + target + "VersionId", previous.get(target));
        for (String code : mergedCodes) if (!code.equals(target)) eventPayload.put("previous" + code + "VersionId", previous.get(code));
        record(branchId, "mergeCandidates", eventPayload);
        return branch(branchId);
    }

    private Map<String, Object> markNoise(long branchId, Map<String, Object> payload, boolean noise) {
        String code = payload.get("pickCode").toString();
        Map<String, Object> old = picks.currentPick(branchId, code);
        String status = noise ? "NOISE" : "CANDIDATE";
        long newId = copyWithStatus(branchId, old, status, null);
        updateCurrent(branchId, code, newId);
        Map<String, Object> eventPayload = new LinkedHashMap<>(payload);
        eventPayload.put("pickCode", code);
        eventPayload.put("previousVersionId", ((Number) old.get("id")).longValue());
        eventPayload.put("newVersionId", newId);
        record(branchId, noise ? "markNoise" : "restoreCandidate", eventPayload);
        return branch(branchId);
    }

    private long copyWithStatus(long branchId, Map<String, Object> old, String status, String mergedInto) {
        return picks.insertPick(eventId(branchId), old.get("pick_code").toString(), picks.nextVersion(old.get("pick_code").toString()),
                old.get("station_code").toString(), old.get("phase").toString(), ((Number) old.get("time_ns")).longValue(),
                old.get("polarity").toString(), ((Number) old.get("confidence_low_ns")).longValue(),
                ((Number) old.get("confidence_high_ns")).longValue(), status, mergedInto, old.get("source").toString(), null);
    }

    private Map<String, Object> setClock(long branchId, Map<String, Object> payload) {
        String station = payload.get("stationCode").toString();
        long correction = longValue(payload, "correctionNs");
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT correction_ns FROM branch_clock_corrections WHERE branch_id=? AND station_code=?", branchId, station);
        Map<String, Object> eventPayload = new LinkedHashMap<>(payload);
        eventPayload.put("existed", !existing.isEmpty());
        if (existing.isEmpty()) eventPayload.put("previousCorrectionNs", 0);
        else eventPayload.put("previousCorrectionNs", existing.get(0).get("correction_ns"));
        jdbc.update("INSERT INTO branch_clock_corrections(branch_id,station_code,correction_ns) VALUES (?,?,?) ON CONFLICT(branch_id,station_code) DO UPDATE SET correction_ns=excluded.correction_ns", branchId, station, correction);
        record(branchId, "setClockCorrection", eventPayload);
        return branch(branchId);
    }

    private Map<String, Object> setModel(long branchId, Map<String, Object> payload) {
        long modelId = longValue(payload, "modelId");
        catalog.requiredModel(modelId);
        long oldModel = currentModelId(branchId);
        jdbc.update("UPDATE interpretation_branches SET current_model_id=? WHERE id=?", modelId, branchId);
        Map<String, Object> eventPayload = new LinkedHashMap<>(payload);
        eventPayload.put("previousModelId", oldModel);
        eventPayload.put("newModelId", modelId);
        record(branchId, "setVelocityModel", eventPayload);
        return branch(branchId);
    }

    private void pinStations(long branchId, Map<String, Long> requested) {
        if (requested.isEmpty()) {
            jdbc.queryForList("SELECT station_code, MAX(id) AS id FROM station_versions GROUP BY station_code ORDER BY station_code")
                    .forEach(row -> jdbc.update("INSERT INTO branch_station_versions(branch_id,station_code,station_version_id) VALUES (?,?,?)", branchId, row.get("station_code"), row.get("id")));
        } else {
            requested.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                catalog.requiredStationVersion(entry.getValue());
                jdbc.update("INSERT INTO branch_station_versions(branch_id,station_code,station_version_id) VALUES (?,?,?)", branchId, entry.getKey(), entry.getValue());
            });
        }
    }

    private void attachCandidatePicks(long branchId, long eventId) {
        for (Map<String, Object> pick : jdbc.queryForList("""
                SELECT pick_code, MAX(version) AS version FROM pick_versions WHERE event_id=? AND status='CANDIDATE' GROUP BY pick_code ORDER BY pick_code
                """, eventId)) {
            long id = jdbc.queryForObject("SELECT id FROM pick_versions WHERE pick_code=? AND version=?", Long.class, pick.get("pick_code"), pick.get("version"));
            jdbc.update("INSERT INTO branch_picks(branch_id,pick_code,current_pick_version_id) VALUES (?,?,?)", branchId, pick.get("pick_code"), id);
        }
    }

    private void updateCurrent(long branchId, String code, long versionId) {
        picks.requiredPickVersion(versionId);
        jdbc.update("INSERT INTO branch_picks(branch_id,pick_code,current_pick_version_id) VALUES (?,?,?) ON CONFLICT(branch_id,pick_code) DO UPDATE SET current_pick_version_id=excluded.current_pick_version_id", branchId, code, versionId);
    }

    private void record(long branchId, String type, Map<String, Object> payload) {
        int seq = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM branch_events WHERE branch_id=?", Integer.class, branchId);
        jdbc.update("INSERT INTO branch_events(branch_id,seq,event_type,payload_json,reversible,created_at) VALUES (?,?,?,?,1,?)", branchId, seq, type, write(payload), Instant.now().toString());
    }

    private long idByCode(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM interpretation_branches WHERE branch_code=?", code);
        if (rows.isEmpty()) throw new ApiException(404, "branch not found: " + code);
        return ((Number) rows.get(0).get("id")).longValue();
    }

    private long eventId(long branchId) {
        return jdbc.queryForObject("SELECT event_id FROM interpretation_branches WHERE id=?", Long.class, branchId);
    }

    private long currentModelId(long branchId) {
        return jdbc.queryForObject("SELECT current_model_id FROM interpretation_branches WHERE id=?", Long.class, branchId);
    }

    private long latestModelId() {
        Long id = jdbc.queryForObject("SELECT id FROM velocity_models ORDER BY id LIMIT 1", Long.class);
        if (id == null) throw new ApiException(400, "create a velocity model before a branch");
        return id;
    }

    private void ensureNotFrozen(long branchId) {
        String frozen = jdbc.queryForObject("SELECT frozen_at FROM interpretation_branches WHERE id=?", String.class, branchId);
        if (frozen != null) throw new ApiException(409, "frozen branch cannot be modified; clone it to continue");
    }

    private List<String> minVertexCover(List<long[]> edges, List<String> stationCodes) {
        int vertexCount = stationCodes.size();
        int bestMask = -1;
        int bestSize = vertexCount + 1;
        for (int mask = 0; mask < (1 << vertexCount); mask++) {
            int size = Integer.bitCount(mask);
            if (size > bestSize) continue;
            boolean covers = true;
            for (long[] edge : edges) {
                if (((mask >> edge[0]) & 1) == 0 && ((mask >> edge[1]) & 1) == 1) continue;
                if (((mask >> edge[0]) & 1) == 1 && ((mask >> edge[1]) & 1) == 0) continue;
                if (((mask >> edge[0]) & 1) == 0 && ((mask >> edge[1]) & 1) == 0) {
                    covers = false;
                    break;
                }
            }
            if (covers && (size < bestSize || size == bestSize && (bestMask == -1 || Integer.compareUnsigned(mask, bestMask) < 0))) {
                bestSize = size;
                bestMask = mask;
            }
        }
        List<String> result = new ArrayList<>();
        if (bestMask >= 0) {
            for (int i = 0; i < vertexCount; i++) if (((bestMask >> i) & 1) == 1) result.add(stationCodes.get(i));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("pickCodes are required");
        return list.stream().map(String::valueOf).sorted().toList();
    }

    static long longValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static Long optionalLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }

    private Map<String, Object> readMap(String json) {
        try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    record CandidateOrder(String station, String phase, long raw, long corrected, long predicted) {}
    record ConflictResult(List<String> stations, List<long[]> edges) {}
}
