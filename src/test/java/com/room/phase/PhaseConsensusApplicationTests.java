package com.room.phase;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhaseConsensusApplicationTests {
    static final Path DB = Path.of(System.getProperty("java.io.tmpdir"), "phase-room-test-" + ProcessHandle.current().pid() + ".db");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void fixedWorkflowCoversVersionsDedupComparisonConflictUndoRestartAndFreeze() {
        post("/api/events", new Models.EventRequest("EQ1", 0L, 0.0, 0.0, 10.0));
        post("/api/stations/versions", new Models.StationRequest("S1", "v1", 0.0, 0.0540, 0.0, Map.of()));
        post("/api/stations/versions", new Models.StationRequest("S2", "v1", 0.0, 0.1080, 0.0, Map.of()));
        post("/api/stations/versions", new Models.StationRequest("S3", "v1", 0.0, 0.1620, 0.0, Map.of()));
        Map<?, ?> fast = post("/api/velocity-models/versions", new Models.VelocityModelRequest("FAST", "v1", List.of(new Models.Layer(0, 6.0, 3.5))));
        Map<?, ?> slow = post("/api/velocity-models/versions", new Models.VelocityModelRequest("SLOW", "v1", List.of(new Models.Layer(0, 1.8, 1.2))));
        Map<?, ?> tied = post("/api/velocity-models/versions", new Models.VelocityModelRequest("TIED", "v1", List.of(new Models.Layer(0, 6.0, 3.5))));

        Map<?, ?> first = post("/api/waveforms", new Models.WaveformRequest("W1", "S1", 10.0, 0L, List.of(0.0, 1.0, 0.0, -1.0), List.of(List.of(1, 3)), List.of(), List.of(3), "analyst"));
        Map<?, ?> duplicate = post("/api/waveforms", new Models.WaveformRequest("W1", "S1", 10.0, 0L, List.of(0.0, 1.0, 0.0, -1.0), List.of(List.of(1, 3)), List.of(), List.of(3), "algorithm"));
        Map<?, ?> changed = post("/api/waveforms", new Models.WaveformRequest("W2", "S2", 20.0, 0L, List.of(0.0, 0.5, 1.0), List.of(), List.of(List.of(0, 1)), List.of(2), "analyst"));
        assertEquals(false, first.get("duplicate"));
        assertEquals(true, duplicate.get("duplicate"));
        assertEquals(2, duplicate.get("receptionCount"));
        assertEquals(20.0, changed.get("sample_rate_hz"));
        assertEquals(1, changed.get("version"));

        post("/api/events/EQ1/picks", new Models.PickRequest("P1", "S1", "P", 1_000_000_000L, "POSITIVE", 900_000_000L, 1_100_000_000L, "manual"));
        post("/api/events/EQ1/picks", new Models.PickRequest("P1B", "S1", "P", 1_050_000_000L, "NEGATIVE", 950_000_000L, 1_200_000_000L, "algorithm"));
        post("/api/events/EQ1/picks", new Models.PickRequest("P2", "S2", "P", 2_000_000_000L, "NEUTRAL", 1_900_000_000L, 2_100_000_000L, "manual"));
        post("/api/events/EQ1/picks", new Models.PickRequest("P3", "S3", "P", 3_000_000_000L, "NEUTRAL", 2_900_000_000L, 3_100_000_000L, "manual"));

        Map<?, ?> branch = post("/api/branches", new Models.BranchRequest("B1", "main", "EQ1", longValue(fast, "id"), Map.of()));
        assertEquals(4, ((List<?>) branch.get("picks")).size());

        Map<?, ?> comparison = get("/api/branches/B1/compare?windowSec=2&modelId=" + fast.get("id") + "&modelId=" + slow.get("id"));
        assertEquals(fast.get("id"), comparison.get("winnerModelId"));
        assertEquals(false, comparison.get("tied"));
        List<Map<?, ?>> models = castList(comparison.get("models"));
        Map<?, ?> fastResult = models.stream().filter(row -> fast.get("id").equals(row.get("modelId"))).findFirst().orElseThrow();
        Map<?, ?> slowResult = models.stream().filter(row -> slow.get("id").equals(row.get("modelId"))).findFirst().orElseThrow();
        assertEquals(true, fastResult.get("eligible"));
        assertEquals(false, slowResult.get("eligible"));
        Map<?, ?> fastS1 = stationPhase(fastResult, "S1", "P");
        Map<?, ?> slowS1 = stationPhase(slowResult, "S1", "P");
        assertEquals(2, fastS1.get("candidateCount"));
        assertEquals(true, fastS1.get("inWindow"));
        assertEquals(false, slowS1.get("inWindow"));
        assertEquals(0.5, ((Number) weightSources(fastS1, "S1").get("gapPenalty")).doubleValue());
        assertEquals(0.25, ((Number) weightSources(fastS1, "S1").get("clippingPenalty")).doubleValue());
        Map<?, ?> fastS2 = stationPhase(fastResult, "S2", "P");
        assertEquals(1.0 / 3.0, ((Number) weightSources(fastS2, "S2").get("overlapPenalty")).doubleValue());
        assertTrue(Math.abs(((Number) fastS1.get("residualNs")).longValue()) < Math.abs(((Number) slowS1.get("residualNs")).longValue()));
        assertTrue(Math.abs(((Number) slowS1.get("residualNs")).longValue()) > 2_000_000_000L);

        Map<?, ?> tiedComparison = get("/api/branches/B1/compare?windowSec=2&modelId=" + fast.get("id") + "&modelId=" + tied.get("id"));
        assertEquals(true, tiedComparison.get("tied"));
                assertEquals(List.of(longValue(fast, "id"), longValue(tied, "id")), numberList(tiedComparison.get("tiedModelIds")).stream().map(Number::longValue).toList());

        Map<?, ?> p1 = currentPick(branch, "P1");
        Map<?, ?> p1b = currentPick(branch, "P1B");
        assertTrue(((Number) p1.get("confidence_high_ns")).longValue() > ((Number) p1b.get("confidence_low_ns")).longValue());
        assertTrue(((Number) p1.get("confidence_low_ns")).longValue() < ((Number) p1b.get("confidence_high_ns")).longValue());

        Map<?, ?> merged = post("/api/branches/B1/actions", new Models.ActionRequest("mergeCandidates", Map.of("targetPickCode", "P1", "pickCodes", List.of("P1", "P1B"))));
        assertEquals(1, countStatus(merged, "MERGED"));
        Map<?, ?> undone = post("/api/branches/B1/undo", Map.of());
        assertEquals(0, countStatus(undone, "MERGED"));

        Map<?, ?> moved = post("/api/branches/B1/actions", new Models.ActionRequest("movePick", Map.of("pickCode", "P1", "timeNs", 1_010_000_000L, "confidenceLowNs", 900_000_000L, "confidenceHighNs", 1_100_000_000L)));
        assertEquals(3, currentPick(moved, "P1").get("version"));
        Map<?, ?> afterUndo = post("/api/branches/B1/undo", Map.of());
        assertEquals(1, currentPick(afterUndo, "P1").get("version"));

        post("/api/branches/B1/actions", new Models.ActionRequest("setClockCorrection", Map.of("stationCode", "S1", "correctionNs", 2_500_000_000L)));
        Map<?, ?> conflicts = get("/api/branches/B1/conflicts?windowSec=120");
        assertEquals(List.of("S1"), conflicts.get("conflictStationCodes"));
        ResponseEntity<Map> blocked = postRaw("/api/branches/B1/freeze", Map.of(), Map.class);
        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode());
        assertTrue(String.valueOf(blocked.getBody().get("error")).contains("S1"));

        post("/api/branches/B1/actions", new Models.ActionRequest("setClockCorrection", Map.of("stationCode", "S1", "correctionNs", 0L)));
        Map<?, ?> frozen = post("/api/branches/B1/freeze", Map.of());
        assertNotNull(frozen.get("frozen_at"));
        assertNotNull(frozen.get("frozen_snapshot_json"));
        post("/api/stations/versions", new Models.StationRequest("S1", "v2", 0.0, 0.0540, 123.0, Map.of("new", "altitude")));
        Map<?, ?> reloaded = get("/api/branches/B1");
        assertEquals(true, String.valueOf(reloaded.get("frozen_snapshot_json")).contains("\"version\":\"v1\""));

        Map<?, ?> replay = post("/api/branches/B1/replay", Map.of("modelId", longValue(slow, "id"),
                "pickVersionIds", Map.of("P1", 1, "P1B", 2, "P2", 3, "P3", 4),
                "stationVersionIds", Map.of("S1", 1, "S2", 2, "S3", 3), "timeWindowSec", 2));
        assertEquals(true, replay.get("replay"));
        assertEquals(longValue(slow, "id"), ((Number) replay.get("pinnedModelId")).longValue());
        assertEquals(false, replay.get("eligible"));

        Map<?, ?> clone = post("/api/branches/B1/clone", new Models.CloneBranchRequest("B2", "with slow", longValue(slow, "id")));
        assertEquals(slow.get("id"), clone.get("current_model_id"));
        assertNull(clone.get("frozenAt"));
        post("/api/branches/B2/actions", new Models.ActionRequest("setClockCorrection", Map.of("stationCode", "S2", "correctionNs", 123L)));
        try (ConfigurableApplicationContext restarted = SpringApplication.run(PhaseConsensusApplication.class,
                "--server.port=0", "--spring.datasource.url=jdbc:sqlite:" + DB, "--spring.sql.init.mode=always")) {
            TestRestTemplate restartedHttp = new TestRestTemplate();
            int port = restarted.getEnvironment().getProperty("local.server.port", Integer.class);
            Map<?, ?> restored = restartedHttp.getForObject("http://127.0.0.1:" + port + "/api/branches/B2", Map.class);
            Map<?, ?> restoredFrozen = restartedHttp.getForObject("http://127.0.0.1:" + port + "/api/branches/B1", Map.class);
            assertEquals(2, ((List<?>) restored.get("events")).size());
            assertEquals(true, String.valueOf(restored).contains("setClockCorrection"));
            assertNotNull(restoredFrozen.get("frozen_at"));
            assertNotNull(restoredFrozen.get("frozen_snapshot_json"));
        }
    }

    private Map<?, ?> post(String path, Object body) {
        ResponseEntity<Map> response = http.postForEntity(path, body, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) throw new AssertionError(path + " -> " + response.getBody());
        return response.getBody();
    }

    private <T> ResponseEntity<T> postRaw(String path, Object body, Class<T> type) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body), type);
    }

    private Map<?, ?> get(String path) {
        return http.getForObject(path, Map.class);
    }

    private long longValue(Map<?, ?> map, String key) {
        return ((Number) map.get(key)).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> castList(Object value) { return (List<Map<?, ?>>) value; }

    @SuppressWarnings("unchecked")
    private List<Number> numberList(Object value) { return (List<Number>) value; }

    private long countStatus(Map<?, ?> branch, String status) {
        return castList(branch.get("picks")).stream().filter(row -> status.equals(row.get("status"))).count();
    }

    private Map<?, ?> currentPick(Map<?, ?> branch, String code) {
        return castList(branch.get("picks")).stream().filter(row -> code.equals(row.get("pick_code"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> stationPhase(Map<?, ?> modelResult, String station, String phase) {
        Map<?, ?> stationResult = castList(modelResult.get("stations")).stream()
                .filter(row -> station.equals(row.get("stationCode"))).findFirst().orElseThrow();
        return (Map<?, ?>) ((Map<?, ?>) stationResult.get("phases")).get(phase);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> weightSources(Map<?, ?> phaseResult, String ignoredStation) {
        return (Map<String, ?>) phaseResult.get("weightSources");
    }
}
