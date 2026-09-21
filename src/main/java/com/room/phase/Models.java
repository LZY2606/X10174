package com.room.phase;

import java.util.List;

public final class Models {
    private Models() {}

    public record Layer(double topDepthKm, double vpKmS, double vsKmS) {}
    public record EventRequest(String eventCode, long originTimeNs, double latitude, double longitude, double depthKm) {}
    public record StationRequest(String stationCode, String version, double latitude, double longitude,
                                 double elevationM, java.util.Map<String, Object> metadata) {}
    public record VelocityModelRequest(String modelCode, String version, List<Layer> layers) {}
    public record WaveformRequest(String clipCode, String stationCode, double sampleRateHz, long startTimeNs,
                                  List<Double> samples, List<List<Integer>> gaps, List<List<Integer>> overlaps,
                                  List<Integer> clippedSampleIndexes, String source) {}
    public record PickRequest(String pickCode, String stationCode, String phase, long timeNs, String polarity,
                              Long confidenceLowNs, Long confidenceHighNs, String source) {}
    public record BranchRequest(String branchCode, String name, String eventCode, Long modelId,
                                java.util.Map<String, Long> stationVersionIds) {}
    public record ActionRequest(String type, java.util.Map<String, Object> payload) {}
    public record CloneBranchRequest(String branchCode, String name, Long modelId) {}
    public record CompareRequest(List<Long> modelIds, Double timeWindowSec) {}
}
