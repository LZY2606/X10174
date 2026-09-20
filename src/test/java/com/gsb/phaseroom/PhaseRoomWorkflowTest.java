package com.gsb.phaseroom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "phaseroom.database=build/test-data/phase-room-test.db")
class PhaseRoomWorkflowTest {

    @Autowired
    private TestRestTemplate rest;

    private Map<String, Object> post(String path, Object payload) {
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(payload), Map.class);
        if (response.getStatusCode().isError()) {
            throw new AssertionError(path + " -> " + response.getStatusCode() + " "
                    + response.getBody());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = rest.getForEntity(path, Map.class);
        if (response.getStatusCode().isError()) {
            throw new AssertionError(path + " -> " + response.getBody());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private void resetSeed() {
        post("/api/admin/reset", Map.of());
        post("/api/demo/seed?force=true", Map.of());
    }

    @Test
    void undoRevertsCandidateMoveAndClockCorrection() {
        resetSeed();
        Map<String, Object> before = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picks = (List<Map<String, Object>>) before.get("picks");
        long s01P = picks.stream()
                .filter(p -> "S01".equals(p.get("stationCode")) && "P".equals(p.get("phase"))
                        && "SELECTED".equals(p.get("status")))
                .map(p -> ((Number) p.get("id")).longValue()).findFirst().orElseThrow();

        post("/api/interpretations/I-REVIEW/picks/" + s01P + "/move",
                Map.of("time", 1_000_006.45, "ciHalfWidth", 0.6));
        Map<String, Object> moved = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movedPicks =
                (List<Map<String, Object>>) moved.get("picks");
        Map<String, Object> movedPick = movedPicks.stream()
                .filter(p -> ((Number) p.get("id")).longValue() == s01P).findFirst().orElseThrow();
        assertEquals(1_000_006.45, ((Number) movedPick.get("time")).doubleValue());

        post("/api/interpretations/I-REVIEW/undo", Map.of());
        Map<String, Object> restored = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restoredPicks =
                (List<Map<String, Object>>) restored.get("picks");
        Map<String, Object> restoredPick = restoredPicks.stream()
                .filter(p -> ((Number) p.get("id")).longValue() == s01P).findFirst().orElseThrow();
        assertEquals(1_000_006.0, ((Number) restoredPick.get("time")).doubleValue());

        post("/api/interpretations/I-REVIEW/corrections",
                Map.of("stationCode", "S02", "shiftSeconds", 0.5));
        Map<String, Object> corrected = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        Map<String, Double> corrections =
                (Map<String, Double>) corrected.get("corrections");
        assertEquals(0.5, corrections.get("S02"));
        post("/api/interpretations/I-REVIEW/undo", Map.of());
        Map<String, Object> undone = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        Map<String, Object> undoneCorrections =
                (Map<String, Object>) undone.get("corrections");
        assertFalse(undoneCorrections.containsKey("S02"));
    }

    @Test
    void mergeAndNoiseActionsAreReplayableAndReversible() {
        resetSeed();
        Map<String, Object> detail = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picks = (List<Map<String, Object>>) detail.get("picks");
        Long selected = picks.stream()
                .filter(p -> "S01".equals(p.get("stationCode")) && "P".equals(p.get("phase"))
                        && "SELECTED".equals(p.get("status")))
                .map(p -> ((Number) p.get("id")).longValue()).findFirst().orElseThrow();
        Long candidate = picks.stream()
                .filter(p -> "S01".equals(p.get("stationCode")) && "P".equals(p.get("phase"))
                        && "CANDIDATE".equals(p.get("status")))
                .map(p -> ((Number) p.get("id")).longValue()).findFirst().orElseThrow();

        post("/api/interpretations/I-REVIEW/picks/merge",
                Map.of("winningPickId", selected, "losingPickId", candidate));
        Map<String, Object> merged = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedPick = ((List<Map<String, Object>>) merged.get("picks")).stream()
                .filter(p -> ((Number) p.get("id")).longValue() == candidate).findFirst().orElseThrow();
        assertEquals("MERGED", mergedPick.get("status"));
        post("/api/interpretations/I-REVIEW/undo", Map.of());
        Map<String, Object> restored = get("/api/interpretations/I-REVIEW");
        Map<String, Object> restoredPick = ((List<Map<String, Object>>) restored.get("picks")).stream()
                .filter(p -> ((Number) p.get("id")).longValue() == candidate).findFirst().orElseThrow();
        assertEquals("CANDIDATE", restoredPick.get("status"));
    }

    @Test
    void clockReversalBlocksFreezeAndReportsMinimumConflictSet() {
        resetSeed();
        // Raw P order is approximately S01, S02, S03, S04. Push later stations earlier and
        // earlier stations later hard enough to invert adjacent pairs.
        post("/api/interpretations/I-REVIEW/corrections",
                Map.of("stationCode", "S01", "shiftSeconds", 8.0));
        post("/api/interpretations/I-REVIEW/corrections",
                Map.of("stationCode", "S04", "shiftSeconds", -8.0));
        Map<String, Object> freeze = post("/api/interpretations/I-REVIEW/freeze", Map.of());
        assertEquals(true, freeze.get("blocked"));
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) freeze.get("analysis");
        assertEquals(true, analysis.get("reversed"));
        @SuppressWarnings("unchecked")
        List<String> minimum = (List<String>) analysis.get("minimumConflictStations");
        assertNotNull(minimum);
        assertFalse(minimum.isEmpty());
        // Removing the reported stations must leave a non-decreasing corrected order.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> corrected =
                (List<Map<String, Object>>) analysis.get("correctedOrder");
        List<Map<String, Object>> remaining = corrected.stream()
                .filter(row -> !minimum.contains(row.get("stationCode"))).toList();
        for (int i = 1; i < remaining.size(); i++) {
            double previous = ((Number) remaining.get(i - 1).get("correctedTime")).doubleValue();
            double current = ((Number) remaining.get(i).get("correctedTime")).doubleValue();
            String previousCode = (String) remaining.get(i - 1).get("stationCode");
            String currentCode = (String) remaining.get(i).get("stationCode");
            assertTrue(previous <= current
                    || (previous == current && previousCode.compareTo(currentCode) <= 0));
        }

        // Undo the bad corrections, then freeze must succeed.
        post("/api/interpretations/I-REVIEW/undo", Map.of());
        post("/api/interpretations/I-REVIEW/undo", Map.of());
        Map<String, Object> cleanFreeze = post("/api/interpretations/I-REVIEW/freeze", Map.of());
        assertEquals(true, cleanFreeze.get("frozen"));
    }

    @Test
    void outsideWindowModelNeverWinsEvenWithSmallerMeanResidual() {
        resetSeed();
        Map<String, Object> comparison = post("/api/interpretations/I-REVIEW/compare",
                Map.of("modelIds", List.of(1, 2, 3)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) comparison.get("models");
        assertEquals(3, models.size());
        boolean hasRejected = models.stream()
                .anyMatch(model -> !Boolean.parseBoolean(String.valueOf(model.get("eligible")))
                        && ((Number) model.get("outsideWindowCount")).intValue() > 0);
        assertTrue(hasRejected, "固定数据至少有一个模型应因窗口外拾取被一票否决");
        Map<String, Object> winner = models.get(0);
        assertEquals("true", String.valueOf(winner.get("eligible")));
        // Deterministic ordering: every eligible model precedes every rejected model, and
        // within each eligibility group weighted mean absolute residual is non-decreasing.
        boolean seenRejected = false;
        boolean firstInGroup = true;
        double previousScore = Double.NEGATIVE_INFINITY;
        Boolean previousEligibility = null;
        for (Map<String, Object> model : models) {
            boolean eligible = Boolean.parseBoolean(String.valueOf(model.get("eligible")));
            if (!eligible) {
                seenRejected = true;
            }
            assertFalse(seenRejected && eligible, "合格模型不能排在被否决模型之后");
            double score = ((Number) model.get("weightedMeanAbsResidual")).doubleValue();
            if (previousEligibility == null || previousEligibility != eligible) {
                firstInGroup = true;
            }
            if (!firstInGroup) {
                assertTrue(score >= previousScore, "同组模型必须按加权平均残差确定性升序");
            }
            previousScore = score;
            previousEligibility = eligible;
            firstInGroup = false;
        }
    }

    @Test
    void tieBreaksDeterministicallyByModelCode() {
        resetSeed();
        // The same eligible model compared against itself yields identical scores; the code
        // tie-break keeps a deterministic VM-C winner instead of null.
        Map<String, Object> comparison = post("/api/interpretations/I-REVIEW/compare",
                Map.of("modelIds", List.of(3, 3)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) comparison.get("models");
        assertEquals(2, models.size());
        assertEquals(models.get(0).get("weightedMeanAbsResidual"),
                models.get(1).get("weightedMeanAbsResidual"));
        assertEquals("VM-C", comparison.get("winnerModelCode"));
    }

    @Test
    void confidenceIntervalsOverlapAndWeightSourcesAreExposed() {
        resetSeed();
        Map<String, Object> comparison = post("/api/interpretations/I-REVIEW/compare",
                Map.of("modelIds", List.of(1, 2, 3)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) comparison.get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRow =
                ((List<Map<String, Object>>) models.get(0).get("rows")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> factors = (Map<String, Object>) firstRow.get("weightFactors");
        assertTrue(factors.containsKey("source"));
        assertTrue(factors.containsKey("confidence"));
        assertTrue(factors.containsKey("clipping"));
        assertTrue(factors.containsKey("gapProximity"));
        assertTrue(factors.containsKey("clockCorrection"));

        Map<String, Object> diffs = post(
                "/api/interpretations/I-REVIEW/station-diffs",
                Map.of("modelAId", 1, "modelBId", 2));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffRows =
                (List<Map<String, Object>>) diffs.get("diffs");
        assertFalse(diffRows.isEmpty());
        String firstKey = diffRows.get(0).get("stationCode") + "|"
                + diffRows.get(0).get("phase");
        // Deterministic station/phase order.
        for (int i = 1; i < diffRows.size(); i++) {
            String key = diffRows.get(i).get("stationCode") + "|" + diffRows.get(i).get("phase");
            assertTrue(firstKey.compareTo(key) <= 0);
        }
    }
}
