package com.gsb.phaseroom.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class Weights {

    private final Waveforms waveforms;

    public Weights(Waveforms waveforms) {
        this.waveforms = waveforms;
    }

    /**
     * Deterministic per-pick weight from provenance, confidence width, data quality and
     * correction provenance. All factors are independent and recorded in the result.
     */
    public Map<String, Object> explain(String stationCode, String channel, double pickTime,
                                       String source, double ciHalfWidth,
                                       double clockShiftSeconds) {
        Map<String, Object> factors = new LinkedHashMap<>();
        double sourceFactor = switch (source == null ? "" : source) {
            case "ANALYST" -> 1.0;
            case "ALGORITHM" -> 0.7;
            default -> 0.5;
        };
        factors.put("source", Map.of("value", source, "factor", round(sourceFactor)));

        double ciFactor;
        if (ciHalfWidth <= 0) {
            ciFactor = 0.8;
        } else if (ciHalfWidth <= 0.5) {
            ciFactor = 1.0;
        } else if (ciHalfWidth <= 2.0) {
            ciFactor = 0.7;
        } else {
            ciFactor = 0.4;
        }
        factors.put("confidence",
                Map.of("halfWidthSeconds", round(ciHalfWidth), "factor", round(ciFactor)));

        boolean clipped = waveforms.isClippedAt(stationCode, channel, pickTime);
        double clipFactor = clipped ? 0.6 : 1.0;
        factors.put("clipping", Map.of("clipped", clipped, "factor", round(clipFactor)));

        double gap = waveforms.gapProximity(stationCode, channel, pickTime);
        double gapFactor;
        if (!Double.isFinite(gap)) {
            gapFactor = 1.0;
        } else if (gap < 0.1) {
            gapFactor = 0.3;
        } else if (gap < 1.0) {
            gapFactor = 0.7;
        } else {
            gapFactor = 1.0;
        }
        Map<String, Object> gapDetail = new LinkedHashMap<>();
        gapDetail.put("distanceSeconds", Double.isFinite(gap) ? round(gap) : null);
        gapDetail.put("factor", round(gapFactor));
        factors.put("gapProximity", gapDetail);

        double correctionFactor = Math.abs(clockShiftSeconds) > 1e-12 ? 0.8 : 1.0;
        factors.put("clockCorrection",
                Map.of("shiftSeconds", round(clockShiftSeconds),
                        "factor", round(correctionFactor)));

        double weight = sourceFactor * ciFactor * clipFactor * gapFactor * correctionFactor;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weight", round(weight));
        out.put("factors", factors);
        return out;
    }

    static double round(double value) {
        return Math.rint(value * 1_000_000.0) / 1_000_000.0;
    }
}
