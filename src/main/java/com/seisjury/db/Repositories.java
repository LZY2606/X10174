package com.seisjury.db;

import com.seisjury.domain.TravelTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class Repositories {

    private final JdbcTemplate jdbc;
    private final Seq seq;

    public Repositories(JdbcTemplate jdbc, Seq seq) {
        this.jdbc = jdbc;
        this.seq = seq;
    }

    public record EventRow(long id, String eventCode, long originMs,
                           double latitude, double longitude, double depthKm) {
    }

    public record StationRow(long id, String stationCode, int version, long globalVersion,
                             double latitude, double longitude, double elevationM, long createdMs) {
    }

    public record ModelRow(long id, String modelCode, int version, long createdMs) {
    }

    public record LayerRow(long id, long modelId, int layerOrder,
                           double topDepthKm, double bottomDepthKm, double vp, double vs) {
    }

    public record SegmentRow(long id, String stationCode, String channel, String segKey, int version,
                             long startMs, double sampleRateHz, int sampleCount, String samples,
                             String contentSha256, String source, double clockOffsetMs,
                             boolean clipped, String fileTag, long createdMs) {
    }

    public record InterpRow(long id, String interpCode, String eventCode, Long velocityModelId,
                            Long headSeq, Integer stationPinVersion, Long frozenMs,
                            long createdMs, Long parentInterpId) {
    }

    public record PickEventRow(long seq, long interpId, String eventType,
                               String payload, Long undoOfSeq, long createdMs) {
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Seq seq() {
        return seq;
    }

    // ---------- events ----------

    public void insertEvent(String code, long originMs, double lat, double lon, double depth) {
        long id = seq.next("event");
        jdbc.update("INSERT INTO seismic_event(id, event_code, origin_ms, latitude, longitude, depth_km) "
                + "VALUES(?,?,?,?,?,?)", id, code, originMs, lat, lon, depth);
    }

    public EventRow findEvent(String code) {
        List<EventRow> rows = jdbc.query("SELECT * FROM seismic_event WHERE event_code = ?",
                Repositories::mapEvent, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<EventRow> listEvents() {
        return jdbc.query("SELECT * FROM seismic_event ORDER BY id", Repositories::mapEvent);
    }

    private static EventRow mapEvent(ResultSet rs, int i) throws SQLException {
        return new EventRow(rs.getLong("id"), rs.getString("event_code"),
                rs.getLong("origin_ms"), rs.getDouble("latitude"),
                rs.getDouble("longitude"), rs.getDouble("depth_km"));
    }

    // ---------- stations ----------

    public long insertStation(String code, double lat, double lon, double elevationM, long nowMs) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM station WHERE station_code = ?",
                Integer.class, code);
        long id = seq.next("station");
        long globalVersion = seq.next("station_global");
        jdbc.update("INSERT INTO station(id, station_code, version, global_version, latitude, longitude, elevation_m, created_ms) "
                + "VALUES(?,?,?,?,?,?,?,?)", id, code, version, globalVersion, lat, lon, elevationM, nowMs);
        return id;
    }

    public long latestStationGlobalVersion() {
        Long v = jdbc.queryForObject("SELECT COALESCE(MAX(global_version), 0) FROM station", Long.class);
        return v == null ? 0L : v;
    }

    /** Station rows as they were at a frozen global pin. */
    public List<StationRow> stationsAtGlobalPin(long pinVersion) {
        List<String> codes = jdbc.query(
                "SELECT station_code FROM station GROUP BY station_code ORDER BY station_code",
                (rs, i) -> rs.getString(1));
        return codes.stream().map(code -> stationAtGlobalPin(code, pinVersion)).toList();
    }

    public StationRow stationAtGlobalPin(String code, long pinVersion) {
        List<StationRow> rows = jdbc.query(
                "SELECT * FROM station WHERE station_code = ? AND global_version <= ? "
                        + "ORDER BY global_version DESC LIMIT 1",
                Repositories::mapStation, code, pinVersion);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return stationAtVersion(code, null);
    }

    public StationRow stationAtVersion(String code, Integer version) {
        if (version == null) {
            List<StationRow> rows = jdbc.query(
                    "SELECT * FROM station WHERE station_code = ? ORDER BY version DESC LIMIT 1",
                    Repositories::mapStation, code);
            return rows.isEmpty() ? null : rows.get(0);
        }
        List<StationRow> rows = jdbc.query(
                "SELECT * FROM station WHERE station_code = ? AND version = ?",
                Repositories::mapStation, code, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<StationRow> stationsLatest() {
        return jdbc.query("""
                SELECT s.* FROM station s
                JOIN (SELECT station_code, MAX(version) mv FROM station GROUP BY station_code) m
                  ON s.station_code = m.station_code AND s.version = m.mv
                ORDER BY s.station_code
                """, Repositories::mapStation);
    }

    private static StationRow mapStation(ResultSet rs, int i) throws SQLException {
        return new StationRow(rs.getLong("id"), rs.getString("station_code"),
                rs.getInt("version"), rs.getLong("global_version"),
                rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getDouble("elevation_m"), rs.getLong("created_ms"));
    }

    // ---------- velocity models ----------

    public long insertModel(String code, List<double[]> layers, long nowMs) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM velocity_model WHERE model_code = ?",
                Integer.class, code);
        long id = seq.next("model");
        jdbc.update("INSERT INTO velocity_model(id, model_code, version, created_ms) VALUES(?,?,?,?)",
                id, code, version, nowMs);
        int order = 0;
        for (double[] layer : layers) {
            jdbc.update("INSERT INTO model_layer(id, model_id, layer_order, top_depth_km, bottom_depth_km, vp_kms, vs_kms) "
                    + "VALUES(?,?,?,?,?,?,?)", seq.next("layer"), id, order++, layer[0], layer[1], layer[2], layer[3]);
        }
        return id;
    }

    public ModelRow model(String code, Integer version) {
        if (version == null) {
            List<ModelRow> rows = jdbc.query(
                    "SELECT * FROM velocity_model WHERE model_code = ? ORDER BY version DESC LIMIT 1",
                    Repositories::mapModel, code);
            return rows.isEmpty() ? null : rows.get(0);
        }
        List<ModelRow> rows = jdbc.query(
                "SELECT * FROM velocity_model WHERE model_code = ? AND version = ?",
                Repositories::mapModel, code, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ModelRow> modelsLatest() {
        return jdbc.query("""
                SELECT v.* FROM velocity_model v
                JOIN (SELECT model_code, MAX(version) mv FROM velocity_model GROUP BY model_code) m
                  ON v.model_code = m.model_code AND v.version = m.mv
                ORDER BY v.model_code
                """, Repositories::mapModel);
    }

    public List<TravelTime.Layer> layers(long modelId) {
        return jdbc.query(
                "SELECT * FROM model_layer WHERE model_id = ? ORDER BY layer_order",
                (RowMapper<TravelTime.Layer>) (rs, i) -> new TravelTime.Layer(
                        rs.getDouble("top_depth_km"),
                        rs.getDouble("bottom_depth_km"),
                        rs.getDouble("vp_kms"), rs.getDouble("vs_kms")), modelId);
    }

    private static ModelRow mapModel(ResultSet rs, int i) throws SQLException {
        return new ModelRow(rs.getLong("id"), rs.getString("model_code"),
                rs.getInt("version"), rs.getLong("created_ms"));
    }

    // ---------- waveforms ----------

    public SegmentRow segmentByContent(String sha) {
        List<SegmentRow> rows = jdbc.query(
                "SELECT * FROM waveform_segment WHERE content_sha256 = ? ORDER BY id LIMIT 1",
                Repositories::mapSegment, sha);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SegmentRow segmentAtVersion(String segKey, Integer version) {
        if (version == null) {
            List<SegmentRow> rows = jdbc.query(
                    "SELECT * FROM waveform_segment WHERE seg_key = ? ORDER BY version DESC LIMIT 1",
                    Repositories::mapSegment, segKey);
            return rows.isEmpty() ? null : rows.get(0);
        }
        List<SegmentRow> rows = jdbc.query(
                "SELECT * FROM waveform_segment WHERE seg_key = ? AND version = ?",
                Repositories::mapSegment, segKey, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SegmentRow> segmentsForStation(String stationCode) {
        return jdbc.query(
                "SELECT * FROM waveform_segment WHERE station_code = ? ORDER BY channel, version",
                Repositories::mapSegment, stationCode);
    }

    public List<SegmentRow> segmentsLatest() {
        return jdbc.query("""
                SELECT w.* FROM waveform_segment w
                JOIN (SELECT seg_key, MAX(version) mv FROM waveform_segment GROUP BY seg_key) m
                  ON w.seg_key = m.seg_key AND w.version = m.mv
                ORDER BY w.station_code, w.channel
                """, Repositories::mapSegment);
    }

    public long insertSegment(String stationCode, String channel, String segKey, long startMs,
                              double rate, int sampleCount, String samples, String sha,
                              String source, double clockOffsetMs, boolean clipped,
                              String fileTag, long nowMs) {
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM waveform_segment WHERE seg_key = ?",
                Integer.class, segKey);
        long id = seq.next("segment");
        jdbc.update("INSERT INTO waveform_segment(id, station_code, channel, seg_key, version, start_ms, "
                + "sample_rate_hz, sample_count, samples, content_sha256, source, clock_offset_ms, "
                + "clipped, file_tag, created_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, stationCode, channel, segKey, version, startMs, rate, sampleCount, samples, sha,
                source, clockOffsetMs, clipped ? 1 : 0, fileTag, nowMs);
        return id;
    }

    public void logReception(String segKey, String sha, Long segmentId, Long duplicateOf,
                             String source, long nowMs) {
        jdbc.update("INSERT INTO reception_log(id, seg_key, content_sha256, segment_id, duplicate_of, "
                + "source, received_ms) VALUES(?,?,?,?,?,?,?)",
                seq.next("reception"), segKey, sha, segmentId, duplicateOf, source, nowMs);
    }

    public int receptionCount(String sha) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reception_log WHERE content_sha256 = ?", Integer.class, sha);
        return n == null ? 0 : n;
    }

    private static SegmentRow mapSegment(ResultSet rs, int i) throws SQLException {
        return new SegmentRow(rs.getLong("id"), rs.getString("station_code"),
                rs.getString("channel"), rs.getString("seg_key"), rs.getInt("version"),
                rs.getLong("start_ms"), rs.getDouble("sample_rate_hz"),
                rs.getInt("sample_count"), rs.getString("samples"),
                rs.getString("content_sha256"), rs.getString("source"),
                rs.getDouble("clock_offset_ms"), rs.getInt("clipped") == 1,
                rs.getString("file_tag"), rs.getLong("created_ms"));
    }

    // ---------- interpretations ----------

    public long insertInterp(String code, String eventCode, Long velocityModelId,
                             Long parentId, long nowMs) {
        long id = seq.next("interp");
        jdbc.update("INSERT INTO interpretation(id, interp_code, event_code, velocity_model_id, "
                + "head_seq, station_pin_version, frozen_ms, created_ms, parent_interp_id) "
                + "VALUES(?,?,?,?,NULL,NULL,NULL,?,?)",
                id, code, eventCode, velocityModelId, nowMs, parentId);
        return id;
    }

    public InterpRow interp(String code) {
        List<InterpRow> rows = jdbc.query("SELECT * FROM interpretation WHERE interp_code = ?",
                Repositories::mapInterp, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public InterpRow interpById(long id) {
        List<InterpRow> rows = jdbc.query("SELECT * FROM interpretation WHERE id = ?",
                Repositories::mapInterp, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<InterpRow> listInterps() {
        return jdbc.query("SELECT * FROM interpretation ORDER BY id", Repositories::mapInterp);
    }

    public void setInterpModel(long interpId, long modelId) {
        jdbc.update("UPDATE interpretation SET velocity_model_id = ? WHERE id = ?", modelId, interpId);
    }

    public void freezeInterp(long interpId, long headSeq, long pinVersion, long nowMs) {
        jdbc.update("UPDATE interpretation SET head_seq = ?, station_pin_version = ?, frozen_ms = ? "
                + "WHERE id = ?", headSeq, pinVersion, nowMs, interpId);
    }

    private static InterpRow mapInterp(ResultSet rs, int i) throws SQLException {
        return new InterpRow(rs.getLong("id"), rs.getString("interp_code"),
                rs.getString("event_code"),
                (Long) rs.getObject("velocity_model_id"),
                (Long) rs.getObject("head_seq"),
                (Integer) rs.getObject("station_pin_version"),
                (Long) rs.getObject("frozen_ms"),
                rs.getLong("created_ms"),
                (Long) rs.getObject("parent_interp_id"));
    }

    public void insertPin(long interpId, String stationCode, int version) {
        jdbc.update("INSERT INTO interp_station_pin(id, interp_id, station_code, version) VALUES(?,?,?,?)",
                seq.next("pin"), interpId, stationCode, version);
    }

    public List<String[]> pins(long interpId) {
        return jdbc.query("SELECT station_code, version FROM interp_station_pin "
                + "WHERE interp_id = ? ORDER BY station_code", (rs, i) ->
                new String[] {rs.getString(1), rs.getString(2)});
    }

    // ---------- pick event stream ----------

    public long appendPickEvent(long interpId, String type, String payload, Long undoOf, long nowMs) {
        long sequence = seq.next("pick_seq");
        jdbc.update("INSERT INTO pick_event(seq, interp_id, event_type, payload, undo_of_seq, created_ms) "
                + "VALUES(?,?,?,?,?,?)", sequence, interpId, type, payload, undoOf, nowMs);
        return sequence;
    }

    public List<PickEventRow> events(long interpId) {
        return jdbc.query("SELECT * FROM pick_event WHERE interp_id = ? ORDER BY seq",
                (rs, i) -> new PickEventRow(rs.getLong("seq"), rs.getLong("interp_id"),
                        rs.getString("event_type"), rs.getString("payload"),
                        (Long) rs.getObject("undo_of_seq"), rs.getLong("created_ms")), interpId);
    }

    public void recordUndo(long interpId, long forwardSeq, long inverseSeq, long nowMs) {
        jdbc.update("INSERT INTO undo_entry(id, interp_id, forward_seq, inverse_seq, created_ms) "
                + "VALUES(?,?,?,?,?)", seq.next("undo"), interpId, forwardSeq, inverseSeq, nowMs);
    }

    public List<long[]> undoEntries(long interpId) {
        return jdbc.query("SELECT forward_seq, inverse_seq, created_ms FROM undo_entry "
                + "WHERE interp_id = ? ORDER BY id", (rs, i) ->
                new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}, interpId);
    }

    public void insertUndoRaw(long interpId, long forwardSeq, long inverseSeq, long nowMs) {
        jdbc.update("INSERT INTO undo_entry(id, interp_id, forward_seq, inverse_seq, created_ms) "
                + "VALUES(?,?,?,?,?)", seq.next("undo"), interpId, forwardSeq, inverseSeq, nowMs);
    }

    public long[] lastUndo(long interpId) {
        List<Long> rows = jdbc.query(
                "SELECT forward_seq FROM undo_entry WHERE interp_id = ? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), interpId);
        return rows.isEmpty() ? null : new long[] {rows.get(0)};
    }

    public void deleteUndo(long interpId, long forwardSeq) {
        jdbc.update("DELETE FROM undo_entry WHERE interp_id = ? AND forward_seq = ?",
                interpId, forwardSeq);
    }
}
