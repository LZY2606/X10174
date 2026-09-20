package com.gsb.phaseroom.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Stations {

    public record Station(long id, String code, String name, double lat, double lon,
                          double elevationM, int version) {
    }

    private final JdbcTemplate jdbc;

    public Stations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Create the first station version or add a new immutable version for an existing code. */
    public Station put(String code, String name, double lat, double lon, double elevationM) {
        Integer current = jdbc.query(
                "SELECT MAX(version) FROM station WHERE code = ?",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, code);
        int version = current == null ? 1 : current + 1;
        if (current != null) {
            jdbc.update(
                    "UPDATE station SET superseded_at = ? WHERE code = ? AND superseded_at IS NULL",
                    Clock.now(), code);
        }
        jdbc.update(
                "INSERT INTO station(code, name, lat, lon, elevation_m, version, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                code, name, lat, lon, elevationM, version, Clock.now());
        return getVersion(code, version);
    }

    public Station active(String code) {
        List<Station> list = jdbc.query(
                "SELECT id, code, name, lat, lon, elevation_m, version FROM station "
                        + "WHERE code = ? AND superseded_at IS NULL",
                (rs, i) -> new Station(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getInt(7)),
                code);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("台站不存在: " + code);
        }
        return list.get(0);
    }

    public Station getVersion(String code, int version) {
        List<Station> list = jdbc.query(
                "SELECT id, code, name, lat, lon, elevation_m, version FROM station "
                        + "WHERE code = ? AND version = ?",
                (rs, i) -> new Station(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getInt(7)),
                code, version);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("台站版本不存在: " + code + "@v" + version);
        }
        return list.get(0);
    }

    public List<Station> listActive() {
        return jdbc.query(
                "SELECT id, code, name, lat, lon, elevation_m, version FROM station "
                        + "WHERE superseded_at IS NULL ORDER BY code ASC, id ASC",
                (rs, i) -> new Station(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getInt(7)));
    }
}
