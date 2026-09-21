package com.room.phase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CatalogService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> createEvent(Models.EventRequest request) {
        requireText(request.eventCode(), "eventCode");
        if (request.originTimeNs() < 0) throw new IllegalArgumentException("originTimeNs must be non-negative");
        String createdAt = Instant.now().toString();
        jdbc.update("INSERT INTO events(event_code, origin_time_ns, latitude, longitude, depth_km, created_at) VALUES (?,?,?,?,?,?)",
                request.eventCode(), request.originTimeNs(), request.latitude(), request.longitude(), request.depthKm(), createdAt);
        return eventByCode(request.eventCode());
    }

    public Map<String, Object> eventByCode(String code) {
        return jdbc.queryForMap("SELECT * FROM events WHERE event_code=?", code);
    }

    @Transactional
    public Map<String, Object> saveStation(Models.StationRequest request) {
        requireText(request.stationCode(), "stationCode");
        requireText(request.version(), "version");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("stationCode", request.stationCode());
        content.put("latitude", request.latitude());
        content.put("longitude", request.longitude());
        content.put("elevationM", request.elevationM());
        content.put("metadata", request.metadata() == null ? Map.of() : request.metadata());
        String hash = Hashing.sha256(content, mapper);
        Map<String, Object> existing = findOne("SELECT * FROM station_versions WHERE station_code=? AND content_hash=?", request.stationCode(), hash);
        if (existing != null) return existing;
        try {
            jdbc.update("INSERT INTO station_versions(station_code,version,latitude,longitude,elevation_m,metadata_json,content_hash,created_at) VALUES (?,?,?,?,?,?,?,?)",
                    request.stationCode(), request.version(), request.latitude(), request.longitude(), request.elevationM(),
                    write(request.metadata() == null ? Map.of() : request.metadata()), hash, Instant.now().toString());
        } catch (RuntimeException ex) {
            if (exists("SELECT 1 FROM station_versions WHERE station_code=? AND version=?", request.stationCode(), request.version())) {
                throw new ApiException(409, "station version already exists with different content");
            }
            throw ex;
        }
        return jdbc.queryForMap("SELECT * FROM station_versions WHERE station_code=? AND content_hash=?", request.stationCode(), hash);
    }

    @Transactional
    public Map<String, Object> saveVelocityModel(Models.VelocityModelRequest request) {
        requireText(request.modelCode(), "modelCode");
        requireText(request.version(), "version");
        if (request.layers() == null || request.layers().isEmpty()) throw new IllegalArgumentException("layers are required");
        double previous = -1;
        for (Models.Layer layer : request.layers()) {
            if (layer.vpKmS() <= 0 || layer.vsKmS() <= 0) throw new IllegalArgumentException("layer velocities must be positive");
            if (layer.topDepthKm() < previous) throw new IllegalArgumentException("layers must be ordered by topDepthKm");
            previous = layer.topDepthKm();
        }
        String layersJson = write(request.layers());
        String hash = Hashing.sha256(Map.of("modelCode", request.modelCode(), "layers", request.layers()), mapper);
        Map<String, Object> existing = findOne("SELECT * FROM velocity_models WHERE model_code=? AND content_hash=?", request.modelCode(), hash);
        if (existing != null) return existing;
        try {
            jdbc.update("INSERT INTO velocity_models(model_code,version,layers_json,content_hash,created_at) VALUES (?,?,?,?,?)",
                    request.modelCode(), request.version(), layersJson, hash, Instant.now().toString());
        } catch (RuntimeException ex) {
            if (exists("SELECT 1 FROM velocity_models WHERE model_code=? AND version=?", request.modelCode(), request.version())) {
                throw new ApiException(409, "model version already exists with different content");
            }
            throw ex;
        }
        return jdbc.queryForMap("SELECT * FROM velocity_models WHERE model_code=? AND content_hash=?", request.modelCode(), hash);
    }

    @Transactional
    public synchronized Map<String, Object> importWaveform(Models.WaveformRequest request) {
        requireText(request.clipCode(), "clipCode");
        requireText(request.stationCode(), "stationCode");
        if (request.sampleRateHz() <= 0) throw new IllegalArgumentException("sampleRateHz must be positive");
        if (request.samples() == null) throw new IllegalArgumentException("samples are required");
        double min = request.samples().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = request.samples().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Map<String, Object> content = waveformContent(request);
        String hash = Hashing.sha256(content, mapper);
        jdbc.update("INSERT OR IGNORE INTO waveform_clips(clip_code, station_code, created_at) VALUES (?,?,?)",
                request.clipCode(), request.stationCode(), Instant.now().toString());
        long clipId = jdbc.queryForObject("SELECT id FROM waveform_clips WHERE clip_code=?", Long.class, request.clipCode());
        Map<String, Object> version = findOne("SELECT * FROM waveform_versions WHERE clip_id=? AND content_hash=?", clipId, hash);
        boolean duplicate = version != null;
        long versionId;
        if (duplicate) {
            versionId = ((Number) version.get("id")).longValue();
        } else {
            Integer maxVersion = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM waveform_versions WHERE clip_id=?", Integer.class, clipId);
            versionId = jdbc.queryForObject("""
                    INSERT INTO waveform_versions(clip_id,version,sample_rate_hz,start_time_ns,sample_count,samples_json,gaps_json,overlaps_json,clipped_json,min_value,max_value,content_hash,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                    """, Long.class, clipId, maxVersion + 1, request.sampleRateHz(), request.startTimeNs(), request.samples().size(),
                    write(request.samples()), write(request.gaps() == null ? List.of() : request.gaps()),
                    write(request.overlaps() == null ? List.of() : request.overlaps()),
                    write(request.clippedSampleIndexes() == null ? List.of() : request.clippedSampleIndexes()),
                    min, max, hash, Instant.now().toString());
            jdbc.update("UPDATE waveform_clips SET latest_version_id=? WHERE id=?", versionId, clipId);
        }
        jdbc.update("INSERT INTO waveform_receptions(clip_id,waveform_version_id,source,received_at,duplicate) VALUES (?,?,?,?,?)",
                clipId, versionId, request.source() == null ? "unknown" : request.source(), Instant.now().toString(), duplicate ? 1 : 0);
        int receptionCount = jdbc.queryForObject("SELECT COUNT(*) FROM waveform_receptions WHERE clip_id=? AND waveform_version_id=?",
                Integer.class, clipId, versionId);
        Map<String, Object> response = new LinkedHashMap<>(findOne("SELECT * FROM waveform_versions WHERE id=?", versionId));
        response.put("clipId", clipId);
        response.put("clipCode", request.clipCode());
        response.put("stationCode", request.stationCode());
        response.put("duplicate", duplicate);
        response.put("receptionCount", receptionCount);
        return response;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Map<String, Object> requiredStationVersion(long id) {
        Map<String, Object> row = findOne("SELECT * FROM station_versions WHERE id=?", id);
        if (row == null) throw new ApiException(404, "station version not found: " + id);
        return row;
    }

    public Map<String, Object> requiredModel(long id) {
        Map<String, Object> row = findOne("SELECT * FROM velocity_models WHERE id=?", id);
        if (row == null) throw new ApiException(404, "velocity model not found: " + id);
        return row;
    }

    public List<Models.Layer> layers(Map<String, Object> model) {
        return read(model.get("layers_json").toString(), new TypeReference<List<Models.Layer>>() {});
    }

    private Map<String, Object> waveformContent(Models.WaveformRequest request) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("sampleRateHz", request.sampleRateHz());
        content.put("startTimeNs", request.startTimeNs());
        content.put("samples", request.samples());
        content.put("gaps", request.gaps() == null ? List.of() : request.gaps());
        content.put("overlaps", request.overlaps() == null ? List.of() : request.overlaps());
        content.put("clippedSampleIndexes", request.clippedSampleIndexes() == null ? List.of() : request.clippedSampleIndexes());
        return content;
    }

    Map<String, Object> findOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    boolean exists(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args) != null;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
