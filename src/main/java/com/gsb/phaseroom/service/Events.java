package com.gsb.phaseroom.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Events {

    public record SeismicEvent(long id, String code, double originTime, double lat, double lon,
                               double depthKm) {
    }

    private final JdbcTemplate jdbc;

    public Events(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SeismicEvent create(String code, double originTime, double lat, double lon,
                               double depthKm) {
        if (depthKm < 0) {
            throw new IllegalArgumentException("震源深度不能为负");
        }
        jdbc.update(
                "INSERT INTO seismic_event(code, origin_time, lat, lon, depth_km, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                code, originTime, lat, lon, depthKm, Clock.now());
        return get(code);
    }

    public SeismicEvent get(String code) {
        List<SeismicEvent> list = jdbc.query(
                "SELECT id, code, origin_time, lat, lon, depth_km FROM seismic_event WHERE code = ?",
                (rs, i) -> new SeismicEvent(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)),
                code);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("事件不存在: " + code);
        }
        return list.get(0);
    }

    public SeismicEvent byId(long id) {
        List<SeismicEvent> list = jdbc.query(
                "SELECT id, code, origin_time, lat, lon, depth_km FROM seismic_event WHERE id = ?",
                (rs, i) -> new SeismicEvent(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)),
                id);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("事件不存在: " + id);
        }
        return list.get(0);
    }

    public List<SeismicEvent> list() {
        return jdbc.query(
                "SELECT id, code, origin_time, lat, lon, depth_km FROM seismic_event "
                        + "ORDER BY code ASC, id ASC",
                (rs, i) -> new SeismicEvent(rs.getLong(1), rs.getString(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)));
    }
}
