package com.phase.room.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class InterpretationRepo {
    private final JdbcTemplate jdbc;

    public InterpretationRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rows.InterpretationRow> INTERP_MAPPER = (rs, n) -> {
        long parent = rs.getLong("parent_id");
        return new Rows.InterpretationRow(rs.getLong("id"), rs.getString("name"),
                rs.wasNull() ? null : parent);
    };

    private static final RowMapper<Rows.InterpretationVersionRow> VERSION_MAPPER = (rs, n) -> {
        long modelId = rs.getLong("model_id");
        int modelVersion = rs.getInt("model_version");
        return new Rows.InterpretationVersionRow(rs.getLong("id"),
                rs.getLong("interpretation_id"), rs.getInt("version"),
                rs.getInt("frozen") != 0, rs.getString("pick_versions_json"),
                rs.getString("station_versions_json"),
                rs.wasNull() ? null : modelId,
                rs.wasNull() ? null : modelVersion);
    };

    public Rows.InterpretationRow insert(String name, Long parentId, double now) {
        jdbc.update("INSERT INTO interpretation(name, parent_id, created_at) VALUES(?,?,?)",
                name, parentId, now);
        return findByName(name);
    }

    public Rows.InterpretationRow findByName(String name) {
        List<Rows.InterpretationRow> list = jdbc.query("SELECT * FROM interpretation WHERE name=?",
                INTERP_MAPPER, name);
        return list.isEmpty() ? null : list.get(0);
    }

    public Rows.InterpretationRow findById(long id) {
        List<Rows.InterpretationRow> list = jdbc.query("SELECT * FROM interpretation WHERE id=?",
                INTERP_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Rows.InterpretationRow> findAll() {
        return jdbc.query("SELECT * FROM interpretation ORDER BY id", INTERP_MAPPER);
    }

    public long addVersion(long interpretationId, String pickVersionsJson,
                           String stationVersionsJson, Long modelId, Integer modelVersion,
                           double now) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM interpretation_version"
                        + " WHERE interpretation_id=?",
                Integer.class, interpretationId);
        int version = max + 1;
        jdbc.update("INSERT INTO interpretation_version(interpretation_id, version, frozen,"
                        + " pick_versions_json, station_versions_json, model_id, model_version,"
                        + " created_at) VALUES(?,?,'0',?,?,?,?,?)",
                interpretationId, version, pickVersionsJson, stationVersionsJson, modelId,
                modelVersion, now);
        Long id = jdbc.queryForObject(
                "SELECT id FROM interpretation_version WHERE interpretation_id=? AND version=?",
                Long.class, interpretationId, version);
        return id;
    }

    public void freeze(long interpretationVersionId) {
        jdbc.update("UPDATE interpretation_version SET frozen='1' WHERE id=?",
                interpretationVersionId);
    }

    public Rows.InterpretationVersionRow findVersionById(long id) {
        List<Rows.InterpretationVersionRow> list = jdbc.query(
                "SELECT * FROM interpretation_version WHERE id=?", VERSION_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Rows.InterpretationVersionRow> versions(long interpretationId) {
        return jdbc.query("SELECT * FROM interpretation_version WHERE interpretation_id=?"
                        + " ORDER BY version", VERSION_MAPPER, interpretationId);
    }

    public void addPick(long interpretationVersionId, long candidateId) {
        jdbc.update("INSERT OR IGNORE INTO interpretation_pick(interpretation_version_id,"
                + " candidate_id) VALUES(?,?)", interpretationVersionId, candidateId);
    }

    public List<Long> pickIds(long interpretationVersionId) {
        return jdbc.queryForList("SELECT candidate_id FROM interpretation_pick"
                        + " WHERE interpretation_version_id=? ORDER BY candidate_id",
                Long.class, interpretationVersionId);
    }
}
