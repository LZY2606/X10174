package com.phase.room.web;

import com.phase.room.domain.Candidate;
import com.phase.room.service.CompareService;
import com.phase.room.service.InterpretationService;
import com.phase.room.service.ModelService;
import com.phase.room.service.PickCommandService;
import com.phase.room.service.PickService;
import com.phase.room.service.SegmentService;
import com.phase.room.service.StationService;
import com.phase.room.domain.Json;
import com.phase.room.domain.Layer;
import com.phase.room.repo.EventRepo;
import com.phase.room.repo.InterpretationRepo;
import com.phase.room.repo.ModelRepo;
import com.phase.room.repo.PickEventRepo;
import com.phase.room.repo.Rows;
import com.phase.room.repo.SegmentRepo;
import com.phase.room.repo.StationRepo;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final EventRepo eventRepo;
    private final StationRepo stationRepo;
    private final ModelRepo modelRepo;
    private final SegmentRepo segmentRepo;
    private final PickEventRepo pickEventRepo;
    private final InterpretationRepo interpretationRepo;
    private final ModelService modelService;
    private final SegmentService segmentService;
    private final StationService stationService;
    private final PickService pickService;
    private final PickCommandService pickCommandService;
    private final CompareService compareService;
    private final InterpretationService interpretationService;

    public ApiController(EventRepo eventRepo, StationRepo stationRepo, ModelRepo modelRepo,
                         SegmentRepo segmentRepo, PickEventRepo pickEventRepo,
                         InterpretationRepo interpretationRepo, ModelService modelService,
                         SegmentService segmentService, StationService stationService,
                         PickService pickService, PickCommandService pickCommandService,
                         CompareService compareService,
                         InterpretationService interpretationService) {
        this.eventRepo = eventRepo;
        this.stationRepo = stationRepo;
        this.modelRepo = modelRepo;
        this.segmentRepo = segmentRepo;
        this.pickEventRepo = pickEventRepo;
        this.interpretationRepo = interpretationRepo;
        this.modelService = modelService;
        this.segmentService = segmentService;
        this.stationService = stationService;
        this.pickService = pickService;
        this.pickCommandService = pickCommandService;
        this.compareService = compareService;
        this.interpretationService = interpretationService;
    }

    // ---------- catalog ----------

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", eventRepo.findAll());
        body.put("stations", stationRepo.findAll());
        body.put("models", modelRepo.findAll());
        body.put("interpretations", interpretationRepo.findAll());
        return body;
    }

    @PostMapping("/events")
    public Rows.EventRow createEvent(@RequestBody Requests.EventRequest request) {
        requireText(request.code(), "事件编码");
        if (eventRepo.findByCode(request.code()) != null) {
            throw new IllegalArgumentException("事件编码已存在");
        }
        return eventRepo.insert(request.code(), request.originTimeMs(), request.lat(),
                request.lon(), request.depthM(), System.currentTimeMillis());
    }

    @PostMapping("/stations")
    public Rows.StationRow createStation(@RequestBody Requests.StationRequest request) {
        requireText(request.code(), "台站编码");
        if (stationRepo.findByCode(request.code()) != null) {
            throw new IllegalArgumentException("台站编码已存在");
        }
        return stationRepo.insert(request.code(), request.name(), request.lat(), request.lon(),
                request.elevationM(), request.clockCorrectionMs(), System.currentTimeMillis());
    }

    @GetMapping("/stations/{id}/versions")
    public List<Rows.StationVersionRow> stationVersions(@PathVariable long id) {
        return stationRepo.findVersions(id);
    }

    @PutMapping("/stations/{id}/metadata")
    public Rows.StationVersionRow updateMetadata(@PathVariable long id,
                                                 @RequestBody Requests.StationUpdate request) {
        return stationService.updateMetadata(id, request.lat(), request.lon(),
                request.elevation());
    }

    @PutMapping("/stations/{id}/clock")
    public Rows.StationVersionRow setClock(@PathVariable long id,
                                           @RequestBody Requests.ClockRequest request) {
        return stationService.setClockCorrection(id, request.clockCorrectionMs());
    }

    @PostMapping("/models")
    public Rows.ModelRow createModel(@RequestBody Requests.ModelRequest request) {
        requireText(request.name(), "模型名称");
        if (modelRepo.findByName(request.name()) != null) {
            throw new IllegalArgumentException("模型名称已存在");
        }
        String layersJson = Json.write(toLayers(request.layers()));
        return modelRepo.insert(request.name(), layersJson, System.currentTimeMillis());
    }

    @PostMapping("/models/{id}/versions")
    public Rows.ModelVersionRow addModelVersion(@PathVariable long id,
                                                @RequestBody Requests.ModelVersionRequest request) {
        if (modelRepo.findById(id) == null) {
            throw new IllegalArgumentException("模型不存在");
        }
        String layersJson = Json.write(toLayers(request.layers()));
        return modelRepo.addVersion(id, layersJson, System.currentTimeMillis());
    }

    @GetMapping("/models/{id}/versions/{version}")
    public Map<String, Object> modelVersion(@PathVariable long id, @PathVariable int version) {
        Rows.ModelVersionRow row = modelRepo.findVersion(id, version);
        if (row == null) {
            throw new IllegalArgumentException("模型版本不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelId", row.modelId());
        body.put("version", row.version());
        body.put("layers", modelService.parseLayers(row.layersJson()));
        return body;
    }

    // ---------- waveforms ----------

    @PostMapping("/segments")
    public Map<String, Object> importSegment(@RequestBody Requests.SegmentRequest request) {
        float[] samples = new float[request.samples().size()];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = request.samples().get(i).floatValue();
        }
        SegmentService.ImportResult result = segmentService.importSegment(request.stationId(),
                request.startMs(), request.sampleRateHz(), request.phase(), samples,
                request.source() == null ? "manual" : request.source());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("segmentId", result.segmentId());
        body.put("version", result.version());
        body.put("deduplicated", result.deduplicated());
        body.put("changed", result.changed());
        body.put("receptions", result.receptions());
        body.put("clipped", result.clipped());
        body.put("sampleCount", result.sampleCount());
        return body;
    }

    @GetMapping("/stations/{id}/segments")
    public List<Map<String, Object>> stationSegments(@PathVariable long id) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Rows.SegmentRow segment : segmentRepo.findByStation(id)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", segment.id());
            body.put("stationId", segment.stationId());
            body.put("startMs", segment.startMs());
            body.put("sampleRateHz", segment.sampleRateHz());
            body.put("sampleCount", segment.sampleCount());
            body.put("phase", segment.phase());
            body.put("contentHash", segment.contentHash());
            body.put("currentVersion", segment.currentVersion());
            body.put("endMs", segment.startMs()
                    + segment.sampleCount() * 1000.0 / segment.sampleRateHz());
            body.put("receptions", segmentRepo.receptions(segment.id()).size());
            result.add(body);
        }
        return result;
    }

    @GetMapping("/stations/{id}/coverage")
    public List<SegmentService.CoverageGap> coverage(@PathVariable long id) {
        return segmentService.coverage(id);
    }

    @GetMapping("/segments/{id}")
    public Map<String, Object> segmentSamples(@PathVariable long id,
                                              @RequestParam(required = false) Integer version) {
        Rows.SegmentRow segment = segmentRepo.findById(id);
        if (segment == null) {
            throw new IllegalArgumentException("片段不存在");
        }
        Rows.SegmentVersionRow versionRow = version == null
                ? segmentRepo.findCurrentVersion(id)
                : segmentRepo.findVersion(id, version);
        List<Float> values = new ArrayList<>();
        for (float value : segmentService.samples(id, version)) {
            values.add(value);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", segment.id());
        body.put("version", versionRow.version());
        body.put("startMs", segment.startMs());
        body.put("sampleRateHz", segment.sampleRateHz());
        body.put("clipped", versionRow.clipped());
        body.put("contentHash", versionRow.contentHash());
        body.put("samples", values);
        return body;
    }

    // ---------- picks ----------

    @GetMapping("/events/{eventId}/stations/{stationId}/candidates")
    public List<Candidate> candidates(@PathVariable long eventId, @PathVariable long stationId) {
        return pickService.candidates(eventId, stationId);
    }

    @GetMapping("/events/{eventId}/stations/{stationId}/history")
    public List<Rows.PickEventRow> history(@PathVariable long eventId,
                                           @PathVariable long stationId) {
        return pickService.stream(eventId, stationId);
    }

    @PostMapping("/picks")
    public Candidate addPick(@RequestBody Requests.AddPickRequest request) {
        return pickCommandService.add(request.eventId(), request.stationId(), request.phase(),
                request.segmentId(), request.arrivalMs(), request.ciLowMs(),
                request.ciHighMs(), request.polarity(), request.weight(),
                request.weightSource());
    }

    @PostMapping("/picks/{id}/move")
    public Candidate movePick(@PathVariable long id,
                              @RequestBody Requests.MovePickRequest request) {
        return pickCommandService.move(request.eventId(), request.stationId(), id,
                request.arrivalMs(), request.ciLowMs(), request.ciHighMs());
    }

    @PostMapping("/picks/{id}/polarity")
    public Candidate polarity(@PathVariable long id,
                              @RequestBody Requests.PolarityRequest request) {
        return pickCommandService.polarity(request.eventId(), request.stationId(), id,
                request.polarity());
    }

    @PostMapping("/picks/{id}/ci")
    public Candidate ci(@PathVariable long id, @RequestBody Requests.CiRequest request,
                        @RequestParam long eventId, @RequestParam long stationId) {
        return pickCommandService.ci(eventId, stationId, id, request.ciLowMs(),
                request.ciHighMs());
    }

    @PostMapping("/picks/{id}/weight")
    public Candidate weight(@PathVariable long id, @RequestBody Requests.WeightRequest request,
                            @RequestParam long eventId, @RequestParam long stationId) {
        return pickCommandService.weight(eventId, stationId, id, request.weight(),
                request.weightSource());
    }

    @PostMapping("/picks/merge")
    public Candidate merge(@RequestBody Requests.MergeRequest request,
                           @RequestParam long eventId, @RequestParam long stationId) {
        return pickCommandService.merge(eventId, stationId, request.candidateIds());
    }

    @PostMapping("/picks/{id}/noise")
    public Candidate noise(@PathVariable long id, @RequestParam long eventId,
                           @RequestParam long stationId) {
        return pickCommandService.noise(eventId, stationId, id);
    }

    @PostMapping("/events/{eventId}/stations/{stationId}/undo")
    public Rows.PickEventRow undo(@PathVariable long eventId, @PathVariable long stationId) {
        return pickCommandService.undo(eventId, stationId);
    }

    // ---------- compare & interpret ----------

    @PostMapping("/compare")
    public CompareService.Comparison compare(@RequestBody Requests.CompareRequest request) {
        Map<Long, Integer> stationVersions = new LinkedHashMap<>();
        Map<Long, Integer> pickSeqs = new LinkedHashMap<>();
        for (Requests.PinStationRequest pin : request.stations()) {
            stationVersions.put(pin.stationId(), pin.stationVersion());
            pickSeqs.put(pin.stationId(), pin.pickSeq());
        }
        return compareService.compare(request.eventId(), request.models(), stationVersions,
                pickSeqs, request.windowMs());
    }

    @PostMapping("/interpretations")
    public InterpretationService.PinnedVersion createInterpretation(
            @RequestBody Requests.CreateInterpretationRequest request) {
        List<InterpretationService.StationPin> pins = pins(request.stations());
        return interpretationService.createVersion(request.eventId(), request.name(), pins,
                request.modelId(), request.modelVersion());
    }

    @GetMapping("/interpretations")
    public List<Map<String, Object>> listInterpretations() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Rows.InterpretationRow row : interpretationRepo.findAll()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", row.id());
            body.put("name", row.name());
            body.put("parentId", row.parentId());
            body.put("versions", interpretationService.listVersions(row.id()));
            result.add(body);
        }
        return result;
    }

    @GetMapping("/interpretations/versions/{id}")
    public InterpretationService.PinnedVersion interpretationVersion(@PathVariable long id) {
        return interpretationService.loadVersion(id);
    }

    @PostMapping("/interpretations/versions/{id}/freeze")
    public InterpretationService.FreezeResult freeze(@PathVariable long id,
                                                     @RequestParam long eventId) {
        return interpretationService.freeze(eventId, id);
    }

    @PostMapping("/interpretations/versions/{id}/branch")
    public InterpretationService.PinnedVersion branch(@PathVariable long id,
                                                      @RequestBody Requests.BranchRequest request) {
        return interpretationService.branch(request.eventId(), id, request.name(),
                request.modelId(), request.modelVersion());
    }

    private List<InterpretationService.StationPin> pins(List<Requests.PinStationRequest> requests) {
        List<InterpretationService.StationPin> pins = new ArrayList<>();
        for (Requests.PinStationRequest request : requests) {
            pins.add(new InterpretationService.StationPin(request.stationId(),
                    request.stationVersion(), request.pickSeq()));
        }
        return pins;
    }

    private List<Layer> toLayers(List<Requests.LayerRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("至少需要一层");
        }
        List<Layer> layers = new ArrayList<>();
        for (Requests.LayerRequest request : requests) {
            layers.add(new Layer(request.topDepthM(), request.vpMps(), request.vsMps()));
        }
        return layers;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }
}
