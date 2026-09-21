package com.phase.room.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SegmentRepo {
    private final JdbcTemplate jdbc;

    public SegmentRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Rows.SegmentRow> SEGMENT_MAPPER = (rs, n) ->
            new Rows.SegmentRow(rs.getLong("id"), rs.getLong("station_id"),
                    rs.getDouble("start_ms"), rs.getDouble("sample_rate_hz"),
                    rs.getInt("sample_count"), rs.getString("phase"),
                    rs.getString("content_hash"), rs.getInt("current_version"));

    private static final RowMapper<Rows.SegmentVersionRow> VERSION_MAPPER = (rs, n) ->
            new Rows.SegmentVersionRow(rs.getLong("segment_id"), rs.getInt("version"),
                    rs.getBytes("samples"), rs.getString("content_hash"),
                    rs.getInt("clipped") != 0);

    private static final RowMapper<Rows.ReceptionRow> RECEPTION_MAPPER = (rs, n) ->
            new Rows.ReceptionRow(rs.getLong("id"), rs.getLong("segment_id"),
                    rs.getString("content_hash"), rs.getDouble("received_at"),
                    rs.getString("source"));

    public Rows.SegmentRow findByKey(long stationId, double startMs, double rateHz) {
        List<Rows.SegmentRow> list = jdbc.query(
                "SELECT * FROM segment WHERE station_id=? AND start_ms=? AND sample_rate_hz=?",
                SEGMENT_MAPPER, stationId, startMs, rateHz);
        return list.isEmpty() ? null : list.get(0);
    }

    public Rows.SegmentRow insert(long stationId, double startMs, double rateHz, int sampleCount,
                                  String phase, String hash, byte[] samples, boolean clipped,
                                  double now) {
        jdbc.update("INSERT INTO segment(station_id, start_ms, sample_rate_hz, sample_count,"
                        + " phase, content_hash, current_version, created_at) VALUES(?,?,?,?,?,?,1,?)",
                stationId, startMs, rateHz, sampleCount, phase, hash, now);
        Rows.SegmentRow segment = findByKey(stationId, startMs, rateHz);
        jdbc.update("INSERT INTO segment_version(segment_id, version, samples, content_hash,"
                        + " clipped, created_at) VALUES(1,1,?,?,?,?)",
                segment.id(), samples, hash, clipped ? 1 : 0, now);
        return segment;
    }

    public Rows.SegmentRow findById(long id) {
        List<Rows.SegmentRow> list = jdbc.query("SELECT * FROM segment WHERE id=?",
                SEGMENT_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Rows.SegmentRow> findByStation(long stationId) {
        return jdbc.query("SELECT * FROM segment WHERE station_id=? ORDER BY start_ms, id",
                SEGMENT_MAPPER, stationId);
    }

    public Rows.SegmentVersionRow findCurrentVersion(long segmentId) {
        Integer version = jdbc.queryForObject("SELECT current_version FROM segment WHERE id=?",
                Integer.class, segmentId);
        return findVersion(segmentId, version);
    }

    public Rows.SegmentVersionRow findVersion(long segmentId, int version) {
        List<Rows.SegmentVersionRow> list = jdbc.query(
                "SELECT * FROM segment_version WHERE segment_id=? AND version=?",
                VERSION_MAPPER, segmentId, version);
        return list.isEmpty() ? null : list.get(0);
    }

    /** New waveform for the same time slot creates a new immutable version. */
    public Rows.SegmentVersionRow addVersion(long segmentId, int sampleCount, byte[] samples,
                                             String hash, boolean clipped, double now) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM segment_version WHERE segment_id=?",
                Integer.class, segmentId);
        int version = max + 1;
        jdbc.update("INSERT INTO segment_version(segment_id, version, samples, content_hash,"
                + " clipped, created_at) VALUES(?,?,?,?,?,?)",
                segmentId, version, samples, hash, clipped ? 1 : 0, now);
        jdbc.update("UPDATE segment SET current_version=?, sample_count=?, content_hash=? WHERE id=?",
                version, sampleCount, hash, segmentId);
        return findVersion(segmentId, version);
    }

    public void addReception(long segmentId, String hash, double now, String source) {
        jdbc.update("INSERT INTO segment_reception(segment_id, content_hash, received_at, source)"
                + " VALUES(?,?,?,?)", segmentId, hash, now, source);
    }

    public List<Rows.ReceptionRow> receptions(long segmentId) {
        return jdbc.query("SELECT * FROM segment_reception WHERE segment_id=? ORDER BY id",
                RECEPTION_MAPPER, segmentId);
    }
}
