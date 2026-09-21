package com.seismic.deliberation.domain;

import java.util.List;

public final class Domain {
    private Domain() {}

    public record StationRow(long id, String code) {}

    public record StationVersion(long stationId, int version, double lat, double lon, double elevationM) {}

    public record Segment(long id, long stationId, String contentHash, int version,
                          double sampleRate, long startMs, float[] samples) {}

    public record ModelVersion(long id, long modelId, int version, double pVelocity, double sVelocity) {}

    public record EventRow(long id, String name, long originEpochMs, double lat, double lon, double depthKm) {}

    public record Branch(long id, long eventId, String name, Long parentId, long modelVersionId,
                         long pickVersion, boolean frozen, Long frozenStationSet,
                         Long frozenModelVersionId, Long frozenPickVersion) {}

    public record PickRow(long id, long branchId, long stationId, String phase, long epochMs,
                          String polarity, Long ciLowMs, Long ciHighMs, double confidence,
                          String status, String source, int version, Long mergedInto) {}

    public record Residual(long stationId, String phase, long pickEpochMs, long theoreticalMs,
                           long residualMs, boolean inWindow, double weight, String weightSource) {}

    public record StationComparison(long stationId, double predictedDiffMs,
                                    Long residualMs1, Long residualMs2,
                                    boolean inWindow1, boolean inWindow2,
                                    double weight, String weightSource) {}

    public record ComparisonResult(List<StationComparison> perStation,
                                   double meanAbs1, double meanAbs2,
                                   int outOfWindow1, int outOfWindow2, String verdict) {}

    public record Trace(double sampleRate, long startMs, float[] samples, boolean[] gap, boolean[] clipped) {}
}
