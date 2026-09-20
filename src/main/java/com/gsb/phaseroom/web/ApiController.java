package com.gsb.phaseroom.web;

import com.gsb.phaseroom.service.BranchService;
import com.gsb.phaseroom.service.DemoData;
import com.gsb.phaseroom.service.Events;
import com.gsb.phaseroom.service.FreezeService;
import com.gsb.phaseroom.service.FrozenService;
import com.gsb.phaseroom.service.Interpretations;
import com.gsb.phaseroom.service.ResidualService;
import com.gsb.phaseroom.service.Stations;
import com.gsb.phaseroom.service.VelocityModels;
import com.gsb.phaseroom.service.Waveforms;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final Stations stations;
    private final Events events;
    private final VelocityModels velocityModels;
    private final Waveforms waveforms;
    private final Interpretations interpretations;
    private final ResidualService residuals;
    private final FrozenService frozen;
    private final FreezeService freeze;
    private final BranchService branches;
    private final DemoData demoData;
    private final JdbcTemplate jdbc;

    public ApiController(Stations stations, Events events, VelocityModels velocityModels,
                         Waveforms waveforms, Interpretations interpretations,
                         ResidualService residuals, FrozenService frozen, FreezeService freeze,
                         BranchService branches, DemoData demoData, JdbcTemplate jdbc) {
        this.stations = stations;
        this.events = events;
        this.velocityModels = velocityModels;
        this.waveforms = waveforms;
        this.interpretations = interpretations;
        this.residuals = residuals;
        this.frozen = frozen;
        this.freeze = freeze;
        this.branches = branches;
        this.demoData = demoData;
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("application", "震相合议室", "ok", true);
    }

    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        List<String> tables = List.of("action_event", "clock_correction", "pick",
                "interpretation", "import_receipt", "waveform_segment", "waveform_content",
                "waveform_track", "velocity_layer", "velocity_model", "seismic_event",
                "station");
        for (String table : tables) {
            jdbc.update("DELETE FROM " + table);
            jdbc.update("DELETE FROM sqlite_sequence WHERE name = ?", table);
        }
        return Map.of("reset", true);
    }

    @PostMapping("/demo/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "false") boolean force) {
        return demoData.seed(force);
    }

    // ---------- stations ----------

    @PostMapping("/stations")
    public Stations.Station putStation(@RequestBody Map<String, Object> body) {
        return stations.put(str(body, "code"), str(body, "name"), dbl(body, "lat"),
                dbl(body, "lon"), dbl(body, "elevationM"));
    }

    @GetMapping("/stations")
    public List<Stations.Station> stations() {
        return stations.listActive();
    }

    // ---------- events ----------

    @PostMapping("/events")
    public Events.SeismicEvent createEvent(@RequestBody Map<String, Object> body) {
        return events.create(str(body, "code"), dbl(body, "originTime"), dbl(body, "lat"),
                dbl(body, "lon"), dbl(body, "depthKm"));
    }

    @GetMapping("/events")
    public List<Events.SeismicEvent> listEvents() {
        return events.list();
    }

    // ---------- velocity models ----------

    @PostMapping("/velocity-models")
    public VelocityModels.Model createModel(@RequestBody Map<String, Object> body) {
        List<?> layerRows = rawList(body, "layers");
        List<double[]> layers = layerRows.stream()
                .map(row -> new double[]{dbl((Map<String, Object>) row, "topDepthKm"),
                        dbl((Map<String, Object>) row, "vpKms"),
                        dbl((Map<String, Object>) row, "vsKms")})
                .toList();
        return velocityModels.create(str(body, "code"), str(body, "name"), layers);
    }

    @GetMapping("/velocity-models")
    public List<VelocityModels.Model> models() {
        return velocityModels.list();
    }

    // ---------- waveforms ----------

    @PostMapping("/waveforms/segments")
    public Map<String, Object> importSegment(@RequestBody Map<String, Object> body) {
        List<Double> samples = rawList(body, "samples").stream()
                .map(v -> ((Number) v).doubleValue()).toList();
        Boolean clipped = body.get("clipped") == null ? null
                : Boolean.valueOf(body.get("clipped").toString());
        return waveforms.importSegment(str(body, "stationCode"), str(body, "channel"),
                dbl(body, "startTime"), dbl(body, "sampleRateHz"), samples,
                dbl(body, "clockOffsetS", 0.0), clipped, str(body, "sourceName", "API"));
    }

    @GetMapping("/waveforms/tracks")
    public List<Map<String, Object>> tracks() {
        return waveforms.tracks();
    }

    @GetMapping("/waveforms/tracks/{station}/{channel}/timeline")
    public Map<String, Object> timeline(@PathVariable String station,
                                        @PathVariable String channel) {
        return waveforms.timeline(station, channel);
    }

    @GetMapping("/waveforms/tracks/{station}/{channel}/segments")
    public List<?> segmentSamples(@PathVariable String station, @PathVariable String channel) {
        return waveforms.segmentsForTrack(station, channel, true);
    }

    // ---------- interpretations ----------

    @PostMapping("/interpretations")
    public Interpretations.Interpretation createInterpretation(@RequestBody Map<String, Object> body) {
        return interpretations.create(str(body, "code"), str(body, "name"),
                str(body, "eventCode"), (long) dbl(body, "modelId"));
    }

    @GetMapping("/interpretations")
    public List<Map<String, Object>> interpretations() {
        return interpretations.list();
    }

    @GetMapping("/interpretations/{code}")
    public Map<String, Object> interpretation(@PathVariable String code) {
        Interpretations.Interpretation interp = interpretations.get(code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interpretation", interp);
        out.put("picks", interpretations.picks(interp.id()));
        out.put("corrections", interpretations.corrections(interp.id()));
        out.put("events", interpretations.events(code));
        out.put("frozenBundle", frozenBundle(interp));
        return out;
    }

    private Object frozenBundle(Interpretations.Interpretation interp) {
        if (!interp.frozen()) {
            return null;
        }
        return jdbc.queryForObject("SELECT frozen_bundle FROM interpretation WHERE id = ?",
                String.class, interp.id());
    }

    @PostMapping("/interpretations/{code}/picks")
    public Interpretations.Pick addPick(@PathVariable String code,
                                        @RequestBody Map<String, Object> body) {
        return interpretations.addPick(code, str(body, "stationCode"), str(body, "phase"),
                dbl(body, "time"), str(body, "polarity", null),
                dbl(body, "ciHalfWidth", 0.0), str(body, "source", "MANUAL"),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("selected", true))));
    }

    @PostMapping("/interpretations/{code}/picks/{pickId}/move")
    public Interpretations.Pick move(@PathVariable String code, @PathVariable long pickId,
                                     @RequestBody Map<String, Object> body) {
        return interpretations.updatePick(code, pickId, asDouble(body.get("time")),
                body.containsKey("polarity") ? str(body, "polarity", null) : null,
                asDouble(body.get("ciHalfWidth")));
    }

    @PostMapping("/interpretations/{code}/picks/{pickId}/status")
    public Interpretations.Pick status(@PathVariable String code, @PathVariable long pickId,
                                       @RequestBody Map<String, Object> body) {
        return interpretations.markStatus(code, pickId, str(body, "status"));
    }

    @PostMapping("/interpretations/{code}/picks/merge")
    public Interpretations.Pick merge(@PathVariable String code,
                                      @RequestBody Map<String, Object> body) {
        return interpretations.mergeCandidates(code, (long) dbl(body, "winningPickId"),
                (long) dbl(body, "losingPickId"));
    }

    @PostMapping("/interpretations/{code}/corrections")
    public Map<String, Object> correction(@PathVariable String code,
                                          @RequestBody Map<String, Object> body) {
        interpretations.setCorrection(code, str(body, "stationCode"),
                dbl(body, "shiftSeconds"));
        return Map.of("ok", true, "corrections", interpretations.corrections(
                interpretations.get(code).id()));
    }

    @PostMapping("/interpretations/{code}/undo")
    public Map<String, Object> undo(@PathVariable String code) {
        return interpretations.undo(code);
    }

    @GetMapping("/interpretations/{code}/ordering")
    public Map<String, Object> ordering(@PathVariable String code) {
        return freeze.analyse(interpretations.get(code).id());
    }

    @PostMapping("/interpretations/{code}/freeze")
    public Map<String, Object> freeze(@PathVariable String code) {
        return frozen.freeze(code);
    }

    @GetMapping("/interpretations/{code}/replay")
    public Map<String, Object> replay(@PathVariable String code) {
        return frozen.replay(code);
    }

    @PostMapping("/interpretations/{code}/branch")
    public Interpretations.Interpretation branch(@PathVariable String code,
                                                 @RequestBody Map<String, Object> body) {
        return branches.branch(code, str(body, "code"), str(body, "name", null),
                body.get("modelId") == null ? null : (long) dbl(body, "modelId"));
    }

    @PostMapping("/interpretations/{code}/compare")
    public Map<String, Object> compare(@PathVariable String code,
                                       @RequestBody Map<String, Object> body) {
        List<Long> ids = rawList(body, "modelIds").stream()
                .map(v -> ((Number) v).longValue()).toList();
        return residuals.compare(code, ids);
    }

    @PostMapping("/interpretations/{code}/station-diffs")
    public Map<String, Object> stationDiffs(@PathVariable String code,
                                            @RequestBody Map<String, Object> body) {
        return residuals.stationDiffs(code, (long) dbl(body, "modelAId"),
                (long) dbl(body, "modelBId"));
    }

    // ---------- helpers ----------

    private static String str(Map<String, Object> body, String key) {
        return str(body, key, null);
    }

    private static String str(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        if (value == null) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("缺少字段: " + key);
        }
        return value.toString();
    }

    private static double dbl(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("缺少数值字段: " + key);
        }
        return ((Number) value).doubleValue();
    }

    private static double dbl(Map<String, Object> body, String key, double fallback) {
        Object value = body.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) rawList(body, key);
    }

    private static List<?> rawList(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("缺少数组字段: " + key);
        }
        return (List<?>) value;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> error(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName()
                : ex.getMessage());
    }
}
