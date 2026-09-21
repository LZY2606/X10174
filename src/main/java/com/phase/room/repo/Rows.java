package com.phase.room.repo;

import java.util.List;

public final class Rows {
    private Rows() {
    }

    public record EventRow(long id, String code, double originTime, double lat, double lon,
                           double depthM) {
    }

    public record StationRow(long id, String code, String name, int currentVersion) {
    }

    public record StationVersionRow(long stationId, int version, double lat, double lon,
                                    double elevationM, double clockCorrectionMs) {
    }

    public record ModelRow(long id, String name, int currentVersion) {
    }

    public record ModelVersionRow(long modelId, int version, String layersJson) {
    }

    public record SegmentRow(long id, long stationId, double startMs, double sampleRateHz,
                             int sampleCount, String phase, String contentHash,
                             int currentVersion) {
    }

    public record SegmentVersionRow(long segmentId, int version, byte[] samples, String contentHash,
                                    boolean clipped) {
    }

    public record ReceptionRow(long id, long segmentId, String contentHash, double receivedAt,
                               String source) {
    }

    public record PickEventRow(long id, long eventId, long stationId, int seq, String type,
                               String payloadJson, String inverseJson, Long undoOf) {
    }

    public record InterpretationRow(long id, String name, Long parentId) {
    }

    public record InterpretationVersionRow(long id, long interpretationId, int version,
                                           boolean frozen, String pickVersionsJson,
                                           String stationVersionsJson, Long modelId,
                                           Integer modelVersion) {
    }

    public record VersionPick(long candidateId, int seq) {
    }

    public record CandidateVersion(long interpretationVersionId, long candidateId) {
    }

    public record EventStationKey(long eventId, long stationId) {
    }

    public record PickVersionMap(List<VersionPick> picks, List<Long> stations) {
    }
}
