package com.gsb.phaseroom.service;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BranchService {

    private final JdbcTemplate jdbc;
    private final Interpretations interpretations;

    public BranchService(JdbcTemplate jdbc, Interpretations interpretations) {
        this.jdbc = jdbc;
        this.interpretations = interpretations;
    }

    /**
     * Copy a frozen (or any) interpretation into a new open branch, pinning the same
     * station/waveform versions but allowing a different velocity model. Picks, clock
     * corrections and action history are copied so undo/replay continues on the branch.
     */
    @Transactional
    public Interpretations.Interpretation branch(String sourceCode, String newCode,
                                                 String newName, Long replacementModelId) {
        Interpretations.Interpretation source = interpretations.get(sourceCode);
        long modelId = replacementModelId == null ? source.modelId() : replacementModelId;
        jdbc.queryForObject("SELECT id FROM velocity_model WHERE id = ?", Long.class, modelId);
        jdbc.update(
                "INSERT INTO interpretation(code, name, event_id, model_id, station_version_map, "
                        + "track_version_map, pick_version, frozen, parent_interpretation_id, "
                        + "created_at) VALUES (?,?,?,?,?,?,0,1,?,?)",
                newCode, newName == null ? source.name() + " 分支" : newName, source.eventId(),
                modelId, Json.write(source.stationVersions()), Json.write(source.trackVersions()),
                source.id(), Clock.now());
        long newId = jdbc.queryForObject(
                "SELECT id FROM interpretation WHERE code = ?", Long.class, newCode);
        jdbc.update("UPDATE interpretation SET frozen = 0 WHERE id = ?", newId);

        List<Interpretations.Pick> picks = jdbc.query(
                "SELECT id, interpretation_id, station_code, phase, time, polarity, "
                        + "ci_half_width_s, status, merged_into, source, pick_version FROM pick "
                        + "WHERE interpretation_id = ? ORDER BY id ASC",
                (rs, i) -> new Interpretations.Pick(rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getString(6),
                        rs.getDouble(7), rs.getString(8), rs.getObject(9) == null ? null : ((Number) rs.getObject(9)).longValue(),
                        rs.getString(10), rs.getInt(11)),
                source.id());
        java.util.Map<Long, Long> idMap = new java.util.HashMap<>();
        for (Interpretations.Pick pick : picks) {
            jdbc.update(
                    "INSERT INTO pick(interpretation_id, station_code, phase, time, polarity, "
                            + "ci_half_width_s, status, merged_into, source, pick_version, "
                            + "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    newId, pick.stationCode(), pick.phase(), pick.time(), pick.polarity(),
                    pick.ciHalfWidth(), pick.status(),
                    pick.mergedInto() == null ? null : -1L,
                    pick.source(), pick.pickVersion(), Clock.now());
            long copiedId = jdbc.queryForObject(
                    "SELECT MAX(id) FROM pick WHERE interpretation_id = ?", Long.class, newId);
            idMap.put(pick.id(), copiedId);
        }
        for (Interpretations.Pick pick : picks) {
            if (pick.mergedInto() != null) {
                jdbc.update("UPDATE pick SET merged_into = ? WHERE id = ?",
                        idMap.get(pick.mergedInto()), idMap.get(pick.id()));
            }
        }

        jdbc.query(
                "SELECT station_code, shift_s, applied_at FROM clock_correction "
                        + "WHERE interpretation_id = ? ORDER BY station_code ASC",
                rs -> {
                    jdbc.update(
                            "INSERT INTO clock_correction(interpretation_id, station_code, "
                                    + "shift_s, applied_at) VALUES (?,?,?,?)",
                            newId, rs.getString(1), rs.getDouble(2), rs.getString(3));
                }, source.id());

        jdbc.query(
                "SELECT seq, type, undo_payload, redo_payload, created_at FROM action_event "
                        + "WHERE interpretation_id = ? ORDER BY seq ASC",
                rs -> {
                    jdbc.update(
                            "INSERT INTO action_event(interpretation_id, seq, type, undo_payload, "
                                    + "redo_payload, created_at) VALUES (?,?,?,?,?,?)",
                            newId, rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5));
                }, source.id());

        jdbc.update("UPDATE interpretation SET pick_version = ? WHERE id = ?",
                source.pickVersion(), newId);
        return interpretations.get(newCode);
    }
}
