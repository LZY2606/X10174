package com.gsb.phaseroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Interpretations {

    public record Pick(long id, long interpretationId, String stationCode, String phase,
                       double time, String polarity, double ciHalfWidth, String status,
                       Long mergedInto, String source, int pickVersion) {
    }

    public record Interpretation(long id, String code, String name, long eventId, long modelId,
                                 Map<String, Integer> stationVersions,
                                 Map<String, Integer> trackVersions, int pickVersion,
                                 boolean frozen, Long parentId) {
    }

    private final JdbcTemplate jdbc;
    private final Stations stations;
    private final Waveforms waveforms;

    public Interpretations(JdbcTemplate jdbc, Stations stations, Waveforms waveforms) {
        this.jdbc = jdbc;
        this.stations = stations;
        this.waveforms = waveforms;
    }

    @Transactional
    public Interpretation create(String code, String name, String eventCode, long modelId) {
        long eventId = eventId(eventCode);
        jdbc.queryForObject("SELECT id FROM velocity_model WHERE id = ?", Long.class, modelId);
        Map<String, Integer> stationVersions = new LinkedHashMap<>();
        for (Stations.Station station : stations.listActive()) {
            stationVersions.put(station.code(), station.version());
        }
        Map<String, Integer> trackVersions = new LinkedHashMap<>(waveforms.activeTrackVersions());
        jdbc.update(
                "INSERT INTO interpretation(code, name, event_id, model_id, station_version_map, "
                        + "track_version_map, pick_version, frozen, created_at) "
                        + "VALUES (?,?,?,?,?,?,0,0,?)",
                code, name, eventId, modelId, Json.write(stationVersions),
                Json.write(trackVersions), Clock.now());
        return get(code);
    }

    public long eventId(String eventCode) {
        return jdbc.queryForObject("SELECT id FROM seismic_event WHERE code = ?", Long.class,
                eventCode);
    }

    public Interpretation get(String code) {
        List<Interpretation> list = jdbc.query(
                "SELECT id, code, name, event_id, model_id, station_version_map, "
                        + "track_version_map, pick_version, frozen, parent_interpretation_id "
                        + "FROM interpretation WHERE code = ?",
                (rs, i) -> new Interpretation(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), readIntMap(rs.getString(6)),
                        readIntMap(rs.getString(7)), rs.getInt(8), rs.getInt(9) == 1,
                        rs.getObject(10) == null ? null : ((Number) rs.getObject(10)).longValue()),
                code);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("解释不存在: " + code);
        }
        return list.get(0);
    }

    public Interpretation getById(long id) {
        List<Interpretation> list = jdbc.query(
                "SELECT id, code, name, event_id, model_id, station_version_map, "
                        + "track_version_map, pick_version, frozen, parent_interpretation_id "
                        + "FROM interpretation WHERE id = ?",
                (rs, i) -> new Interpretation(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), readIntMap(rs.getString(6)),
                        readIntMap(rs.getString(7)), rs.getInt(8), rs.getInt(9) == 1,
                        rs.getObject(10) == null ? null : ((Number) rs.getObject(10)).longValue()),
                id);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("解释不存在: " + id);
        }
        return list.get(0);
    }

    static Map<String, Integer> readIntMap(String json) {
        return Json.read(json, new TypeReference<LinkedHashMap<String, Integer>>() {
        });
    }

    public List<Map<String, Object>> list() {
        return jdbc.query(
                "SELECT id, code, name, event_id, model_id, pick_version, frozen, "
                        + "parent_interpretation_id, created_at FROM interpretation "
                        + "ORDER BY code ASC, id ASC",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("code", rs.getString("code"));
                    row.put("name", rs.getString("name"));
                    row.put("eventId", rs.getLong("event_id"));
                    row.put("modelId", rs.getLong("model_id"));
                    row.put("pickVersion", rs.getInt("pick_version"));
                    row.put("frozen", rs.getInt("frozen") == 1);
                    row.put("parentInterpretationId", rs.getObject("parent_interpretation_id"));
                    row.put("createdAt", rs.getString("created_at"));
                    return row;
                });
    }

    public List<Pick> picks(long interpretationId) {
        return jdbc.query(
                "SELECT id, interpretation_id, station_code, phase, time, polarity, "
                        + "ci_half_width_s, status, merged_into, source, pick_version FROM pick "
                        + "WHERE interpretation_id = ? "
                        + "ORDER BY station_code ASC, phase ASC, time ASC, id ASC",
                (rs, i) -> new Pick(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getDouble(5), rs.getString(6), rs.getDouble(7),
                        rs.getString(8), rs.getObject(9) == null ? null : ((Number) rs.getObject(9)).longValue(), rs.getString(10),
                        rs.getInt(11)),
                interpretationId);
    }

    public List<Pick> selectedPicks(long interpretationId) {
        return picks(interpretationId).stream()
                .filter(p -> "SELECTED".equals(p.status()))
                .sorted(Comparator.comparingDouble(Pick::time)
                        .thenComparing(Pick::stationCode)
                        .thenComparing(Pick::phase))
                .toList();
    }

    public Map<String, Double> corrections(long interpretationId) {
        Map<String, Double> out = new LinkedHashMap<>();
        jdbc.query(
                "SELECT station_code, shift_s FROM clock_correction "
                        + "WHERE interpretation_id = ? ORDER BY station_code ASC",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> out.put(rs.getString(1), rs.getDouble(2)), interpretationId);
        return out;
    }

    void requireOpen(Interpretation interpretation) {
        if (interpretation.frozen()) {
            throw new IllegalStateException("解释已冻结，不能修改: " + interpretation.code());
        }
    }

    int nextPickVersion(long interpretationId) {
        Integer current = jdbc.queryForObject(
                "SELECT pick_version FROM interpretation WHERE id = ?", Integer.class,
                interpretationId);
        int next = (current == null ? 0 : current) + 1;
        jdbc.update("UPDATE interpretation SET pick_version = ? WHERE id = ?", next,
                interpretationId);
        return next;
    }

    int nextSeq(long interpretationId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM action_event WHERE interpretation_id = ?",
                Integer.class, interpretationId);
        return (max == null ? 0 : max) + 1;
    }

    void recordEvent(long interpretationId, String type, List<Map<String, Object>> undo,
                     List<Map<String, Object>> redo) {
        jdbc.update(
                "INSERT INTO action_event(interpretation_id, seq, type, undo_payload, "
                        + "redo_payload, created_at) VALUES (?,?,?,?,?,?)",
                interpretationId, nextSeq(interpretationId), type, Json.write(undo),
                Json.write(redo), Clock.now());
    }

    @Transactional
    public Pick addPick(String interpCode, String stationCode, String phase, double time,
                        String polarity, double ciHalfWidth, String source, boolean selected) {
        Interpretation interp = get(interpCode);
        requireOpen(interp);
        Integer stationVersion = interp.stationVersions().get(stationCode);
        if (stationVersion == null) {
            throw new IllegalArgumentException("解释未钉住该台站版本: " + stationCode);
        }
        stations.getVersion(stationCode, stationVersion);
        if (!"P".equals(phase) && !"S".equals(phase)) {
            throw new IllegalArgumentException("震相只能是 P 或 S");
        }
        String status = selected ? "SELECTED" : "CANDIDATE";
        String polarityValue = normalizePolarity(polarity);
        int version = nextPickVersion(interp.id());
        jdbc.update(
                "INSERT INTO pick(interpretation_id, station_code, phase, time, polarity, "
                        + "ci_half_width_s, status, source, pick_version, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                interp.id(), stationCode, phase, time, polarityValue,
                Math.max(0, ciHalfWidth), status, source == null ? "MANUAL" : source, version,
                Clock.now());
        long pickId = jdbc.queryForObject(
                "SELECT MAX(id) FROM pick WHERE interpretation_id = ?", Long.class, interp.id());
        recordEvent(interp.id(), "ADD_PICK", new ArrayList<>(),
                List.of(deletePickOp(pickId)));
        return pick(interp.id(), pickId);
    }

    private String normalizePolarity(String polarity) {
        if (polarity == null || polarity.isBlank()) {
            return null;
        }
        if ("+".equals(polarity) || "-".equals(polarity) || "?".equals(polarity)) {
            return polarity;
        }
        throw new IllegalArgumentException("极性只能是 +、- 或 ?");
    }

    public Pick pick(long interpretationId, long pickId) {
        List<Pick> list = jdbc.query(
                "SELECT id, interpretation_id, station_code, phase, time, polarity, "
                        + "ci_half_width_s, status, merged_into, source, pick_version FROM pick "
                        + "WHERE interpretation_id = ? AND id = ?",
                (rs, i) -> new Pick(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getDouble(5), rs.getString(6), rs.getDouble(7),
                        rs.getString(8), rs.getObject(9) == null ? null : ((Number) rs.getObject(9)).longValue(), rs.getString(10),
                        rs.getInt(11)),
                interpretationId, pickId);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("拾取不存在: " + pickId);
        }
        return list.get(0);
    }

    @Transactional
    public Pick updatePick(String interpCode, long pickId, Double time, String polarity,
                           Double ciHalfWidth) {
        Interpretation interp = get(interpCode);
        requireOpen(interp);
        Pick before = pick(interp.id(), pickId);
        int version = nextPickVersion(interp.id());
        double newTime = time == null ? before.time() : time;
        String newPolarity = polarity == null ? before.polarity() : normalizePolarity(polarity);
        double newCi = ciHalfWidth == null
                ? before.ciHalfWidth()
                : Math.max(0, ciHalfWidth);
        jdbc.update(
                "UPDATE pick SET time = ?, polarity = ?, ci_half_width_s = ?, pick_version = ? "
                        + "WHERE id = ? AND interpretation_id = ?",
                newTime, newPolarity, newCi, version, pickId, interp.id());
        Pick after = pick(interp.id(), pickId);
        recordEvent(interp.id(), "UPDATE_PICK",
                List.of(upsertPickOp(before, version)),
                List.of(upsertPickOp(after, before.pickVersion())));
        return after;
    }

    @Transactional
    public Pick markStatus(String interpCode, long pickId, String status) {
        Interpretation interp = get(interpCode);
        requireOpen(interp);
        Pick before = pick(interp.id(), pickId);
        if (!List.of("CANDIDATE", "SELECTED", "NOISE").contains(status)) {
            throw new IllegalArgumentException("不支持的状态: " + status);
        }
        int version = nextPickVersion(interp.id());
        jdbc.update(
                "UPDATE pick SET status = ?, merged_into = NULL, pick_version = ? "
                        + "WHERE id = ? AND interpretation_id = ?",
                status, version, pickId, interp.id());
        Pick after = pick(interp.id(), pickId);
        recordEvent(interp.id(), "MARK_" + status,
                List.of(upsertPickOp(before, version)),
                List.of(upsertPickOp(after, before.pickVersion())));
        return after;
    }

    @Transactional
    public Pick mergeCandidates(String interpCode, long winningPickId, long losingPickId) {
        Interpretation interp = get(interpCode);
        requireOpen(interp);
        Pick winner = pick(interp.id(), winningPickId);
        Pick loser = pick(interp.id(), losingPickId);
        if (!winner.stationCode().equals(loser.stationCode())
                || !winner.phase().equals(loser.phase())) {
            throw new IllegalArgumentException("只能合并同台站同震相的候选");
        }
        int version = nextPickVersion(interp.id());
        Pick loserBefore = loser;
        jdbc.update(
                "UPDATE pick SET status = 'MERGED', merged_into = ?, pick_version = ? "
                        + "WHERE id = ? AND interpretation_id = ?",
                winningPickId, version, losingPickId, interp.id());
        if ("SELECTED".equals(loser.status()) && !"SELECTED".equals(winner.status())) {
            jdbc.update("UPDATE pick SET status = 'SELECTED' WHERE id = ?", winningPickId);
        }
        Pick loserAfter = pick(interp.id(), losingPickId);
        Pick winnerAfter = pick(interp.id(), winningPickId);
        List<Map<String, Object>> undo = new ArrayList<>();
        undo.add(upsertPickOp(loserBefore, version));
        undo.add(upsertPickOp(winner, version));
        List<Map<String, Object>> redo = new ArrayList<>();
        redo.add(upsertPickOp(loserAfter, loserBefore.pickVersion()));
        redo.add(upsertPickOp(winnerAfter, winner.pickVersion()));
        recordEvent(interp.id(), "MERGE_CANDIDATES", undo, redo);
        return winnerAfter;
    }

    @Transactional
    public void setCorrection(String interpCode, String stationCode, double shiftSeconds) {
        Interpretation interp = get(interpCode);
        requireOpen(interp);
        Integer correctionStationVersion = interp.stationVersions().get(stationCode);
        if (correctionStationVersion == null) {
            throw new IllegalArgumentException("解释未钉住该台站版本: " + stationCode);
        }
        stations.getVersion(stationCode, correctionStationVersion);
        Double old = corrections(interp.id()).get(stationCode);
        int version = nextPickVersion(interp.id());
        if (old == null) {
            jdbc.update(
                    "INSERT INTO clock_correction(interpretation_id, station_code, shift_s, "
                            + "applied_at) VALUES (?,?,?,?)",
                    interp.id(), stationCode, shiftSeconds, Clock.now());
            Map<String, Object> redo = correctionOp("INSERT", interp.id(), stationCode,
                    shiftSeconds);
            recordEvent(interp.id(), "CLOCK_CORRECTION",
                    List.of(correctionDeleteOp(interp.id(), stationCode)), List.of(redo));
        } else {
            jdbc.update(
                    "UPDATE clock_correction SET shift_s = ?, applied_at = ? "
                            + "WHERE interpretation_id = ? AND station_code = ?",
                    shiftSeconds, Clock.now(), interp.id(), stationCode);
            recordEvent(interp.id(), "CLOCK_CORRECTION",
                    List.of(correctionOp("UPDATE", interp.id(), stationCode, old)),
                    List.of(correctionOp("UPDATE", interp.id(), stationCode, shiftSeconds)));
        }
    }

    @Transactional
    public Map<String, Object> undo(String interpCode) {
        Interpretation interp = get(interpCode);
        requireOpen(interp);
        Integer lastSeq = jdbc.queryForObject(
                "SELECT MAX(seq) FROM action_event WHERE interpretation_id = ?", Integer.class,
                interp.id());
        if (lastSeq == null || lastSeq == 0) {
            throw new IllegalStateException("没有可撤销的动作");
        }
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT type, undo_payload FROM action_event WHERE interpretation_id = ? AND seq = ?",
                interp.id(), lastSeq);
        applyPayload((String) event.get("undo_payload"));
        jdbc.update("DELETE FROM action_event WHERE interpretation_id = ? AND seq = ?",
                interp.id(), lastSeq);
        int restored = Math.max(0, interp.pickVersion() - 1);
        jdbc.update("UPDATE interpretation SET pick_version = ? WHERE id = ?", restored,
                interp.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("undoneType", (String) event.get("type"));
        result.put("pickVersion", restored);
        return result;
    }

    public List<Map<String, Object>> events(String interpCode) {
        Interpretation interp = get(interpCode);
        return jdbc.query(
                "SELECT seq, type, created_at FROM action_event WHERE interpretation_id = ? "
                        + "ORDER BY seq ASC",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", rs.getInt("seq"));
                    row.put("type", rs.getString("type"));
                    row.put("createdAt", rs.getString("created_at"));
                    return row;
                }, interp.id());
    }

    @SuppressWarnings("unchecked")
    void applyPayload(String json) {
        List<Map<String, Object>> ops = Json.read(json, new TypeReference<List<Map<String, Object>>>() {
        });
        for (Map<String, Object> op : ops) {
            String operation = (String) op.get("op");
            switch (operation) {
                case "UPSERT_PICK" -> upsertFromSnapshot((Map<String, Object>) op.get("pick"));
                case "DELETE_PICK" -> jdbc.update("DELETE FROM pick WHERE id = ?",
                        ((Number) op.get("pickId")).longValue());
                case "INSERT_CORRECTION" -> jdbc.update(
                        "INSERT INTO clock_correction(interpretation_id, station_code, shift_s, "
                                + "applied_at) VALUES (?,?,?,?)",
                        ((Number) op.get("interpretationId")).longValue(),
                        op.get("stationCode"), ((Number) op.get("shift")).doubleValue(),
                        Clock.now());
                case "UPDATE_CORRECTION" -> jdbc.update(
                        "UPDATE clock_correction SET shift_s = ?, applied_at = ? "
                                + "WHERE interpretation_id = ? AND station_code = ?",
                        ((Number) op.get("shift")).doubleValue(), Clock.now(),
                        ((Number) op.get("interpretationId")).longValue(),
                        op.get("stationCode"));
                case "DELETE_CORRECTION" -> jdbc.update(
                        "DELETE FROM clock_correction WHERE interpretation_id = ? AND station_code = ?",
                        ((Number) op.get("interpretationId")).longValue(),
                        op.get("stationCode"));
                default -> throw new IllegalStateException("未知事件操作: " + operation);
            }
        }
    }

    private void upsertFromSnapshot(Map<String, Object> snapshot) {
        long id = ((Number) snapshot.get("id")).longValue();
        Integer existing = jdbc.query("SELECT 1 FROM pick WHERE id = ?",
                rs -> rs.next() ? 1 : null, id);
        Object mergedInto = snapshot.get("mergedInto");
        if (existing == null) {
            jdbc.update(
                    "INSERT INTO pick(id, interpretation_id, station_code, phase, time, polarity, "
                            + "ci_half_width_s, status, merged_into, source, pick_version, "
                            + "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, ((Number) snapshot.get("interpretationId")).longValue(),
                    snapshot.get("stationCode"), snapshot.get("phase"),
                    ((Number) snapshot.get("time")).doubleValue(), snapshot.get("polarity"),
                    ((Number) snapshot.get("ciHalfWidth")).doubleValue(),
                    snapshot.get("status"),
                    mergedInto == null ? null : ((Number) mergedInto).longValue(),
                    snapshot.get("source"), ((Number) snapshot.get("pickVersion")).intValue(),
                    Clock.now());
        } else {
            jdbc.update(
                    "UPDATE pick SET interpretation_id = ?, station_code = ?, phase = ?, "
                            + "time = ?, polarity = ?, ci_half_width_s = ?, status = ?, "
                            + "merged_into = ?, source = ?, pick_version = ? WHERE id = ?",
                    ((Number) snapshot.get("interpretationId")).longValue(),
                    snapshot.get("stationCode"), snapshot.get("phase"),
                    ((Number) snapshot.get("time")).doubleValue(), snapshot.get("polarity"),
                    ((Number) snapshot.get("ciHalfWidth")).doubleValue(),
                    snapshot.get("status"),
                    mergedInto == null ? null : ((Number) mergedInto).longValue(),
                    snapshot.get("source"), ((Number) snapshot.get("pickVersion")).intValue(),
                    id);
        }
    }

    private Map<String, Object> upsertPickOp(Pick pick, int version) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", pick.id());
        snapshot.put("interpretationId", pick.interpretationId());
        snapshot.put("stationCode", pick.stationCode());
        snapshot.put("phase", pick.phase());
        snapshot.put("time", pick.time());
        snapshot.put("polarity", pick.polarity());
        snapshot.put("ciHalfWidth", pick.ciHalfWidth());
        snapshot.put("status", pick.status());
        snapshot.put("mergedInto", pick.mergedInto());
        snapshot.put("source", pick.source());
        snapshot.put("pickVersion", version);
        return Map.of("op", "UPSERT_PICK", "pick", snapshot);
    }

    private Map<String, Object> deletePickOp(long pickId) {
        return Map.of("op", "DELETE_PICK", "pickId", pickId);
    }

    private Map<String, Object> correctionDeleteOp(long interpretationId, String stationCode) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "DELETE_CORRECTION");
        op.put("interpretationId", interpretationId);
        op.put("stationCode", stationCode);
        return op;
    }

    private Map<String, Object> correctionOp(String kind, long interpretationId,
                                             String stationCode, double shift) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", kind.equals("INSERT") ? "INSERT_CORRECTION" : "UPDATE_CORRECTION");
        op.put("interpretationId", interpretationId);
        op.put("stationCode", stationCode);
        op.put("shift", shift);
        return op;
    }
}
