package com.room.phase;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PickService {
    private final JdbcTemplate jdbc;

    public PickService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createPick(String eventCode, Models.PickRequest request) {
        Long eventId = jdbc.queryForObject("SELECT id FROM events WHERE event_code=?", Long.class, eventCode);
        if (eventId == null) throw new ApiException(404, "event not found");
        validatePhase(request.phase());
        String polarity = normalizePolarity(request.polarity());
        long low = request.confidenceLowNs() == null ? request.timeNs() : request.confidenceLowNs();
        long high = request.confidenceHighNs() == null ? request.timeNs() : request.confidenceHighNs();
        validateInterval(low, request.timeNs(), high);
        long id = insertPick(eventId, request.pickCode(), 1, request.stationCode(), request.phase(), request.timeNs(),
                polarity, low, high, "CANDIDATE", null, request.source(), null);
        return jdbc.queryForMap("SELECT * FROM pick_versions WHERE id=?", id);
    }

    public Map<String, Object> requiredPickVersion(long id) {
        Map<String, Object> row = jdbc.queryForList("SELECT * FROM pick_versions WHERE id=?", id).stream().findFirst().orElse(null);
        if (row == null) throw new ApiException(404, "pick version not found: " + id);
        return row;
    }

    long nextVersion(String pickCode) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM pick_versions WHERE pick_code=?", Integer.class, pickCode);
        return (max == null ? 0 : max) + 1;
    }

    long insertPick(long eventId, String pickCode, long version, String stationCode, String phase, long timeNs,
                    String polarity, long low, long high, String status, String mergedIntoCode,
                    String source, Map<String, Object> extra) {
        CatalogService.requireText(pickCode, "pickCode");
        CatalogService.requireText(stationCode, "stationCode");
        validatePhase(phase);
        validateInterval(low, timeNs, high);
        if (!"CANDIDATE".equals(status) && !"MERGED".equals(status) && !"NOISE".equals(status)) {
            throw new IllegalArgumentException("invalid pick status");
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("pickCode", pickCode);
        content.put("stationCode", stationCode);
        content.put("phase", phase);
        content.put("timeNs", timeNs);
        content.put("polarity", polarity);
        content.put("confidenceLowNs", low);
        content.put("confidenceHighNs", high);
        content.put("status", status);
        content.put("mergedIntoCode", mergedIntoCode);
        if (extra != null) content.put("sourceContext", extra);
        String hash = Hashing.sha256(content, new com.fasterxml.jackson.databind.ObjectMapper());
        return jdbc.queryForObject("""
                INSERT INTO pick_versions(pick_code,version,event_id,station_code,phase,time_ns,polarity,confidence_low_ns,confidence_high_ns,status,source,merged_into_code,content_hash,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, pickCode, version, eventId, stationCode, phase, timeNs, polarity, low, high, status,
                source == null ? "manual" : source, mergedIntoCode, hash, Instant.now().toString());
    }

    Map<String, Object> currentPick(long branchId, String pickCode) {
        return jdbc.queryForList("""
                SELECT pv.* FROM branch_picks bp JOIN pick_versions pv ON pv.id=bp.current_pick_version_id
                WHERE bp.branch_id=? AND bp.pick_code=?
                """, branchId, pickCode).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "pick not attached to branch: " + pickCode));
    }

    long eventIdOfBranch(long branchId) {
        return jdbc.queryForObject("SELECT event_id FROM interpretation_branches WHERE id=?", Long.class, branchId);
    }

    static void validatePhase(String phase) {
        if (!"P".equals(phase) && !"S".equals(phase)) throw new IllegalArgumentException("phase must be P or S");
    }

    static String normalizePolarity(String polarity) {
        if (polarity == null || polarity.isBlank()) return "NEUTRAL";
        if (!"POSITIVE".equals(polarity) && !"NEGATIVE".equals(polarity) && !"NEUTRAL".equals(polarity)) {
            throw new IllegalArgumentException("polarity must be POSITIVE, NEGATIVE, or NEUTRAL");
        }
        return polarity;
    }

    static void validateInterval(long low, long time, long high) {
        if (low > time || high < time) throw new IllegalArgumentException("time must be inside confidence interval");
        if (low > high) throw new IllegalArgumentException("confidence interval is reversed");
    }
}
