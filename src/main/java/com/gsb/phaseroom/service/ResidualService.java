package com.gsb.phaseroom.service;

import com.gsb.phaseroom.config.AppProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ResidualService {

    public record StationPick(String stationCode, String phase, double observedTime,
                              double rawPickTime, double segmentClockOffset,
                              double appliedShift, double ciHalfWidth, String source,
                              boolean clipped, double weight, Map<String, Object> weightFactors) {
    }

    private final Stations stations;
    private final Events events;
    private final VelocityModels velocityModels;
    private final Waveforms waveforms;
    private final Weights weights;
    private final Interpretations interpretations;
    private final AppProperties props;

    public ResidualService(Stations stations, Events events, VelocityModels velocityModels,
                           Waveforms waveforms, Weights weights,
                           Interpretations interpretations, AppProperties props) {
        this.stations = stations;
        this.events = events;
        this.velocityModels = velocityModels;
        this.waveforms = waveforms;
        this.weights = weights;
        this.interpretations = interpretations;
        this.props = props;
    }

    public double windowSeconds() {
        return props.getWindowSeconds();
    }

    private StationPick toStationPick(Interpretations.Interpretation interp,
                                      Interpretations.Pick pick) {
        String channel = "S".equals(pick.phase()) ? "BHN" : "BHZ";
        double offset = waveforms.clockOffsetAt(pick.stationCode(), channel, pick.time());
        if (Double.compare(offset, 0) == 0 && !"S".equals(pick.phase())) {
            offset = waveforms.clockOffsetAt(pick.stationCode(), "BHZ", pick.time());
        }
        double shift = interpretations.corrections(interp.id())
                .getOrDefault(pick.stationCode(), 0.0);
        double observed = pick.time() + offset + shift;
        Map<String, Object> weightExplanation = weights.explain(pick.stationCode(), channel,
                pick.time(), pick.source(), pick.ciHalfWidth(), shift);
        double weight = ((Number) weightExplanation.get("weight")).doubleValue();
        return new StationPick(pick.stationCode(), pick.phase(), observed, pick.time(), offset,
                shift, pick.ciHalfWidth(), pick.source(),
                waveforms.isClippedAt(pick.stationCode(), channel, pick.time()), weight,
                (Map<String, Object>) weightExplanation.get("factors"));
    }

    /** Compute all selected-pick residuals for an interpretation under a model version. */
    public Map<String, Object> residualsForModel(Interpretations.Interpretation interp,
                                                 VelocityModels.Model model) {
        Events.SeismicEvent event = events.byId(interp.eventId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Interpretations.Pick pick : interpretations.selectedPicks(interp.id())) {
            Integer stationVersion = interp.stationVersions().get(pick.stationCode());
            Stations.Station station = stations.getVersion(pick.stationCode(), stationVersion);
            double distance = Geo.surfaceDistanceKm(event.lat(), event.lon(), station.lat(),
                    station.lon());
            double travel = velocityModels.travelTimeSeconds(model, distance, event.depthKm(),
                    station.elevationM(), pick.phase());
            double predicted = event.originTime() + travel;
            StationPick sp = toStationPick(interp, pick);
            double residual = predicted - sp.observedTime();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stationCode", station.code());
            row.put("stationVersion", station.version());
            row.put("phase", pick.phase());
            row.put("pickId", pick.id());
            row.put("distanceKm", round(distance));
            row.put("predictedTime", round(predicted));
            row.put("observedTime", round(sp.observedTime()));
            row.put("rawPickTime", round(sp.rawPickTime()));
            row.put("segmentClockOffset", round(sp.segmentClockOffset()));
            row.put("appliedShift", round(sp.appliedShift()));
            row.put("travelTimeSeconds", round(travel));
            row.put("residualSeconds", round(residual));
            row.put("absResidualSeconds", round(Math.abs(residual)));
            row.put("ciHalfWidthSeconds", round(pick.ciHalfWidth()));
            row.put("withinWindow", Math.abs(residual) <= props.getWindowSeconds());
            row.put("weight", sp.weight());
            row.put("weightFactors", sp.weightFactors());
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("stationCode"))
                .thenComparing(r -> (String) r.get("phase")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelId", model.id());
        out.put("modelCode", model.code());
        out.put("modelVersion", model.version());
        out.put("windowSeconds", props.getWindowSeconds());
        out.put("rows", rows);
        return out;
    }

    /**
     * Deterministic model comparison. A model with any outside-window selected pick can never
     * win, even when its mean residual is smaller. Among eligible models, lowest weighted
     * mean absolute residual wins; ties resolve by unweighted mean, outside-window count
     * (ascending), then model code.
     */
    public Map<String, Object> compare(String interpCode, List<Long> modelIds) {
        Interpretations.Interpretation interp = interpretations.get(interpCode);
        List<Map<String, Object>> models = new ArrayList<>();
        for (Long modelId : modelIds) {
            VelocityModels.Model model = velocityModels.get(modelId);
            Map<String, Object> residual = residualsForModel(interp, model);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) residual.get("rows");
            double weightedAbsSum = 0;
            double weightSum = 0;
            double absSum = 0;
            int outside = 0;
            for (Map<String, Object> row : rows) {
                double abs = ((Number) row.get("absResidualSeconds")).doubleValue();
                double weight = ((Number) row.get("weight")).doubleValue();
                weightedAbsSum += abs * weight;
                weightSum += weight;
                absSum += abs;
                if (!Boolean.TRUE.equals(row.get("withinWindow"))) {
                    outside++;
                }
            }
            double weightedMean = weightSum > 0 ? weightedAbsSum / weightSum : Double.POSITIVE_INFINITY;
            double mean = rows.isEmpty() ? Double.POSITIVE_INFINITY : absSum / rows.size();
            Map<String, Object> summary = new LinkedHashMap<>(residual);
            summary.put("eligible", outside == 0 && !rows.isEmpty());
            summary.put("outsideWindowCount", outside);
            summary.put("weightedMeanAbsResidual", round(weightedMean));
            summary.put("meanAbsResidual", round(mean));
            models.add(summary);
        }
        models.sort((left, right) -> {
            boolean leftEligible = Boolean.TRUE.equals(left.get("eligible"));
            boolean rightEligible = Boolean.TRUE.equals(right.get("eligible"));
            if (leftEligible != rightEligible) {
                return leftEligible ? -1 : 1;
            }
            int byWeight = Double.compare(
                    ((Number) left.get("weightedMeanAbsResidual")).doubleValue(),
                    ((Number) right.get("weightedMeanAbsResidual")).doubleValue());
            if (byWeight != 0) {
                return byWeight;
            }
            int byMean = Double.compare(
                    ((Number) left.get("meanAbsResidual")).doubleValue(),
                    ((Number) right.get("meanAbsResidual")).doubleValue());
            if (byMean != 0) {
                return byMean;
            }
            int byOutside = Integer.compare(
                    ((Number) left.get("outsideWindowCount")).intValue(),
                    ((Number) right.get("outsideWindowCount")).intValue());
            if (byOutside != 0) {
                return byOutside;
            }
            return ((String) left.get("modelCode"))
                    .compareTo((String) right.get("modelCode"));
        });
        Map<String, Object> winner = models.isEmpty() ? null : models.get(0);
        boolean winnerEligible = winner != null && Boolean.TRUE.equals(winner.get("eligible"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interpretationCode", interpCode);
        result.put("windowSeconds", props.getWindowSeconds());
        result.put("models", models);
        result.put("winnerModelId", winnerEligible ? winner.get("modelId") : null);
        result.put("winnerModelCode", winnerEligible ? winner.get("modelCode") : null);
        result.put("rule", "超出残差窗口的模型一票否决；其后按加权平均绝对残差升序，平局按模型代码字典序");
        return result;
    }

    /** Per-station predicted/observed differences between two models. */
    public Map<String, Object> stationDiffs(String interpCode, long modelAId, long modelBId) {
        Interpretations.Interpretation interp = interpretations.get(interpCode);
        Map<String, Object> a = residualsForModel(interp, velocityModels.get(modelAId));
        Map<String, Object> b = residualsForModel(interp, velocityModels.get(modelBId));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsA = (List<Map<String, Object>>) a.get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsB = (List<Map<String, Object>>) b.get("rows");
        Map<String, List<Map<String, Object>>> byKeyA = keyRows(rowsA);
        Map<String, List<Map<String, Object>>> byKeyB = keyRows(rowsB);
        List<String> keys = new ArrayList<>();
        keys.addAll(byKeyA.keySet());
        for (String key : byKeyB.keySet()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        keys.sort(String::compareTo);
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> ra = byKeyA.getOrDefault(key, List.of()).stream().findFirst().orElse(null);
            Map<String, Object> rb = byKeyB.getOrDefault(key, List.of()).stream().findFirst().orElse(null);
            Map<String, Object> diff = new LinkedHashMap<>();
            String[] parts = key.split("\\|", 2);
            diff.put("stationCode", parts[0]);
            diff.put("phase", parts[1]);
            diff.put("modelA", brief(ra));
            diff.put("modelB", brief(rb));
            if (ra != null && rb != null) {
                double predDiff = ((Number) rb.get("predictedTime")).doubleValue()
                        - ((Number) ra.get("predictedTime")).doubleValue();
                double residualDiff = ((Number) rb.get("residualSeconds")).doubleValue()
                        - ((Number) ra.get("residualSeconds")).doubleValue();
                diff.put("predictedDifferenceSeconds", round(predDiff));
                diff.put("residualDifferenceSeconds", round(residualDiff));
                diff.put("observedDifferenceSeconds", 0.0);
            }
            diffs.add(diff);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interpretationCode", interpCode);
        result.put("modelAId", modelAId);
        result.put("modelBId", modelBId);
        result.put("diffs", diffs);
        return result;
    }

    private Map<String, List<Map<String, Object>>> keyRows(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            map.computeIfAbsent(row.get("stationCode") + "|" + row.get("phase"),
                    k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    private Map<String, Object> brief(Map<String, Object> row) {
        if (row == null) {
            return Map.of("missing", true);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("predictedTime", row.get("predictedTime"));
        out.put("observedTime", row.get("observedTime"));
        out.put("residualSeconds", row.get("residualSeconds"));
        out.put("withinWindow", row.get("withinWindow"));
        out.put("weight", row.get("weight"));
        out.put("weightFactors", row.get("weightFactors"));
        return out;
    }

    public static double round(double value) {
        return Weights.round(value);
    }
}
