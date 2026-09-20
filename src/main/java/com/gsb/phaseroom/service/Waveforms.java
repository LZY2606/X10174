package com.gsb.phaseroom.service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Waveforms {

    public record Segment(long id, long trackId, String stationCode, String channel,
                          double startTime, double endTime, double sampleRateHz,
                          double clockOffsetS, boolean clipped, int receivedCount,
                          long contentId, String sha256, int sampleCount, double[] samples) {
    }

    public record Gap(double start, double end, String kind, long firstSegment,
                      long secondSegment) {
    }

    private final JdbcTemplate jdbc;

    public Waveforms(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> importSegment(String stationCode, String channel, double startTime,
                                             double sampleRateHz, List<Double> samples,
                                             double clockOffsetS, Boolean clipped,
                                             String sourceName) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("采样率必须为正");
        }
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("波形片段必须包含至少一个采样");
        }
        byte[] blob = Samples.encode(samples);
        String contentHash = sha256(blob);
        String now = Clock.now();

        Long contentId = jdbc.query("SELECT id FROM waveform_content WHERE sha256 = ?",
                rs -> rs.next() ? rs.getLong(1) : null, contentHash);
        boolean newContent = contentId == null;
        if (newContent) {
            jdbc.update(
                    "INSERT INTO waveform_content(sha256, sample_count, samples, created_at) "
                            + "VALUES (?,?,?,?)",
                    contentHash, samples.size(), blob, now);
            contentId = jdbc.queryForObject(
                    "SELECT id FROM waveform_content WHERE sha256 = ?", Long.class, contentHash);
        }

        Long trackId = jdbc.query(
                "SELECT id FROM waveform_track WHERE station_code = ? AND channel = ?",
                rs -> rs.next() ? rs.getLong(1) : null, stationCode, channel);
        Integer oldVersion = null;
        if (trackId == null) {
            jdbc.update(
                    "INSERT INTO waveform_track(station_code, channel, version, created_at) "
                            + "VALUES (?,?,1,?)",
                    stationCode, channel, now);
            trackId = jdbc.queryForObject(
                    "SELECT id FROM waveform_track WHERE station_code = ? AND channel = ?",
                    Long.class, stationCode, channel);
        } else {
            oldVersion = jdbc.queryForObject(
                    "SELECT version FROM waveform_track WHERE id = ?", Integer.class, trackId);
        }

        Long duplicateSegment = findDuplicate(trackId, contentId, startTime, sampleRateHz,
                clockOffsetS);
        boolean duplicated = duplicateSegment != null;
        long segmentId;
        boolean newTrackVersion;
        if (duplicated) {
            segmentId = duplicateSegment;
            jdbc.update(
                    "UPDATE waveform_segment SET received_count = received_count + 1, "
                            + "last_received_at = ? WHERE id = ?",
                    now, segmentId);
            newTrackVersion = false;
        } else {
            boolean clip = clipped != null ? clipped : detectClipping(samples);
            jdbc.update(
                    "INSERT INTO waveform_segment(track_id, content_id, start_time, "
                            + "sample_rate_hz, clock_offset_s, clipped, received_count, "
                            + "first_received_at, last_received_at) VALUES (?,?,?,?,?,?,1,?,?)",
                    trackId, contentId, startTime, sampleRateHz, clockOffsetS,
                    clip ? 1 : 0, now, now);
            segmentId = jdbc.queryForObject(
                    "SELECT id FROM waveform_segment WHERE rowid = last_insert_rowid()",
                    Long.class);
            newTrackVersion = true;
        }

        int trackVersion;
        if (newTrackVersion) {
            trackVersion = (oldVersion == null ? 0 : oldVersion) + 1;
            jdbc.update("UPDATE waveform_track SET version = ? WHERE id = ?", trackVersion,
                    trackId);
        } else {
            trackVersion = jdbc.queryForObject(
                    "SELECT version FROM waveform_track WHERE id = ?", Integer.class, trackId);
        }

        jdbc.update(
                "INSERT INTO import_receipt(content_id, source_name, received_at, "
                        + "duplicate_of_segment) VALUES (?,?,?,?)",
                contentId, sourceName == null ? "unknown" : sourceName, now,
                duplicated ? segmentId : null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segmentId", segmentId);
        result.put("contentId", contentId);
        result.put("sha256", contentHash);
        result.put("newContent", newContent);
        result.put("duplicated", duplicated);
        result.put("trackVersion", trackVersion);
        result.put("newTrackVersion", newTrackVersion);
        result.put("sourceName", sourceName == null ? "unknown" : sourceName);
        return result;
    }

    private Long findDuplicate(long trackId, long contentId, double startTime,
                               double sampleRateHz, double clockOffsetS) {
        List<Long> ids = jdbc.queryForList(
                "SELECT s.id FROM waveform_segment s JOIN waveform_content c ON s.content_id = c.id "
                        + "WHERE s.track_id = ? AND c.id = ? "
                        + "AND abs(s.start_time - ?) < 1e-9 AND abs(s.sample_rate_hz - ?) < 1e-9 "
                        + "AND abs(s.clock_offset_s - ?) < 1e-9 ORDER BY s.id ASC",
                Long.class, trackId, contentId, startTime, sampleRateHz, clockOffsetS);
        return ids.isEmpty() ? null : ids.get(0);
    }

    static boolean detectClipping(List<Double> samples) {
        if (samples.size() < 4) {
            return false;
        }
        double max = 0;
        for (double sample : samples) {
            max = Math.max(max, Math.abs(sample));
        }
        if (max == 0) {
            return false;
        }
        int flatAtMax = 0;
        int run = 0;
        double previous = Double.NaN;
        for (double sample : samples) {
            if (Math.abs(Math.abs(sample) - max) < 1e-12 * (1 + max)
                    && sample == previous) {
                run++;
                flatAtMax = Math.max(flatAtMax, run);
            } else {
                run = 1;
            }
            previous = sample;
        }
        return flatAtMax >= 3;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Segment> segmentsForTrack(String stationCode, String channel,
                                          boolean includeSamples) {
        return jdbc.query(
                "SELECT s.id, s.track_id, t.station_code, t.channel, s.start_time, "
                        + "s.sample_rate_hz, s.clock_offset_s, s.clipped, s.received_count, "
                        + "s.content_id, c.sha256, c.sample_count, c.samples "
                        + "FROM waveform_segment s JOIN waveform_track t ON s.track_id = t.id "
                        + "JOIN waveform_content c ON s.content_id = c.id "
                        + "WHERE t.station_code = ? AND t.channel = ? ORDER BY s.start_time ASC, s.id ASC",
                (rs, i) -> {
                    int count = rs.getInt("sample_count");
                    double rate = rs.getDouble("sample_rate_hz");
                    double start = rs.getDouble("start_time");
                    byte[] blob = rs.getBytes("samples");
                    double[] values = includeSamples ? Samples.decode(blob) : new double[0];
                    return new Segment(rs.getLong("id"), rs.getLong("track_id"),
                            rs.getString("station_code"), rs.getString("channel"), start,
                            start + (count - 1) / rate, rate, rs.getDouble("clock_offset_s"),
                            rs.getInt("clipped") == 1, rs.getInt("received_count"),
                            rs.getLong("content_id"), rs.getString("sha256"), count, values);
                },
                stationCode, channel);
    }

    public Map<String, Integer> activeTrackVersions() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        jdbc.query(
                "SELECT station_code, channel, version FROM waveform_track "
                        + "ORDER BY station_code ASC, channel ASC",
                rs -> {
                    versions.put(rs.getString("station_code") + "|" + rs.getString("channel"),
                            rs.getInt("version"));
                });
        return versions;
    }

    /**
     * Segments covering {@code timeSeconds} (nominal, uncorrected time base), ordered
     * deterministically by start time then id.
     */
    public List<Segment> covering(String stationCode, String channel, double timeSeconds) {
        return segmentsForTrack(stationCode, channel, false).stream()
                .filter(segment -> timeSeconds >= segment.startTime() - 1e-9
                        && timeSeconds <= segment.endTime() + 1e-9)
                .sorted(Comparator.comparingDouble(Segment::startTime)
                        .thenComparingLong(Segment::id))
                .toList();
    }

    /**
     * Assemble the ordered timeline: sorted segments plus deterministic gap/overlap spans.
     * A GAP is an uncovered interval between adjacent segments; an OVERLAP is a non-empty
     * intersection. Unequal rates are preserved per segment.
     */
    public Map<String, Object> timeline(String stationCode, String channel) {
        List<Segment> raw = segmentsForTrack(stationCode, channel, false);
        List<Segment> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingDouble(Segment::startTime).thenComparingLong(Segment::id));
        List<Gap> features = new ArrayList<>();
        for (int i = 0; i + 1 < sorted.size(); i++) {
            Segment a = sorted.get(i);
            Segment b = sorted.get(i + 1);
            double overlapStart = Math.max(a.startTime(), b.startTime());
            double overlapEnd = Math.min(a.endTime(), b.endTime());
            if (overlapEnd > overlapStart + 1e-9) {
                features.add(new Gap(overlapStart, overlapEnd, "OVERLAP", a.id(), b.id()));
            } else if (b.startTime() > a.endTime() + 1e-9) {
                features.add(new Gap(a.endTime(), b.startTime(), "GAP", a.id(), b.id()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stationCode", stationCode);
        out.put("channel", channel);
        out.put("segments", sorted);
        out.put("features", features);
        return out;
    }

    /** Effective clock offset (seconds) at a pick time; 0 when no covering segment exists. */
    public double clockOffsetAt(String stationCode, String channel, double timeSeconds) {
        List<Segment> cover = covering(stationCode, channel, timeSeconds);
        return cover.isEmpty() ? 0 : cover.get(0).clockOffsetS();
    }

    public boolean isClippedAt(String stationCode, String channel, double timeSeconds) {
        return covering(stationCode, channel, timeSeconds).stream()
                .anyMatch(Segment::clipped);
    }

    /** Distance from the pick time to the nearest uncovered gap boundary, in seconds. */
    public double gapProximity(String stationCode, String channel, double timeSeconds) {
        Object timelineObj = timeline(stationCode, channel).get("features");
        @SuppressWarnings("unchecked")
        List<Gap> gaps = (List<Gap>) timelineObj;
        double best = Double.POSITIVE_INFINITY;
        for (Gap gap : gaps) {
            if (!"GAP".equals(gap.kind())) {
                continue;
            }
            if (timeSeconds < gap.start()) {
                best = Math.min(best, gap.start() - timeSeconds);
            } else if (timeSeconds > gap.end()) {
                best = Math.min(best, timeSeconds - gap.end());
            } else {
                best = 0;
            }
        }
        return best;
    }

    public List<Map<String, Object>> tracks() {
        return jdbc.query(
                "SELECT id, station_code, channel, version FROM waveform_track "
                        + "ORDER BY station_code ASC, channel ASC",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("stationCode", rs.getString("station_code"));
                    row.put("channel", rs.getString("channel"));
                    row.put("version", rs.getInt("version"));
                    return row;
                });
    }

    public int receiptCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_receipt", Integer.class);
        return count == null ? 0 : count;
    }
}
