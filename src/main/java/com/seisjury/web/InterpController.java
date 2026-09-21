package com.seisjury.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seisjury.db.Repositories;
import com.seisjury.domain.InterpService;
import com.seisjury.domain.InterpService.ComparisonResult;
import com.seisjury.domain.PickLogic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interpretations")
public class InterpController {

    private final InterpService service;
    private final Repositories repo;
    private final ObjectMapper mapper;

    public InterpController(InterpService service, Repositories repo, ObjectMapper mapper) {
        this.service = service;
        this.repo = repo;
        this.mapper = mapper;
    }

    public record CreateRequest(String code, String eventCode, String velocityModelCode) {
    }

    public record BranchRequest(String sourceCode, String newCode, String velocityModelCode) {
    }

    public record NoiseRequest(boolean noise) {
    }

    public record CompareRequest(long windowMs, List<String> velocityModelCodes) {
    }

    @PostMapping
    public Repositories.InterpRow create(@RequestBody CreateRequest request) {
        return service.create(request.code, request.eventCode, request.velocityModelCode);
    }

    @GetMapping
    public List<Repositories.InterpRow> list() {
        return repo.listInterps();
    }

    @PostMapping("/branch")
    public Repositories.InterpRow branch(@RequestBody BranchRequest request) {
        return service.branch(request.sourceCode, request.newCode, request.velocityModelCode);
    }

    @GetMapping("/{code}")
    public Map<String, Object> detail(@PathVariable String code) {
        Repositories.InterpRow interp = repo.interp(code);
        if (interp == null) {
            throw new IllegalArgumentException("interpretation not found: " + code);
        }
        PickLogic.State state = service.replay(interp.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interpretation", interp);
        InterpService.ModelView view = service.modelView(interp.velocityModelId());
        out.put("velocityModel", view);
        out.put("predictions", service.predictions(interp, view));
        out.put("candidates", state.ordered());
        out.put("clockCorrectionsMs", state.clockCorrectionMs);
        out.put("headSeq", state.lastSeq);
        out.put("events", eventView(interp.id()));
        out.put("stationPins", repo.pins(interp.id()));
        return out;
    }

    private List<Map<String, Object>> eventView(long interpId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Repositories.PickEventRow row : repo.events(interpId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", row.seq());
            item.put("eventType", row.eventType());
            item.put("undoOfSeq", row.undoOfSeq());
            item.put("payload", parse(row.payload()));
            item.put("createdMs", row.createdMs());
            item.put("undoable", repo.undoEntries(interpId).stream()
                    .anyMatch(e -> e[0] == row.seq()));
            result.add(item);
        }
        return result;
    }

    private Object parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    @PostMapping("/{code}/actions")
    public Map<String, Object> act(@PathVariable String code,
                                   @RequestBody InterpService.ActionRequest request) {
        long seq = service.act(code, request);
        return Map.of("seq", seq);
    }

    @PostMapping("/{code}/candidates/{id}/noise")
    public Map<String, Object> noise(@PathVariable String code, @PathVariable long id,
                                     @RequestBody(required = false) NoiseRequest request) {
        boolean value = request == null || request.noise();
        long seq = service.markNoise(code, id, value);
        return Map.of("seq", seq);
    }

    @PostMapping("/{code}/undo")
    public Map<String, Object> undo(@PathVariable String code) {
        long seq = service.undo(code);
        return Map.of("inverseSeq", seq);
    }

    @PostMapping("/{code}/compare")
    public ComparisonResult compare(@PathVariable String code,
                                    @RequestBody(required = false) CompareRequest request) {
        long window = request == null ? 5000L : Math.max(0L, request.windowMs);
        List<String> models = request == null ? null : request.velocityModelCodes();
        return service.compare(code, window, models);
    }

    @GetMapping("/{code}/clock-report")
    public Map<String, Object> clockReport(@PathVariable String code) {
        var report = service.clockReport(code);
        return Map.of("consistent", report.consistent(),
                "conflicts", report.conflicts(),
                "minimumConflictStations", report.minimumSet());
    }

    @PostMapping("/{code}/freeze")
    public InterpService.FreezeResult freeze(@PathVariable String code) {
        return service.freeze(code);
    }
}
