package com.seisjury;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Component
public class Fixture {

    public static final long T0 = 1_700_000_000_000L;

    private final MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    public Fixture(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public MvcResult post(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
    }

    public MvcResult postOk(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
    }

    public JsonNode getJson(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    public JsonNode postJson(String url, Object body) throws Exception {
        MvcResult result = post(url, body);
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** Event near (35.68, 139.76), 30 km depth; three stations at distinct distances. */
    public void seedCatalog() throws Exception {
        postOk("/api/events", java.util.Map.of(
                "code", "EV1", "originMs", T0,
                "latitude", 35.68, "longitude", 139.76, "depthKm", 30.0));
        addStation("S00", 35.69, 139.77, 50.0);
        addStation("S01", 35.75, 139.80, 120.0);
        addStation("S02", 36.20, 140.20, 30.0);
    }

    public JsonNode addStation(String code, double lat, double lon, double elevation) throws Exception {
        return postJson("/api/stations", java.util.Map.of(
                "code", code, "latitude", lat, "longitude", lon, "elevationM", elevation));
    }

    /** Slow and fast layered models, both with explicit layer bottoms. */
    public void seedModels() throws Exception {
        postOk("/api/velocity-models", java.util.Map.of("code", "SLOW", "layers", List.of(
                List.of(-1.0, 35.0, 5.8, 3.4),
                List.of(35.0, 200.0, 8.0, 4.6))));
        postOk("/api/velocity-models", java.util.Map.of("code", "FAST", "layers", List.of(
                List.of(-1.0, 35.0, 6.2, 3.6),
                List.of(35.0, 200.0, 8.4, 4.8))));
    }

    public JsonNode uploadWaveform(String station, String channel, String fileTag,
                                   long startMs, double rate, List<Double> samples,
                                   double clockOffsetMs) throws Exception {
        return postJson("/api/waveforms", java.util.Map.of(
                "stationCode", station, "channel", channel, "fileTag", fileTag,
                "startMs", startMs, "sampleRateHz", rate, "samples", samples,
                "source", "SEED-FIXTURE", "clockOffsetMs", clockOffsetMs));
    }

    public JsonNode createInterp(String code, String modelCode) throws Exception {
        return postJson("/api/interpretations", java.util.Map.of(
                "code", code, "eventCode", "EV1", "velocityModelCode", modelCode));
    }

    public JsonNode addCandidate(String interp, String station, String channel, String phase,
                                 long arrivalMs, long ciHalfWidthMs, double weight, String source)
            throws Exception {
        return postJson("/api/interpretations/" + interp + "/actions", java.util.Map.of(
                "type", "ADD",
                "candidate", java.util.Map.of(
                        "stationCode", station, "channel", channel, "phase", phase,
                        "arrivalMs", arrivalMs, "polarity", "UP",
                        "confidenceLowMs", arrivalMs - ciHalfWidthMs,
                        "confidenceHighMs", arrivalMs + ciHalfWidthMs,
                        "weight", weight, "source", source)));
    }

    public List<Double> ramp(int n, double rateHz) {
        java.util.List<Double> values = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double value = Math.sin(i * 0.35) * 100;
            if (i > n * 0.4 && i < n * 0.4 + Math.max(2, (int) Math.round(rateHz * 0.002))) {
                value = 100.0;
            }
            values.add(value);
        }
        return values;
    }
}
