package com.phase.room.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class EventRepo {
    private final JdbcTemplate jdbc;

    public EventRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rows.EventRow> MAPPER = (rs, n) -> new Rows.EventRow(
            rs.getLong("id"), rs.getString("code"), rs.getDouble("origin_time"),
            rs.getDouble("lat"), rs.getDouble("lon"), rs.getDouble("depth_m"));

    public Rows.EventRow insert(String code, double originTime, double lat, double lon,
                                double depthM, double now) {
        jdbc.update("INSERT INTO ev(code, origin_time, lat, lon, depth_m, created_at) VALUES(?,?,?,?,?,?)",
                code, originTime, lat, lon, depthM, now);
        return findByCode(code);
    }

    public Rows.EventRow findByCode(String code) {
        List<Rows.EventRow> list = jdbc.query("SELECT * FROM ev WHERE code=?", MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    public Rows.EventRow findById(long id) {
        List<Rows.EventRow> list = jdbc.query("SELECT * FROM ev WHERE id=?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Rows.EventRow> findAll() {
        return jdbc.query("SELECT * FROM ev ORDER BY id", MAPPER);
    }
}
