package com.seisjury.web;

import com.seisjury.db.Repositories;
import com.seisjury.domain.CatalogService;
import com.seisjury.domain.WaveformService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;
    private final Repositories repo;
    private final WaveformService waveforms;

    public CatalogController(CatalogService catalog, Repositories repo, WaveformService waveforms) {
        this.catalog = catalog;
        this.repo = repo;
        this.waveforms = waveforms;
    }

    public record EventRequest(String code, Long originMs, Double latitude,
                               Double longitude, Double depthKm) {
    }

    public record StationRequest(String code, Double latitude, Double longitude,
                                 Double elevationM) {
    }

    public record ModelRequest(String code, List<double[]> layers) {
    }

    public record WaveformRequest(String stationCode, String channel, String fileTag,
                                  Long startMs, Double sampleRateHz, List<Double> samples,
                                  String source, Double clockOffsetMs) {
    }

    @PostMapping("/events")
    public Map<String, Object> createEvent(@RequestBody EventRequest request) {
        require(request.code, "code");
        requireNumber(request.originMs, "originMs");
        requireNumber(request.latitude, "latitude");
        requireNumber(request.longitude, "longitude");
        requireNumber(request.depthKm, "depthKm");
        catalog.createEvent(request.code, request.originMs, request.latitude,
                request.longitude, request.depthKm);
        return Map.of("eventCode", request.code);
    }

    @GetMapping("/events")
    public List<Repositories.EventRow> events() {
        return repo.listEvents();
    }

    @PostMapping("/stations")
    public Repositories.StationRow addStation(@RequestBody StationRequest request) {
        require(request.code, "code");
        requireNumber(request.latitude, "latitude");
        requireNumber(request.longitude, "longitude");
        requireNumber(request.elevationM, "elevationM");
        return catalog.addStationVersion(request.code, request.latitude, request.longitude,
                request.elevationM);
    }

    @GetMapping("/stations")
    public List<Repositories.StationRow> stations() {
        return repo.stationsLatest();
    }

    @PostMapping("/velocity-models")
    public Repositories.ModelRow addModel(@RequestBody ModelRequest request) {
        require(request.code, "code");
        return catalog.addModelVersion(request.code, request.layers);
    }

    @GetMapping("/velocity-models")
    public List<Repositories.ModelRow> models() {
        return repo.modelsLatest();
    }

    @PostMapping("/waveforms")
    public WaveformService.UploadResult upload(@RequestBody WaveformRequest request) {
        return waveforms.upload(request.stationCode, request.channel, request.fileTag,
                request.startMs, request.sampleRateHz, request.samples, request.source,
                request.clockOffsetMs);
    }

    @GetMapping("/waveforms")
    public List<Repositories.SegmentRow> waveformList() {
        return repo.segmentsLatest();
    }

    @GetMapping("/waveforms/coverage")
    public List<WaveformService.Gap> coverage(@RequestParam String stationCode,
                                              @RequestParam(required = false) Long gapToleranceMs) {
        return waveforms.coverageGaps(stationCode, gapToleranceMs);
    }

    @GetMapping("/receptions")
    public List<Map<String, Object>> receptions(@RequestParam(required = false) String segKey) {
        String sql = "SELECT r.id, r.seg_key, r.content_sha256, r.segment_id, r.duplicate_of, "
                + "r.source, r.received_ms FROM reception_log r";
        if (segKey != null) {
            return repo.jdbc().query(sql + " WHERE seg_key = ? ORDER BY r.id",
                    (rs, i) -> Map.<String, Object>of(
                            "id", rs.getLong(1),
                            "segKey", rs.getString(2),
                            "contentSha256", rs.getString(3),
                            "segmentId", rs.getLong(4),
                            "duplicateOf", rs.getObject(5) == null ? 0L : rs.getLong(5),
                            "source", rs.getString(6),
                            "receivedMs", rs.getLong(7)), segKey);
        }
        return repo.jdbc().query(sql + " ORDER BY r.id", (rs, i) -> Map.<String, Object>of(
                "id", rs.getLong(1),
                "segKey", rs.getString(2),
                "contentSha256", rs.getString(3),
                "segmentId", rs.getLong(4),
                "duplicateOf", rs.getObject(5) == null ? 0L : rs.getLong(5),
                "source", rs.getString(6),
                "receivedMs", rs.getLong(7)));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
    }

    private static void requireNumber(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " required");
        }
    }
}
