package com.gsb.phaseroom.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes an immutable bundle pinning the exact pick + station-metadata + velocity-model +
 * waveform version triple and replays it. Later station elevation changes never alter a
 * frozen conclusion because replay resolves all metadata through the pinned versions.
 */
@Service
public class FrozenService {

    private final JdbcTemplate jdbc;
    private final Interpretations interpretations;
    private final Events events;
    private final VelocityModels models;
    private final ResidualService residuals;
    private final FreezeService freeze;

    public FrozenService(JdbcTemplate jdbc, Interpretations interpretations, Events events,
                         VelocityModels models, ResidualService residuals, FreezeService freeze) {
        this.jdbc = jdbc;
        this.interpretations = interpretations;
        this.events = events;
        this.models = models;
        this.residuals = residuals;
        this.freeze = freeze;
    }

    @Transactional
    public Map<String, Object> freeze(String interpCode) {
        Interpretations.Interpretation interp = interpretations.get(interpCode);
        Map<String, Object> conflict = freeze.analyse(interp.id());
        if (Boolean.TRUE.equals(conflict.get("reversed"))) {
            Map<String, Object> blocked = new LinkedHashMap<>();
            blocked.put("blocked", true);
            blocked.put("reason", "校时修正会反转台站 P 震相先后次序，已阻止冻结");
            blocked.put("analysis", conflict);
            return blocked;
        }
        Map<String, Object> bundle = bundle(interp);
        return freeze.freeze(interpCode, bundle);
    }

    public Map<String, Object> bundle(Interpretations.Interpretation interp) {
        VelocityModels.Model model = models.get(interp.modelId());
        Map<String, Object> residual = residuals.residualsForModel(interp, model);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("interpretationCode", interp.code());
        bundle.put("frozenPickVersion", interp.pickVersion());
        bundle.put("modelVersion", Map.of("id", model.id(), "code", model.code(),
                "version", model.version()));
        bundle.put("stationVersions", interp.stationVersions());
        bundle.put("trackVersions", interp.trackVersions());
        bundle.put("eventId", interp.eventId());
        bundle.put("corrections", interpretations.corrections(interp.id()));
        bundle.put("residuals", residual);
        bundle.put("ordering", freeze.analyse(interp.id()));
        return bundle;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> replay(String interpCode) {
        Interpretations.Interpretation current = interpretations.get(interpCode);
        if (!current.frozen()) {
            throw new IllegalStateException("仅冻结解释可以重放: " + interpCode);
        }
        String stored = jdbc.queryForObject(
                "SELECT frozen_bundle FROM interpretation WHERE id = ?", String.class,
                current.id());
        Map<String, Object> pinned = Json.read(stored, new com.fasterxml.jackson.core.type
                .TypeReference<LinkedHashMap<String, Object>>() {
        });
        long modelId = ((Number) ((Map<String, Object>) pinned.get("modelVersion")).get("id"))
                .longValue();
        int pinnedModelVersion = ((Number) ((Map<String, Object>) pinned.get("modelVersion"))
                .get("version")).intValue();
        VelocityModels.Model modelNow = models.get(modelId);

        Map<String, Object> recomputed = residuals.residualsForModel(
                new Interpretations.Interpretation(current.id(), current.code(), current.name(),
                        current.eventId(), current.modelId(),
                        (Map<String, Integer>) pinned.get("stationVersions"),
                        (Map<String, Integer>) pinned.get("trackVersions"),
                        ((Number) pinned.get("frozenPickVersion")).intValue(),
                        current.frozen(), current.parentId()),
                modelNow);

        List<Double> oldResiduals = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>)
                ((Map<String, Object>) pinned.get("residuals")).get("rows")) {
            oldResiduals.add(((Number) row.get("residualSeconds")).doubleValue());
        }
        List<Double> newResiduals = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) recomputed.get("rows")) {
            newResiduals.add(((Number) row.get("residualSeconds")).doubleValue());
        }
        boolean identical = oldResiduals.equals(newResiduals);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interpretationCode", interpCode);
        result.put("pinnedModelVersion", pinnedModelVersion);
        result.put("currentModelVersion", modelNow.version());
        result.put("pickedVersions", Map.of(
                "pickVersion", pinned.get("frozenPickVersion"),
                "stationVersions", pinned.get("stationVersions"),
                "trackVersions", pinned.get("trackVersions")));
        result.put("pinnedResiduals", ((Map<String, Object>) pinned.get("residuals")).get("rows"));
        result.put("recomputedResiduals", recomputed.get("rows"));
        result.put("identical", identical);
        result.put("note",
                "重放始终通过冻结时钉住的台站与模型版本计算；修改当前台站海拔不改变本结果。");
        return result;
    }
}
