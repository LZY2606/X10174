package com.phase.room.web;

import java.util.List;
import java.util.Map;

public final class Requests {
    private Requests() {
    }

    public record EventRequest(String code, double originTimeMs, double lat, double lon,
                               double depthM) {
    }

    public record StationRequest(String code, String name, double lat, double lon,
                                 double elevationM, double clockCorrectionMs) {
    }

    public record StationUpdate(double lat, double lon, double elevationM) {
    }

    public record ClockRequest(double clockCorrectionMs) {
    }

    public record ModelRequest(String name, List<LayerRequest> layers) {
    }

    public record ModelVersionRequest(List<LayerRequest> layers) {
    }

    public record LayerRequest(double topDepthM, double vpMps, double vsMps) {
    }

    public record SegmentRequest(long stationId, double startMs, double sampleRateHz,
                                 String phase, List<Double> samples, String source) {
    }

    public record AddPickRequest(long eventId, long stationId, String phase, Long segmentId,
                                 double arrivalMs, double ciLowMs, double ciHighMs,
                                 String polarity, Double weight, String weightSource) {
    }

    public record StationPickRequest(long stationId) {
    }

    public record MovePickRequest(long eventId, long stationId, double arrivalMs,
                                 double ciLowMs, double ciHighMs) {
    }

    public record PolarityRequest(long eventId, long stationId, String polarity) {
    }

    public record CiRequest(double ciLowMs, double ciHighMs) {
    }

    public record WeightRequest(double weight, String weightSource) {
    }

    public record MergeRequest(List<Long> candidateIds) {
    }

    public record PinStationRequest(long stationId, int stationVersion, int pickSeq) {
    }

    public record CreateInterpretationRequest(long eventId, String name,
                                              List<PinStationRequest> stations, Long modelId,
                                              Integer modelVersion) {
    }

    public record BranchRequest(long eventId, String name, Long modelId, Integer modelVersion) {
    }

    public record CompareRequest(long eventId, List<long[]> models, double windowMs,
                                 List<PinStationRequest> stations) {
    }
}
