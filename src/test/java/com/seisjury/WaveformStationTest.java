package com.seisjury;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
class WaveformStationTest {

    @Autowired
    private MockMvc mvc;

    private Fixture fixture;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        fixture = new Fixture(mvc);
    }

    @Test
    void stationElevationCreatesNewVersionAndLatestIsDeterministic() throws Exception {
        fixture.seedCatalog();
        JsonNode stations = fixture.getJson("/api/stations");
        assertThat(stations).hasSize(3);
        assertThat(stations.get(0).get("stationCode").asText()).isEqualTo("S00");

        JsonNode second = fixture.addStation("S00", 35.69, 139.77, 999.0);
        assertThat(second.get("version").asInt()).isEqualTo(2);
        JsonNode latest = fixture.getJson("/api/stations");
        JsonNode s00 = latest.get(0);
        assertThat(s00.get("version").asInt()).isEqualTo(2);
        assertThat(s00.get("elevationM").asDouble()).isEqualTo(999.0);
        assertThat(s00.get("globalVersion").asLong()).isGreaterThan(
                latest.get(1).get("globalVersion").asLong());
    }

    @Test
    void duplicateSegmentIsDeduplicatedButReceptionCountAccumulates() throws Exception {
        fixture.seedCatalog();
        List<Double> samples = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        JsonNode first = fixture.uploadWaveform("S00", "HHZ", "clip.mseed",
                Fixture.T0, 40.0, samples, 0.0);
        assertThat(first.get("deduplicated").asBoolean()).isFalse();
        JsonNode second = fixture.uploadWaveform("S00", "HHZ", "clip.mseed",
                Fixture.T0, 40.0, samples, 0.0);
        assertThat(second.get("deduplicated").asBoolean()).isTrue();
        assertThat(second.get("segmentId").asLong()).isEqualTo(first.get("segmentId").asLong());
        assertThat(second.get("receptionCount").asInt()).isEqualTo(2);
        JsonNode receptions = fixture.getJson("/api/receptions?segKey=S00%7CHHZ%7Cclip.mseed");
        assertThat(receptions).hasSize(2);
    }

    @Test
    void changedWaveformCreatesNewVersionAndDetectsClipping() throws Exception {
        fixture.seedCatalog();
        List<Double> samplesA = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> samplesB = List.of(1.0, 2.0, 3.0, 4.0, 5.0001);
        JsonNode a = fixture.uploadWaveform("S00", "HHZ", "clip.mseed",
                Fixture.T0, 40.0, samplesA, 0.0);
        JsonNode b = fixture.uploadWaveform("S00", "HHZ", "clip.mseed",
                Fixture.T0, 40.0, samplesB, 0.0);
        assertThat(b.get("deduplicated").asBoolean()).isFalse();
        assertThat(b.get("version").asInt()).isEqualTo(2);

        // 100 Hz: a plateau of 3 equal max samples => 20 ms >= 1 ms threshold
        List<Double> clipped = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            clipped.add(i >= 8 && i <= 10 ? 50.0 : (i % 2 == 0 ? -5.0 : 5.0));
        }
        JsonNode clip = fixture.uploadWaveform("S01", "HHZ", "clip2.mseed",
                Fixture.T0, 100.0, clipped, 0.0);
        assertThat(clip.get("clipped").asBoolean()).isTrue();
    }

    @Test
    void unequalSampleRatesAndGapBoundariesAreReported() throws Exception {
        fixture.seedCatalog();
        fixture.uploadWaveform("S00", "HHZ", "a.mseed", Fixture.T0, 40.0,
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.0);
        // last sample at T0 + 100 ms; next segment at 40 Hz starts at T0 + 1100 ms (gap 1000 ms)
        fixture.uploadWaveform("S00", "HHZ", "b.mseed", Fixture.T0 + 1100, 40.0,
                List.of(2.0, 3.0, 4.0, 5.0, 6.0), 0.0);
        // overlapping segment on another channel: last sample at 100ms, next starts at 50ms
        fixture.uploadWaveform("S00", "HHE", "c.mseed", Fixture.T0, 40.0,
                List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.0);
        fixture.uploadWaveform("S00", "HHE", "d.mseed", Fixture.T0 + 50, 40.0,
                List.of(2.0, 3.0, 4.0, 5.0, 6.0), 0.0);

        JsonNode coverage = fixture.getJson("/api/waveforms/coverage?stationCode=S00");
        assertThat(coverage).hasSize(2);
        JsonNode gap = coverage.get(0);
        JsonNode overlap = coverage.get(1);
        assertThat(gap.get("channel").asText()).isEqualTo("HHZ");
        assertThat(gap.get("kind").asText()).isEqualTo("GAP");
        assertThat(gap.get("durationMs").asLong()).isEqualTo(1000L);
        assertThat(overlap.get("kind").asText()).isEqualTo("OVERLAP");
        assertThat(overlap.get("durationMs").asLong()).isEqualTo(50L);
    }
}
