package com.phase.room.service;

import com.phase.room.domain.Waveform;
import com.phase.room.repo.Rows;
import com.phase.room.repo.SegmentRepo;
import com.phase.room.repo.StationRepo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SegmentService {
    private final SegmentRepo segmentRepo;
    private final StationRepo stationRepo;

    public SegmentService(SegmentRepo segmentRepo, StationRepo stationRepo) {
        this.segmentRepo = segmentRepo;
        this.stationRepo = stationRepo;
    }

    public record ImportResult(long segmentId, int version, boolean deduplicated,
                               boolean changed, int receptions, boolean clipped,
                               int sampleCount) {
    }

    /**
     * Content-addressed import: identical bytes at the same slot are deduplicated
     * but still count a reception; changed bytes create a new segment version.
     */
    public synchronized ImportResult importSegment(long stationId, double startMs,
                                                   double sampleRateHz, String phase,
                                                   float[] samples, String source) {
        if (stationRepo.findById(stationId) == null) {
            throw new IllegalArgumentException("台站不存在: " + stationId);
        }
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("采样率必须为正");
        }
        if (samples.length == 0) {
            throw new IllegalArgumentException("波形样本不能为空");
        }
        byte[] bytes = Waveform.encode(samples);
        String hash = Waveform.sha256(bytes);
        boolean clipped = Waveform.detectClipping(samples);
        double now = System.currentTimeMillis();

        Rows.SegmentRow existing = segmentRepo.findByKey(stationId, startMs, sampleRateHz);
        if (existing == null) {
            Rows.SegmentRow created = segmentRepo.insert(stationId, startMs, sampleRateHz,
                    samples.length, phase, hash, bytes, clipped, now);
            segmentRepo.addReception(created.id(), hash, now, source);
            return new ImportResult(created.id(), 1, false, false, 1, clipped,
                    samples.length);
        }
        boolean changed = !existing.contentHash().equals(hash);
        int version = existing.currentVersion();
        if (changed) {
            Rows.SegmentVersionRow newVersion = segmentRepo.addVersion(existing.id(),
                    samples.length, bytes, hash, clipped, now);
            version = newVersion.version();
        }
        segmentRepo.addReception(existing.id(), hash, now, source);
        int receptions = segmentRepo.receptions(existing.id()).size();
        return new ImportResult(existing.id(), version, !changed, changed,
                receptions, clipped, samples.length);
    }

    public record CoverageGap(double startMs, double endMs, double gapMs, boolean overlap) {
    }

    /**
     * Gap/overlap boundaries between segments of one station. Boundary tolerance
     * is half a sample of the shorter-rate neighbour so unequal rates stay stable.
     */
    public List<CoverageGap> coverage(long stationId) {
        List<Rows.SegmentRow> segments = segmentRepo.findByStation(stationId);
        List<CoverageGap> result = new ArrayList<>();
        for (int i = 0; i + 1 < segments.size(); i++) {
            Rows.SegmentRow a = segments.get(i);
            Rows.SegmentRow b = segments.get(i + 1);
            double endA = a.startMs() + a.sampleCount() * 1000.0 / a.sampleRateHz();
            double delta = b.startMs() - endA;
            double tolerance = 0.5 * 1000.0 / Math.min(a.sampleRateHz(), b.sampleRateHz());
            if (delta > tolerance) {
                result.add(new CoverageGap(endA, b.startMs(), delta, false));
            } else if (delta < -tolerance) {
                result.add(new CoverageGap(b.startMs(), endA, -delta, true));
            }
        }
        return result;
    }

    public float[] samples(long segmentId, Integer version) {
        Rows.SegmentVersionRow row = version == null
                ? segmentRepo.findCurrentVersion(segmentId)
                : segmentRepo.findVersion(segmentId, version);
        if (row == null) {
            throw new IllegalArgumentException("片段版本不存在");
        }
        return Waveform.decode(row.samples());
    }
}
