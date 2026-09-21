package com.room.phase;

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
@RequestMapping("/api")
public class ApiController {
    private final CatalogService catalog;
    private final PickService picks;
    private final BranchService branches;
    private final ComparisonService comparison;

    public ApiController(CatalogService catalog, PickService picks, BranchService branches, ComparisonService comparison) {
        this.catalog = catalog;
        this.picks = picks;
        this.branches = branches;
        this.comparison = comparison;
    }

    @PostMapping("/events")
    public Map<String, Object> createEvent(@RequestBody Models.EventRequest request) {
        return catalog.createEvent(request);
    }

    @GetMapping("/events/{code}")
    public Map<String, Object> event(@PathVariable String code) {
        return catalog.eventByCode(code);
    }

    @PostMapping("/stations/versions")
    public Map<String, Object> station(@RequestBody Models.StationRequest request) {
        return catalog.saveStation(request);
    }

    @GetMapping("/stations/versions")
    public List<Map<String, Object>> stations() {
        return catalog.list("SELECT * FROM station_versions ORDER BY station_code,id");
    }

    @PostMapping("/velocity-models/versions")
    public Map<String, Object> velocityModel(@RequestBody Models.VelocityModelRequest request) {
        return catalog.saveVelocityModel(request);
    }

    @GetMapping("/velocity-models/versions")
    public List<Map<String, Object>> velocityModels() {
        return catalog.list("SELECT * FROM velocity_models ORDER BY id");
    }

    @PostMapping("/waveforms")
    public Map<String, Object> waveform(@RequestBody Models.WaveformRequest request) {
        return catalog.importWaveform(request);
    }

    @GetMapping("/waveforms")
    public List<Map<String, Object>> waveforms() {
        return catalog.list("""
                SELECT wc.clip_code,wc.station_code,wv.*,
                       (SELECT COUNT(*) FROM waveform_receptions wr WHERE wr.waveform_version_id=wv.id) AS reception_count,
                       (SELECT SUM(duplicate) FROM waveform_receptions wr WHERE wr.waveform_version_id=wv.id) AS duplicate_receptions
                FROM waveform_clips wc JOIN waveform_versions wv ON wv.clip_id=wc.id ORDER BY wc.clip_code,wv.version
                """);
    }

    @PostMapping("/events/{code}/picks")
    public Map<String, Object> pick(@PathVariable String code, @RequestBody Models.PickRequest request) {
        return picks.createPick(code, request);
    }

    @GetMapping("/events/{code}/picks")
    public List<Map<String, Object>> picks(@PathVariable String code) {
        return catalog.list("SELECT pv.* FROM pick_versions pv JOIN events e ON e.id=pv.event_id WHERE e.event_code=? ORDER BY pv.station_code,pv.phase,pv.version,pv.id", code);
    }

    @PostMapping("/branches")
    public Map<String, Object> branch(@RequestBody Models.BranchRequest request) {
        return branches.createBranch(request);
    }

    @GetMapping("/branches")
    public List<Map<String, Object>> branchList() {
        return catalog.list("SELECT id,branch_code,name,event_id,parent_branch_id,current_model_id,frozen_at,created_at FROM interpretation_branches ORDER BY id");
    }

    @GetMapping("/branches/{code}")
    public Map<String, Object> branch(@PathVariable String code) {
        return branches.branch(code);
    }

    @PostMapping("/branches/{code}/actions")
    public Map<String, Object> action(@PathVariable String code, @RequestBody Models.ActionRequest request) {
        return branches.action(code, request);
    }

    @PostMapping("/branches/{code}/undo")
    public Map<String, Object> undo(@PathVariable String code) {
        return branches.undo(code);
    }

    @PostMapping("/branches/{code}/freeze")
    public Map<String, Object> freeze(@PathVariable String code) {
        return branches.freeze(code);
    }

    @PostMapping("/branches/{code}/clone")
    public Map<String, Object> clone(@PathVariable String code, @RequestBody Models.CloneBranchRequest request) {
        return branches.cloneBranch(code, request);
    }

    @GetMapping("/branches/{code}/compare")
    public Map<String, Object> compareGet(@PathVariable String code,
                                          @RequestParam(required = false) List<Long> modelId,
                                          @RequestParam(required = false) Double windowSec) {
        return comparison.compare(code, modelId, windowSec);
    }

    @PostMapping("/branches/{code}/compare")
    public Map<String, Object> comparePost(@PathVariable String code, @RequestBody(required = false) Models.CompareRequest request) {
        return comparison.compare(code, request == null ? List.of() : request.modelIds(), request == null ? null : request.timeWindowSec());
    }

    @PostMapping("/branches/{code}/replay")
    public Map<String, Object> replay(@PathVariable String code, @RequestBody(required = false) Map<String, Object> payload) {
        return comparison.replay(code, payload == null ? Map.of() : payload);
    }

    @GetMapping("/branches/{code}/conflicts")
    public Map<String, Object> conflicts(@PathVariable String code, @RequestParam(defaultValue = "120") Double windowSec) {
        Map<String, Object> branch = branches.branch(code);
        BranchService.ConflictResult conflict = branches.conflictReport(code, windowSec);
        return Map.of("conflictStationCodes", conflict.stations(), "edgeCount", conflict.edges().size(),
                "windowNs", Math.round(windowSec * 1_000_000_000.0));
    }
}
