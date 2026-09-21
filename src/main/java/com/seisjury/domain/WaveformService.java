package com.seisjury.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seisjury.db.Repositories;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaveformService {

    public record UploadResult(long segmentId, String segKey, int version, String contentSha256,
                               boolean deduplicated, long receptionCount, boolean clipped) {
    }

    public record Coverage(String segKey, String stationCode, String channel, int version,
                           long startMs, long endMs, long durationMs, double sampleRateHz,
                           boolean clipped) {
    }

    public record Gap(String channel, long startMs, long endMs, long durationMs, String kind) {
    }

    private final Repositories repo;
    private final Clock clock;
    private final ObjectMapper mapper;

    public WaveformService(Repositories repo, Clock clock, ObjectMapper mapper) {
        this.repo = repo;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional
    public UploadResult upload(String stationCode, String channel, String fileTag, long startMs,
                               double sampleRateHz, List<Double> samples, String source,
                               Double clockOffsetMs) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel is required");
        }
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive");
        }
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        String segKey = stationCode + "|" + channel + "|" + (fileTag == null ? "" : fileTag);
        String normalized = normalizeSamples(samples);
        String sha = sha256(segKey + "\n" + startMs + "\n" + sampleRateHz + "\n" + normalized);
        Repositories.SegmentRow existing = repo.segmentByContent(sha);
        long now = clock.nowMs();
        double offset = clockOffsetMs == null ? 0.0d : clockOffsetMs;
        boolean clipped = detectClipping(samples, sampleRateHz);
        String sampleText = mapper.createObjectNode().put("csv", normalized).toString();
        long segmentId;
        int version;
        boolean deduplicated;
        if (existing != null) {
            segmentId = existing.id();
            version = existing.version();
            deduplicated = true;
            repo.logReception(segKey, sha, segmentId, null, source(source), now);
        } else {
            segmentId = repo.insertSegment(stationCode, channel, segKey, startMs, sampleRateHz,
                    samples.size(), sampleText, sha, source(source), offset, clipped, fileTag, now);
            version = repo.segmentAtVersion(segKey, null).version();
            repo.logReception(segKey, sha, segmentId, null, source(source), now);
            deduplicated = false;
        }
        return new UploadResult(segmentId, segKey, version, sha, deduplicated,
                repo.receptionCount(sha), clipped);
    }

    /** Coverage gaps and overlaps for the newest version of each segment of a station. */
    public List<Gap> coverageGaps(String stationCode, Long gapToleranceMs) {
        Map<String, List<Coverage>> byChannel = new LinkedHashMap<>();
        for (Repositories.SegmentRow row : repo.segmentsLatest()) {
            if (!row.stationCode().equals(stationCode)) {
                continue;
            }
            long endMs = row.startMs()
                    + Math.round((row.sampleCount() - 1) * 1000.0d / row.sampleRateHz());
            Coverage coverage = new Coverage(row.segKey(), stationCode, row.channel(), row.version(),
                    row.startMs(), endMs, endMs - row.startMs(), row.sampleRateHz(), row.clipped());
            byChannel.computeIfAbsent(row.channel(), k -> new ArrayList<>()).add(coverage);
        }
        long tolerance = gapToleranceMs == null ? 1L : gapToleranceMs;
        List<Gap> gaps = new ArrayList<>();
        for (Map.Entry<String, List<Coverage>> entry : byChannel.entrySet()) {
            List<Coverage> list = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(Coverage::startMs)
                            .thenComparingInt(Coverage::version))
                    .toList();
            for (int i = 0; i + 1 < list.size(); i++) {
                Coverage a = list.get(i);
                Coverage b = list.get(i + 1);
                long delta = b.startMs() - a.endMs();
                if (delta > tolerance) {
                    gaps.add(new Gap(entry.getKey(), a.endMs(), b.startMs(), delta, "GAP"));
                } else if (delta < -tolerance) {
                    gaps.add(new Gap(entry.getKey(), b.startMs(), a.endMs(), -delta, "OVERLAP"));
                }
            }
        }
        gaps.sort(Comparator.comparing(Gap::channel).thenComparingLong(Gap::startMs));
        return gaps;
    }

    public static String normalizeSamples(List<Double> samples) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            double value = samples.get(i);
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                sb.append(Long.toString((long) value));
            } else {
                sb.append(Double.toString(value));
            }
        }
        return sb.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean detectClipping(List<Double> samples, double rate) {
        if (samples.size() < 3) {
            return false;
        }
        double max = samples.stream().mapToDouble(Math::abs).max().orElse(0.0d);
        if (max == 0.0d) {
            return false;
        }
        int plateau = 1;
        int best = 1;
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).equals(samples.get(i - 1))
                    && Math.abs(samples.get(i)) >= 0.98d * max) {
                plateau++;
                best = Math.max(best, plateau);
            } else {
                plateau = 1;
            }
        }
        double plateauMs = best * 1000.0d / rate;
        return plateauMs >= 1.0d;
    }

    private static String source(String source) {
        return source == null || source.isBlank() ? "UNKNOWN" : source;
    }
}
