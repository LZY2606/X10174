package com.gsb.phaseroom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "phaseroom.database=build/test-data/phase-room-test.db")
class PhaseRoomIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private Map<String, Object> body(ResponseEntity<Map> response) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = response.getBody();
        assertNotNull(map);
        return map;
    }

    private Map<String, Object> post(String path, Object payload) {
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(payload), Map.class);
        if (response.getStatusCode().isError()) {
            throw new AssertionError("POST " + path + " -> " + response.getBody());
        }
        return body(response);
    }

    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = rest.getForEntity(path, Map.class);
        if (response.getStatusCode().isError()) {
            throw new AssertionError("GET " + path + " -> " + response.getBody());
        }
        return body(response);
    }

    private Map<String, Object> postRaw(String path, Object payload) {
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(payload), Map.class);
        return body(response);
    }

    @Test
    void homePageShowsChineseTitle() {
        String html = rest.getForObject("/", String.class);
        assertTrue(html.contains("震相合议室"));
        assertTrue(html.contains("app.js"));
    }

    @Test
    void fixedVirtualDataIsDeterministic() {
        post("/api/admin/reset", Map.of());
        Map<String, Object> seeded = post("/api/demo/seed?force=true", Map.of());
        assertEquals(true, seeded.get("seeded"));
        assertEquals(4, seeded.get("stations"));
        assertEquals(3, seeded.get("models"));
        List<?> stations = rest.exchange("/api/stations", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {
                }).getBody();
        List<?> models = rest.exchange("/api/velocity-models", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {
                }).getBody();
        assertNotNull(stations);
        assertNotNull(models);
        assertEquals(4, stations.size());
        assertEquals(3, models.size());
        // Fixed order for every result set.
        assertEquals("S01", ((Map<?, ?>) stations.get(0)).get("code"));
        assertEquals("VM-A", ((Map<?, ?>) models.get(0)).get("code"));
    }

    @Test
    void duplicateWaveformContentIsDeduplicatedButReceiptsAccumulate() {
        post("/api/admin/reset", Map.of());
        post("/api/demo/seed?force=true", Map.of());
        List<Double> samples = List.of(0.1, 0.2, -0.3, 0.0, 0.12, 0.2, -0.3);
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "stationCode", "SX1", "channel", "BHZ", "startTime", 2000000.0,
                "sampleRateHz", 40.0, "samples", samples, "clockOffsetS", 0.0,
                "sourceName", "TEST-A"));
        Map<String, Object> first = post("/api/waveforms/segments", payload);
        Map<String, Object> secondPayload = new LinkedHashMap<>(payload);
        secondPayload.put("sourceName", "TEST-B");
        Map<String, Object> second = post("/api/waveforms/segments", secondPayload);
        assertEquals(false, first.get("duplicated"));
        assertEquals(true, second.get("duplicated"));
        assertEquals(first.get("contentId"), second.get("contentId"));
        Map<String, Object> timeline = get(
                "/api/waveforms/tracks/SX1/BHZ/timeline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = (List<Map<String, Object>>) timeline.get("segments");
        assertEquals(1, segments.size());
        assertEquals(2, segments.get(0).get("receivedCount"));
    }

    @Test
    void gapBoundariesUnequalRatesAndClippingAreReported() {
        post("/api/admin/reset", Map.of());
        post("/api/demo/seed?force=true", Map.of());
        Map<String, Object> timeline = get("/api/waveforms/tracks/S01/BHZ/timeline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments =
                (List<Map<String, Object>>) timeline.get("segments");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> features =
                (List<Map<String, Object>>) timeline.get("features");
        assertEquals(40.0, ((Number) segments.get(0).get("sampleRateHz")).doubleValue());
        assertTrue(features.stream().anyMatch(f -> "GAP".equals(f.get("kind"))));

        Map<String, Object> bhz = get("/api/waveforms/tracks/S01/BHZ/timeline");
        Map<String, Object> bhn = get("/api/waveforms/tracks/S01/BHN/timeline");
        @SuppressWarnings("unchecked")
        double rateZ = ((Number) ((Map<String, Object>)
                ((List<?>) bhz.get("segments")).get(0)).get("sampleRateHz")).doubleValue();
        @SuppressWarnings("unchecked")
        double rateN = ((Number) ((Map<String, Object>)
                ((List<?>) bhn.get("segments")).get(0)).get("sampleRateHz")).doubleValue();
        assertNotEquals(rateZ, rateN);

        Map<String, Object> s02 = get("/api/waveforms/tracks/S02/BHZ/timeline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> s02Segments =
                (List<Map<String, Object>>) s02.get("segments");
        assertTrue(s02Segments.stream().anyMatch(s -> Boolean.TRUE.equals(s.get("clipped"))));
        assertTrue(((List<?>) s02.get("features")).stream()
                .anyMatch(f -> "OVERLAP".equals(((Map<?, ?>) f).get("kind"))));
    }

    @Test
    void changedWaveformFileCreatesNewTrackVersion() {
        post("/api/admin/reset", Map.of());
        post("/api/demo/seed?force=true", Map.of());
        Map<String, Object> first = post("/api/waveforms/segments", new LinkedHashMap<>(Map.of(
                "stationCode", "SV1", "channel", "BHZ", "startTime", 3000000.0,
                "sampleRateHz", 20.0, "samples", List.of(0.1, 0.2, 0.3),
                "sourceName", "V1")));
        Map<String, Object> changed = post("/api/waveforms/segments", new LinkedHashMap<>(Map.of(
                "stationCode", "SV1", "channel", "BHZ", "startTime", 3000005.0,
                "sampleRateHz", 20.0, "samples", List.of(0.1, 0.2, 0.31),
                "sourceName", "V2")));
        assertEquals(1, first.get("trackVersion"));
        assertEquals(2, changed.get("trackVersion"));
        assertEquals(true, changed.get("newContent"));
    }
}
