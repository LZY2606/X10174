package com.phase.room.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class StationRepo {
    private final JdbcTemplate jdbc;

    public StationRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rows.StationRow> STATION_MAPPER = (rs, n) -> new Rows.StationRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getInt("current_version"));

    private static final RowMapper<Rows.StationVersionRow> VERSION_MAPPER = (rs, n) ->
            new Rows.StationVersionRow(rs.getLong("station_id"), rs.getInt("version"),
                    rs.getDouble("lat"), rs.getDouble("lon"), rs.getDouble("elevation_m"),
                    rs.getDouble("clock_correction_ms"));

    public Rows.StationRow insert(String code, String name, double lat, double lon,
                                  double elevationM, double clockMs, double now) {
        jdbc.update("INSERT INTO station(code, name, current_version, created_at) VALUES(?,?,1,?)",
                code, name, now);
        Rows.StationRow station = findByCode(code);
        jdbc.update("INSERT INTO station_version(station_id, version, lat, lon, elevation_m,"
                        + " clock_correction_ms, created_at) VALUES(1,1,?,?,?,?,?)",
                station.id(), lat, lon, elevationM, clockMs, now);
        return station;
    }

    public Rows.StationRow findByCode(String code) {
        List<Rows.StationRow> list = jdbc.query("SELECT * FROM station WHERE code=?",
                STATION_MAPPER, code);
        return list.isEmpty() ? null : list.get(0);
    }

    public Rows.StationRow findById(long id) {
        List<Rows.StationRow> list = jdbc.query("SELECT * FROM station WHERE id=?",
                STATION_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Rows.StationRow> findAll() {
        return jdbc.query("SELECT * FROM station ORDER BY code", STATION_MAPPER);
    }

    public Rows.StationVersionRow findVersion(long stationId, int version) {
        List<Rows.StationVersionRow> list = jdbc.query(
                "SELECT * FROM station_version WHERE station_id=? AND version=?",
                VERSION_MAPPER, stationId, version);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Rows.StationVersionRow> findVersions(long stationId) {
        return jdbc.query("SELECT * FROM station_version WHERE station_id=? ORDER BY version",
                VERSION_MAPPER, stationId);
    }

    /** Appends a new immutable metadata version and bumps the current pointer. */
    public Rows.StationVersionRow addVersion(long stationId, double lat, double lon,
                                             double elevationM, double clockMs, double now) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM station_version WHERE station_id=?",
                Integer.class, stationId);
        int version = max + 1;
        jdbc.update("INSERT INTO station_version(station_id, version, lat, lon, elevation_m,"
                + " clock_correction_ms, created_at) VALUES(?,?,?,?,?,?,?)",
                stationId, version, lat, lon, elevationM, clockMs, now);
        jdbc.update("UPDATE station SET current_version=? WHERE id=?", version, stationId);
        return findVersion(stationId, version);
    }
}
