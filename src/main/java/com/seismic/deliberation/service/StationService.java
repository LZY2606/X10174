package com.seismic.deliberation.service;

import com.seismic.deliberation.domain.Domain.StationRow;
import com.seismic.deliberation.domain.Domain.StationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Service
public class StationService {
    private final JdbcTemplate db;

    public StationService(JdbcTemplate db) {
        this.db = db;
    }

    public long createStation(String code, double lat, double lon, double elevationM) {
        long setVersion = newSetVersion();
        KeyHolder kh = new GeneratedKeyHolder();
        db.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO station(code) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        insertVersion(id, 1, setVersion, lat, lon, elevationM);
        return id;
    }

    public int updateStation(long stationId, double lat, double lon, double elevationM) {
        Integer next = db.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM station_version WHERE station_id = ?",
                Integer.class, stationId);
        long setVersion = newSetVersion();
        insertVersion(stationId, next, setVersion, lat, lon, elevationM);
        return next;
    }

    private void insertVersion(long stationId, int version, long setVersion,
                               double lat, double lon, double elevationM) {
        db.update("INSERT INTO station_version(station_id, version, set_version, lat, lon, elevation_m, created_at)"
                        + " VALUES (?,?,?,?,?,?,datetime('now'))",
                stationId, version, setVersion, lat, lon, elevationM);
    }

    private long newSetVersion() {
        KeyHolder kh = new GeneratedKeyHolder();
        db.update(con -> con.prepareStatement(
                "INSERT INTO station_set_version(created_at) VALUES (datetime('now'))",
                Statement.RETURN_GENERATED_KEYS), kh);
        return kh.getKey().longValue();
    }

    public long currentSetVersion() {
        Long v = db.queryForObject("SELECT COALESCE(MAX(id), 0) FROM station_set_version", Long.class);
        return v == null ? 0 : v;
    }

    public StationVersion versionAt(long stationId, long setVersion) {
        List<StationVersion> rows = db.query(
                "SELECT station_id, version, lat, lon, elevation_m FROM station_version"
                        + " WHERE station_id = ? AND set_version <= ?"
                        + " ORDER BY set_version DESC, version DESC LIMIT 1",
                (rs, i) -> new StationVersion(rs.getLong(1), rs.getInt(2),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)),
                stationId, setVersion);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StationVersion current(long stationId) {
        return versionAt(stationId, currentSetVersion());
    }

    public List<StationRow> stations() {
        return db.query("SELECT id, code FROM station ORDER BY id",
                (rs, i) -> new StationRow(rs.getLong(1), rs.getString(2)));
    }
}
