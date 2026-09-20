package com.gsb.phaseroom.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic virtual data: unequal sample rates, a gap, overlap, clipping and two models. */
@Service
public class DemoData {

    public static final String EVENT_CODE = "EV-20260901";
    public static final double ORIGIN = 1_000_000.0;

    private final Stations stations;
    private final Events events;
    private final VelocityModels velocityModels;
    private final Waveforms waveforms;
    private final Interpretations interpretations;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DemoData(Stations stations, Events events, VelocityModels velocityModels,
                    Waveforms waveforms, Interpretations interpretations,
                    org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.stations = stations;
        this.events = events;
        this.velocityModels = velocityModels;
        this.waveforms = waveforms;
        this.interpretations = interpretations;
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> seed(boolean force) {
        if (!force && !interpretations.list().isEmpty()) {
            return Map.of("seeded", false, "reason", "数据库已存在解释");
        }
        if (force) {
            resetTables();
        }

        events.create(EVENT_CODE, ORIGIN, 35.0, 139.0, 20.0);

        stations.put("S01", "凌云", 35.30, 139.10, 120.0);
        stations.put("S02", "潮见", 34.80, 139.40, 40.0);
        stations.put("S03", "青野", 35.10, 138.60, 260.0);
        stations.put("S04", "北岭", 35.60, 139.80, 10.0);

        velocityModels.create("VM-A", "标准四层模型", List.of(
                new double[]{0.0, 5.8, 3.4},
                new double[]{5.0, 6.2, 3.6},
                new double[]{15.0, 6.8, 3.9},
                new double[]{30.0, 8.0, 4.6}));
        velocityModels.create("VM-B", "快速上地幔模型", List.of(
                new double[]{0.0, 5.6, 3.2},
                new double[]{4.0, 6.5, 3.8},
                new double[]{12.0, 7.2, 4.1},
                new double[]{25.0, 8.4, 4.8}));
        // Third deterministic model: a much faster lithosphere profile so that the far
        // station S04 stays inside the acceptance window while VM-A/VM-B remain rejected.
        velocityModels.create("VM-C", "拟合快速岩石圈模型", List.of(
                new double[]{0.0, 6.4, 3.7},
                new double[]{4.0, 7.4, 4.3},
                new double[]{12.0, 8.2, 4.8},
                new double[]{25.0, 8.9, 5.1}));

        // S01: unequal rates across channels and an explicit gap on BHZ.
        seedWaveform("S01", "BHZ", 40.0, ORIGIN + 2, 160, 0.0, false);
        seedWaveform("S01", "BHZ", 40.0, ORIGIN + 14, 160, 0.0, false);
        seedWaveform("S01", "BHN", 25.0, ORIGIN + 2, 180, 0.0, false);
        // S02: overlapping BHZ segments; later one clips.
        seedWaveform("S02", "BHZ", 50.0, ORIGIN + 4, 400, 0.0, false);
        seedWaveform("S02", "BHZ", 50.0, ORIGIN + 9, 400, 0.0, true);
        seedWaveform("S02", "BHN", 50.0, ORIGIN + 6, 400, 0.0, false);
        // S03: clock offset embedded in the imported segment.
        seedWaveform("S03", "BHZ", 40.0, ORIGIN + 5, 240, 0.35, false);
        seedWaveform("S03", "BHN", 40.0, ORIGIN + 8, 240, 0.35, false);
        // S04: clean, different rate, long segments covering the far-arriving picks.
        seedWaveform("S04", "BHZ", 33.3, ORIGIN + 6, 420, 0.0, false);
        seedWaveform("S04", "BHN", 20.0, ORIGIN + 9, 420, 0.0, false);

        interpretations.create("I-REVIEW", "主事件复核", EVENT_CODE,
                velocityModels.get(1).id());
        addPicksForReview();

        return Map.of("seeded", true, "event", EVENT_CODE, "stations", 4, "models", 3);
    }

    private void addPicksForReview() {
        // Predicted P arrivals are around origin+5..10; pick times are chosen to match.
        interpretations.addPick("I-REVIEW", "S01", "P", ORIGIN + 6.0, "+", 0.20,
                "ANALYST", true);
        interpretations.addPick("I-REVIEW", "S01", "P", ORIGIN + 6.8, "?", 0.45,
                "ALGORITHM", false);
        interpretations.addPick("I-REVIEW", "S02", "P", ORIGIN + 7.0, "+", 0.15,
                "ANALYST", true);
        interpretations.addPick("I-REVIEW", "S03", "P", ORIGIN + 7.4, "-", 0.30,
                "ANALYST", true);
        interpretations.addPick("I-REVIEW", "S04", "P", ORIGIN + 13.5, "?", 0.40,
                "ALGORITHM", true);
        interpretations.addPick("I-REVIEW", "S01", "S", ORIGIN + 10.8, "+", 0.35,
                "ANALYST", true);
        interpretations.addPick("I-REVIEW", "S02", "S", ORIGIN + 12.4, "+", 0.30,
                "ANALYST", true);
        interpretations.addPick("I-REVIEW", "S03", "S", ORIGIN + 13.2, "-", 0.35,
                "ANALYST", true);
        // Deliberately early S pick: fits VM-C within the window but rejects VM-A/VM-B,
        // proving an out-of-window model can never win on a smaller mean alone.
        interpretations.addPick("I-REVIEW", "S04", "S", ORIGIN + 21.2, "?", 0.50,
                "ALGORITHM", true);
    }

    static List<Double> synthetic(double startTime, double rate, int count, double pickTime,
                                  boolean clip) {
        List<Double> samples = new ArrayList<>(count);
        double peak = clip ? 0.9 : 1.0;
        for (int i = 0; i < count; i++) {
            double t = startTime + i / rate;
            double dt = t - pickTime;
            double pulse = Math.exp(-Math.pow(dt * 6.0, 2)) * (clip ? 1.2 : 1.0);
            double wave = pulse * peak
                    + 0.03 * Math.sin(t * 7.3 + startTime)
                    + 0.015 * Math.sin(t * 19.7 + rate);
            if (clip && wave > 1.0) {
                wave = 1.0;
            } else if (clip && wave < -1.0) {
                wave = -1.0;
            }
            samples.add(Math.rint(wave * 1_000_000.0) / 1_000_000.0);
        }
        return samples;
    }

    private void seedWaveform(String station, String channel, double rate, double start,
                              int count, double offset, boolean clip) {
        double pulseOffset = "S04".equals(station)
                ? ("S".equals(channel) ? 12.5 : 7.5)
                : ("S".equals(channel) ? 7.0 : 4.0);
        double pickTime = start + pulseOffset;
        List<Double> samples = synthetic(start, rate, count, pickTime, clip);
        waveforms.importSegment(station, channel, start, rate, samples, offset, clip,
                "DEMO-SEEDER");
    }

    private void resetTables() {
        List<String> tables = List.of("action_event", "clock_correction", "pick",
                "interpretation", "import_receipt", "waveform_segment", "waveform_content",
                "waveform_track", "velocity_layer", "velocity_model", "seismic_event",
                "station");
        for (String table : tables) {
            jdbc.update("DELETE FROM " + table);
            jdbc.update("DELETE FROM sqlite_sequence WHERE name = ?", table);
        }
    }
}
