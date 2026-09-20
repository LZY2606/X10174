package com.gsb.phaseroom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "phaseroom.database=build/test-data/phase-room-test.db")
class PhaseRoomVersionTest {

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

    @Test
    void frozenReplayIgnoresLaterStationElevationChange() {
        post("/api/admin/reset", Map.of());
        post("/api/demo/seed?force=true", Map.of());
        Map<String, Object> frozen = post("/api/interpretations/I-REVIEW/freeze", Map.of());
        assertEquals(true, frozen.get("frozen"));
        Map<String, Object> replayBefore = get("/api/interpretations/I-REVIEW/replay");
        assertEquals(true, replayBefore.get("identical"));
        int pinnedVersion = ((Number) ((Map<?, ?>) replayBefore.get("pickedVersions"))
                .get("pickVersion")).intValue();
        assertTrue(pinnedVersion > 0);

        // Add a new station version with a very different elevation. The active metadata
        // changes, but the frozen interpretation must still replay against v1.
        post("/api/stations", Map.of(
                "code", "S01", "name", "Lingyun-resurvey", "lat", 35.30, "lon", 139.10,
                "elevationM", 2400.0));
        List<java.util.Map<String, Object>> active = rest.exchange("/api/stations",
                HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<
                        List<java.util.Map<String, Object>>>() {
                }).getBody();
        assertNotNull(active);
        Map<String, Object> activeS01 = active.stream()
                .filter(s -> "S01".equals(s.get("code"))).findFirst().orElseThrow();
        assertEquals(2, activeS01.get("version"));

        Map<String, Object> replayAfter = get("/api/interpretations/I-REVIEW/replay");
        assertEquals(true, replayAfter.get("identical"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pinnedStationVersions =
                (Map<String, Object>) ((Map<?, ?>) replayAfter.get("pickedVersions"))
                        .get("stationVersions");
        assertEquals(1, pinnedStationVersions.get("S01"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pinnedRows =
                (List<Map<String, Object>>) replayAfter.get("pinnedResiduals");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recomputedRows =
                (List<Map<String, Object>>) replayAfter.get("recomputedResiduals");
        assertEquals(pinnedRows.size(), recomputedRows.size());
        for (int i = 0; i < pinnedRows.size(); i++) {
            assertEquals(pinnedRows.get(i).get("residualSeconds"),
                    recomputedRows.get(i).get("residualSeconds"));
        }
    }

    @Test
    void frozenInterpretationCanBranchWithReplacementModelAndKeepsHistory() {
        post("/api/admin/reset", Map.of());
        post("/api/demo/seed?force=true", Map.of());
        post("/api/interpretations/I-REVIEW/freeze", Map.of());
        Map<String, Object> frozenDetail = get("/api/interpretations/I-REVIEW");
        int originalEvents = ((List<?>) frozenDetail.get("events")).size();
        assertTrue(originalEvents > 0);

        post("/api/interpretations/I-REVIEW/branch",
                Map.of("code", "I-BRANCH-B", "name", "替换 VM-B 分支", "modelId", 2));
        Map<String, Object> branch = get("/api/interpretations/I-BRANCH-B");
        @SuppressWarnings("unchecked")
        Map<String, Object> branchInterp =
                (Map<String, Object>) branch.get("interpretation");
        assertEquals(2, branchInterp.get("modelId"));
        assertEquals(false, branchInterp.get("frozen"));
        assertEquals(((List<?>) frozenDetail.get("picks")).size(),
                ((List<?>) branch.get("picks")).size());
        assertEquals(originalEvents, ((List<?>) branch.get("events")).size());

        // Branch is open to further undoable actions.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picks = (List<Map<String, Object>>) branch.get("picks");
        long pickId = ((Number) picks.get(0).get("id")).longValue();
        post("/api/interpretations/I-BRANCH-B/picks/" + pickId + "/status",
                Map.of("status", "NOISE"));
        Map<String, Object> changed = get("/api/interpretations/I-BRANCH-B");
        @SuppressWarnings("unchecked")
        Map<String, Object> changedPick =
                ((List<Map<String, Object>>) changed.get("picks")).stream()
                        .filter(p -> ((Number) p.get("id")).longValue() == pickId)
                        .findFirst().orElseThrow();
        assertEquals("NOISE", changedPick.get("status"));
    }

    @Test
    void interpretationPinsIndependentStationPickAndModelVersions() {
        post("/api/admin/reset", Map.of());
        post("/api/demo/seed?force=true", Map.of());
        Map<String, Object> detail = get("/api/interpretations/I-REVIEW");
        @SuppressWarnings("unchecked")
        Map<String, Object> interp = (Map<String, Object>) detail.get("interpretation");
        @SuppressWarnings("unchecked")
        Map<String, Object> stationVersions =
                (Map<String, Object>) interp.get("stationVersions");
        @SuppressWarnings("unchecked")
        Map<String, Object> trackVersions =
                (Map<String, Object>) interp.get("trackVersions");
        assertEquals(1, stationVersions.get("S01"));
        assertTrue(trackVersions.containsKey("S01|BHZ"));
        assertTrue(((Integer) interp.get("pickVersion")) > 0);
        assertEquals(1, interp.get("modelId"));
    }
}
