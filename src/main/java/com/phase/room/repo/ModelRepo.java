package com.phase.room.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ModelRepo {
    private final JdbcTemplate jdbc;

    public ModelRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rows.ModelRow> MODEL_MAPPER = (rs, n) -> new Rows.ModelRow(
            rs.getLong("id"), rs.getString("name"), rs.getInt("current_version"));

    private static final RowMapper<Rows.ModelVersionRow> VERSION_MAPPER = (rs, n) ->
            new Rows.ModelVersionRow(rs.getLong("model_id"), rs.getInt("version"),
                    rs.getString("layers_json"));

    public Rows.ModelRow insert(String name, String layersJson, double now) {
        jdbc.update("INSERT INTO velocity_model(name, current_version, created_at) VALUES(?,1,?)",
                name, now);
        Rows.ModelRow model = findByName(name);
        jdbc.update("INSERT INTO velocity_model_version(model_id, version, layers_json, created_at)"
                + " VALUES(?,1,?,?)", model.id(), layersJson, now);
        return model;
    }

    public Rows.ModelRow findByName(String name) {
        List<Rows.ModelRow> list = jdbc.query("SELECT * FROM velocity_model WHERE name=?",
                MODEL_MAPPER, name);
        return list.isEmpty() ? null : list.get(0);
    }

    public Rows.ModelRow findById(long id) {
        List<Rows.ModelRow> list = jdbc.query("SELECT * FROM velocity_model WHERE id=?",
                MODEL_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Rows.ModelRow> findAll() {
        return jdbc.query("SELECT * FROM velocity_model ORDER BY name", MODEL_MAPPER);
    }

    public Rows.ModelVersionRow findVersion(long modelId, int version) {
        List<Rows.ModelVersionRow> list = jdbc.query(
                "SELECT * FROM velocity_model_version WHERE model_id=? AND version=?",
                VERSION_MAPPER, modelId, version);
        return list.isEmpty() ? null : list.get(0);
    }

    public Rows.ModelVersionRow addVersion(long modelId, String layersJson, double now) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM velocity_model_version WHERE model_id=?",
                Integer.class, modelId);
        int version = max + 1;
        jdbc.update("INSERT INTO velocity_model_version(model_id, version, layers_json, created_at)"
                + " VALUES(?,?,?,?)", modelId, version, layersJson, now);
        jdbc.update("UPDATE velocity_model SET current_version=? WHERE id=?", version, modelId);
        return findVersion(modelId, version);
    }
}
