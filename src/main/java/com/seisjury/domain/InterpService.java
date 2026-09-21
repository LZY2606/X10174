package com.seisjury.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seisjury.db.Repositories;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterpService {

    private final Repositories repo;
    private final Clock clock;
    private final ObjectMapper mapper;

    public InterpService(Repositories repo, Clock clock, ObjectMapper mapper) {
        this.repo = repo;
        this.clock = clock;
        this.mapper = mapper;
    }

    public static final class ActionRequest {
        public String type;
        public PickLogic.Candidate candidate;
        public Long targetId;
        public Long survivorId;
        public List<Long> mergedIds;
        public String stationCode;
        public Long afterMs;
        public String polarity;
        public Long arrivalMs;
        public Long confidenceLowMs;
        public Long confidenceHighMs;
        public Double weight;
        public String source;
        public String note;
    }

    public record StationPrediction(String stationCode, int stationVersion, long globalVersion,
                                    double distanceKm, Double pMs, Double sMs) {
    }

    // ---------- interpretation lifecycle ----------

    @Transactional
    public Repositories.InterpRow create(String code, String eventCode, String modelCode) {
        if (repo.interp(code) != null) {
            throw new ConflictException("interpretation already exists: " + code);
        }
        Repositories.EventRow event = requireEvent(eventCode);
        Repositories.ModelRow model = modelOrThrow(modelCode, null);
        long id = repo.insertInterp(code, event.eventCode(), model.id(), null, clock.nowMs());
        return repo.interpById(id);
    }

    @Transactional
    public Repositories.InterpRow branch(String sourceCode, String newCode, String replacementModelCode) {
        Repositories.InterpRow source = requireInterp(sourceCode);
        if (repo.interp(newCode) != null) {
            throw new ConflictException("interpretation already exists: " + newCode);
        }
        Repositories.ModelRow model = replacementModelCode == null
                ? requireModelRow(source.velocityModelId())
                : modelOrThrow(replacementModelCode, null);
        long now = clock.nowMs();
        long id = repo.insertInterp(newCode, source.eventCode(), model.id(), source.id(), now);
        for (Repositories.PickEventRow event : repo.events(source.id())) {
            repo.appendPickEvent(id, event.eventType(), event.payload(),
                    event.undoOfSeq(), event.createdMs());
        }
        for (long[] entry : repo.undoEntries(source.id())) {
            repo.insertUndoRaw(id, entry[0], entry[1], entry[2]);
        }
        return repo.interpById(id);
    }

    // ---------- event stream actions ----------

    @Transactional
    public long act(String interpCode, ActionRequest request) {
        Repositories.InterpRow interp = requireWritable(interpCode);
        PickLogic.State state = replay(interp.id());
        long now = clock.nowMs();
        String type = request.type == null ? "" : request.type.toUpperCase();
        ObjectNode payload = switch (type) {
            case PickLogic.ADD -> addPayload(request);
            case PickLogic.REMOVE -> removePayload(state, request);
            case PickLogic.UPDATE -> updatePayload(state, request);
            case PickLogic.MERGE -> mergePayload(state, request);
            case PickLogic.CLOCK -> clockPayload(state, request);
            default -> throw new IllegalArgumentException("unsupported action: " + request.type);
        };
        long seq = repo.appendPickEvent(interp.id(), type, payload.toString(), null, now);
        repo.recordUndo(interp.id(), seq, seq, now);
        return seq;
    }

    @Transactional
    public long markNoise(String interpCode, long candidateId, boolean noise) {
        Repositories.InterpRow interp = requireWritable(interpCode);
        PickLogic.State state = replay(interp.id());
        PickLogic.Candidate before = copyCandidate(state.require(candidateId));
        PickLogic.Candidate after = copyCandidate(before);
        after.status = noise ? PickLogic.STATUS_NOISE : PickLogic.STATUS_ACTIVE;
        ObjectNode payload = mapper.createObjectNode();
        payload.set("before", PickLogic.candidateNode(mapper, before));
        payload.set("after", PickLogic.candidateNode(mapper, after));
        long now = clock.nowMs();
        long seq = repo.appendPickEvent(interp.id(), PickLogic.UPDATE, payload.toString(), null, now);
        repo.recordUndo(interp.id(), seq, seq, now);
        return seq;
    }

    @Transactional
    public long undo(String interpCode) {
        Repositories.InterpRow interp = requireWritable(interpCode);
        long[] last = repo.lastUndo(interp.id());
        if (last == null) {
            throw new ConflictException("nothing to undo");
        }
        long forwardSeq = last[0];
        Repositories.PickEventRow forward = repo.events(interp.id()).stream()
                .filter(e -> e.seq() == forwardSeq).findFirst()
                .orElseThrow(() -> new IllegalStateException("forward event missing"));
        ObjectNode inverse = inversePayload(forward);
        long now = clock.nowMs();
        long inverseSeq = repo.appendPickEvent(interp.id(),
                inverseType(forward.eventType()), inverse.toString(), forwardSeq, now);
        repo.deleteUndo(interp.id(), forwardSeq);
        return inverseSeq;
    }

    public PickLogic.State replay(long interpId) {
        PickLogic.State state = new PickLogic.State();
        for (Repositories.PickEventRow row : repo.events(interpId)) {
            try {
                JsonNode payload = mapper.readTree(row.payload());
                PickLogic.apply(state, row.eventType(), payload);
                state.lastSeq = row.seq();
            } catch (Exception e) {
                throw new IllegalStateException("cannot replay seq " + row.seq(), e);
            }
        }
        return state;
    }

    private ObjectNode addPayload(ActionRequest request) {
        validateCandidate(request.candidate);
        PickLogic.Candidate candidate = request.candidate;
        candidate.id = repo.seq().next("candidate");
        if (candidate.polarity == null) {
            candidate.polarity = "NONE";
        }
        if (candidate.source == null) {
            candidate.source = "HUMAN";
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set("candidate", PickLogic.candidateNode(mapper, candidate));
        return payload;
    }

    private ObjectNode removePayload(PickLogic.State state, ActionRequest request) {
        if (request.targetId == null) {
            throw new IllegalArgumentException("targetId required");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set("candidate", PickLogic.candidateNode(mapper, state.require(request.targetId)));
        return payload;
    }

    private ObjectNode updatePayload(PickLogic.State state, ActionRequest request) {
        if (request.targetId == null) {
            throw new IllegalArgumentException("targetId required");
        }
        PickLogic.Candidate after = copyCandidate(state.require(request.targetId));
        if (request.arrivalMs != null) {
            after.arrivalMs = request.arrivalMs;
        }
        if (request.polarity != null) {
            after.polarity = request.polarity;
        }
        if (request.confidenceLowMs != null) {
            after.confidenceLowMs = request.confidenceLowMs;
        }
        if (request.confidenceHighMs != null) {
            after.confidenceHighMs = request.confidenceHighMs;
        }
        if (request.weight != null) {
            after.weight = request.weight;
        }
        if (request.source != null) {
            after.source = request.source;
        }
        if (request.note != null) {
            after.note = request.note;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set("after", PickLogic.candidateNode(mapper, after));
        return payload;
    }

    private ObjectNode mergePayload(PickLogic.State state, ActionRequest request) {
        if (request.survivorId == null || request.mergedIds == null || request.mergedIds.isEmpty()) {
            throw new IllegalArgumentException("survivorId and mergedIds required");
        }
        if (request.mergedIds.contains(request.survivorId)) {
            throw new IllegalArgumentException("survivor cannot be merged into itself");
        }
        PickLogic.Candidate survivor = copyCandidate(state.require(request.survivorId));
        List<PickLogic.Candidate> merged = request.mergedIds.stream()
                .map(state::require).map(this::copyCandidate).toList();
        for (PickLogic.Candidate c : merged) {
            if (!c.phase.equals(survivor.phase) || !c.stationCode.equals(survivor.stationCode)) {
                throw new IllegalArgumentException("can only merge same station/phase candidates");
            }
            if (!PickLogic.STATUS_ACTIVE.equals(c.status)) {
                throw new IllegalArgumentException("only active candidates can be merged");
            }
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.set("survivorBefore", PickLogic.candidateNode(mapper, copyCandidate(survivor)));
        ArrayNode beforeArray = payload.putArray("mergedBefore");
        merged.forEach(c -> beforeArray.add(PickLogic.candidateNode(mapper, copyCandidate(c))));
        if (request.arrivalMs != null) {
            survivor.arrivalMs = request.arrivalMs;
        }
        payload.set("survivor", PickLogic.candidateNode(mapper, survivor));
        ArrayNode array = payload.putArray("merged");
        merged.forEach(c -> array.add(PickLogic.candidateNode(mapper, c)));
        return payload;
    }

    private ObjectNode clockPayload(PickLogic.State state, ActionRequest request) {
        if (request.stationCode == null || request.afterMs == null) {
            throw new IllegalArgumentException("stationCode and afterMs required");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("stationCode", request.stationCode);
        payload.put("afterMs", request.afterMs);
        Long before = state.clockCorrectionMs.get(request.stationCode);
        payload.put("beforeMs", before == null ? 0L : before);
        return payload;
    }

    private String inverseType(String forwardType) {
        return switch (forwardType) {
            case PickLogic.ADD -> PickLogic.REMOVE;
            case PickLogic.REMOVE -> PickLogic.ADD;
            case PickLogic.UPDATE -> PickLogic.UPDATE;
            case PickLogic.MERGE -> PickLogic.UNMERGE;
            case PickLogic.UNMERGE -> PickLogic.MERGE;
            case PickLogic.CLOCK -> PickLogic.CLOCK;
            default -> throw new IllegalStateException("cannot invert " + forwardType);
        };
    }

    private ObjectNode inversePayload(Repositories.PickEventRow forward) {
        try {
            JsonNode p = mapper.readTree(forward.payload());
            return switch (forward.eventType()) {
                case PickLogic.ADD, PickLogic.REMOVE -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.set("candidate", p.get("candidate"));
                    yield n;
                }
                case PickLogic.UPDATE -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.set("after", p.get("before") == null ? p.get("after") : p.get("before"));
                    yield n;
                }
                case PickLogic.MERGE -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.set("survivor", p.get("survivorBefore"));
                    n.set("restored", p.get("mergedBefore"));
                    yield n;
                }
                case PickLogic.UNMERGE -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.set("survivor", p.get("survivor"));
                    n.set("restored", p.get("restored"));
                    yield n;
                }
                case PickLogic.CLOCK -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.put("stationCode", p.get("stationCode").asText());
                    n.put("afterMs", p.has("beforeMs") ? p.get("beforeMs").asLong() : 0L);
                    n.put("beforeMs", p.get("afterMs").asLong());
                    yield n;
                }
                default -> throw new IllegalStateException();
            };
        } catch (Exception e) {
            throw new IllegalStateException("cannot build inverse", e);
        }
    }

    // ---------- predictions, residuals, comparison ----------

    public record ModelView(long id, String code, int version, List<double[]> layers) {
    }

    public record ResidualRow(String stationCode, String phase, long candidateId,
                              long observedCorrectedMs, Double predictedMs, double residualMs,
                              double weight, String weightSource, boolean inWindow,
                              Long confidenceLowMs, Long confidenceHighMs,
                              Double observedDiffFromBaselineMs, Double predictedDiffFromBaselineMs) {
    }

    public record ModelScore(long modelId, String modelCode, int version, int comparedStations,
                             int inWindowCount, int outOfWindowCount, double weightedRmsMs,
                             double meanResidualMs, double totalWeight, List<ResidualRow> rows,
                             List<StationPrediction> predictions) {
        public String key() {
            return modelCode + "#v" + version;
        }
    }

    public record StationComparison(String stationCode, String phase, Long candidateId,
                                    Map<String, Double> observedMs, Map<String, Double> predictedMs,
                                    Map<String, Double> residualMs, Map<String, Boolean> inWindow,
                                    Map<String, Double> predictedDiffMs,
                                    Map<String, Double> observedDiffMs,
                                    List<String> weightSources) {
    }

    public record ComparisonResult(long windowMs, List<ModelScore> scores,
                                   List<ModelScore> winners,
                                   List<StationComparison> stationComparison) {
    }

    public ModelView modelView(Long modelId) {
        Repositories.ModelRow model = requireModelRow(modelId);
        List<double[]> layers = repo.jdbc().query(
                "SELECT top_depth_km, bottom_depth_km, vp_kms, vs_kms FROM model_layer "
                        + "WHERE model_id = ? ORDER BY layer_order",
                (rs, i) -> new double[] {rs.getDouble(1), rs.getDouble(2),
                        rs.getDouble(3), rs.getDouble(4)}, model.id());
        return new ModelView(model.id(), model.modelCode(), model.version(), layers);
    }

    private List<TravelTime.Layer> travelLayers(ModelView view) {
        List<TravelTime.Layer> list = new ArrayList<>();
        for (double[] l : view.layers()) {
            list.add(new TravelTime.Layer(l[0], l[1], l[2], l[3]));
        }
        return list;
    }

    public Map<String, StationPrediction> predictions(Repositories.InterpRow interp, ModelView view) {
        Repositories.EventRow event = requireEvent(interp.eventCode());
        List<Repositories.StationRow> stations = pinnedOrLatest(interp);
        Map<String, StationPrediction> result = new LinkedHashMap<>();
        List<TravelTime.Layer> layers = travelLayers(view);
        for (Repositories.StationRow station : stations) {
            double distance = TravelTime.epicentralKm(event.latitude(), event.longitude(),
                    station.latitude(), station.longitude());
            double elevationKm = station.elevationM() / 1000.0d;
            TravelTime.Ray pRay = TravelTime.trace(layers, event.depthKm(), elevationKm, "P", distance);
            TravelTime.Ray sRay = TravelTime.trace(layers, event.depthKm(), elevationKm, "S", distance);
            Double pMs = pRay.grazing() ? null : event.originMs() + pRay.timeSec() * 1000.0d;
            Double sMs = sRay.grazing() ? null : event.originMs() + sRay.timeSec() * 1000.0d;
            result.put(station.stationCode(), new StationPrediction(station.stationCode(),
                    station.version(), station.globalVersion(), distance, pMs, sMs));
        }
        return result;
    }

    public List<Repositories.StationRow> pinnedOrLatest(Repositories.InterpRow interp) {
        if (interp.stationPinVersion() != null) {
            List<Repositories.StationRow> pinned = repo.stationsAtGlobalPin(interp.stationPinVersion());
            if (!pinned.isEmpty()) {
                return pinned;
            }
        }
        return repo.stationsLatest();
    }

    public static PickLogic.Candidate representativeCandidate(List<PickLogic.Candidate> candidates) {
        return candidates.stream()
                .filter(c -> PickLogic.STATUS_ACTIVE.equals(c.status))
                .min(Comparator.comparingDouble((PickLogic.Candidate c) -> -c.weight)
                        .thenComparingLong(c -> c.arrivalMs)
                        .thenComparingLong(c -> c.id))
                .orElse(null);
    }

    public ComparisonResult compare(String interpCode, long windowMs, List<String> modelCodes) {
        Repositories.InterpRow interp = requireInterp(interpCode);
        PickLogic.State state = replay(interp.id());

        Map<String, List<PickLogic.Candidate>> grouped = new TreeMap<>();
        for (PickLogic.Candidate c : state.ordered()) {
            grouped.computeIfAbsent(c.stationCode + "|" + c.phase, k -> new ArrayList<>()).add(c);
        }
        List<Repositories.ModelRow> modelRows = resolveModels(modelCodes, interp);
        List<ModelView> views = modelRows.stream().map(m -> modelView(m.id())).toList();

        // key station|phase -> per model data
        record Slot(long candidateId, long observed, Double predicted, Double residual,
                    boolean inWindow, double weight, String weightSource,
                    Long ciLow, Long ciHigh) {
        }
        Map<String, Map<String, Slot>> slots = new TreeMap<>();
        Map<String, Map<String, StationPrediction>> predictionsByModel = new LinkedHashMap<>();
        for (int mi = 0; mi < modelRows.size(); mi++) {
            Repositories.ModelRow model = modelRows.get(mi);
            ModelView view = views.get(mi);
            Map<String, StationPrediction> predictions = predictions(interp, view);
            predictionsByModel.put(modelKey(model), predictions);
            for (Map.Entry<String, List<PickLogic.Candidate>> entry : grouped.entrySet()) {
                PickLogic.Candidate candidate = representativeCandidate(entry.getValue());
                if (candidate == null) {
                    continue;
                }
                StationPrediction prediction = predictions.get(candidate.stationCode);
                if (prediction == null) {
                    continue;
                }
                Double predicted = "S".equalsIgnoreCase(candidate.phase)
                        ? prediction.sMs() : prediction.pMs();
                long correction = state.clockCorrectionMs.getOrDefault(candidate.stationCode, 0L);
                long observed = candidate.correctedArrivalMs(correction);
                Double residual = predicted == null ? null : observed - predicted;
                boolean inWindow = residual != null && Math.abs(residual) <= windowMs;
                slots.computeIfAbsent(entry.getKey(), k -> new TreeMap<>())
                        .put(modelKey(model), new Slot(candidate.id, observed, predicted, residual,
                                inWindow, candidate.weight,
                                weightSource(candidate, entry.getValue()),
                                candidate.confidenceLowMs, candidate.confidenceHighMs));
            }
        }

        String baseline = modelRows.stream().map(InterpService::modelKey)
                .min(Comparator.naturalOrder()).orElseThrow();
        List<ModelScore> scores = new ArrayList<>();
        for (int mi = 0; mi < modelRows.size(); mi++) {
            Repositories.ModelRow model = modelRows.get(mi);
            Map<String, StationPrediction> predictions = predictionsByModel.get(modelKey(model));
            List<ResidualRow> rows = new ArrayList<>();
            for (Map.Entry<String, Map<String, Slot>> entry : slots.entrySet()) {
                Slot slot = entry.getValue().get(modelKey(model));
                if (slot == null || slot.residual() == null) {
                    continue;
                }
                Slot base = entry.getValue().get(baseline);
                Double predictedDiff = base == null || base.predicted() == null ? null
                        : slot.predicted() - base.predicted();
                Double observedDiff = base == null ? null
                        : Double.valueOf(slot.observed() - base.observed());
                rows.add(new ResidualRow(entry.getKey().substring(0, entry.getKey().indexOf('|')),
                        entry.getKey().substring(entry.getKey().indexOf('|') + 1),
                        slot.candidateId(), slot.observed(), slot.predicted(), slot.residual(),
                        slot.weight(), slot.weightSource(), slot.inWindow(),
                        slot.ciLow(), slot.ciHigh(), observedDiff, predictedDiff));
            }
            rows.sort(Comparator.comparing(ResidualRow::stationCode)
                    .thenComparing(ResidualRow::phase)
                    .thenComparingLong(ResidualRow::candidateId));
            scores.add(scoreModel(model, rows, predictions, windowMs));
        }
        scores.sort(scoreComparator());
        List<ModelScore> winners = winners(scores);

        List<StationComparison> stationComparisons = new ArrayList<>();
        for (Map.Entry<String, Map<String, Slot>> entry : slots.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            Map<String, Double> observedMap = new TreeMap<>();
            Map<String, Double> predictedMap = new TreeMap<>();
            Map<String, Double> residualMap = new TreeMap<>();
            Map<String, Boolean> inWindowMap = new TreeMap<>();
            Map<String, Double> predictedDiffMap = new TreeMap<>();
            Map<String, Double> observedDiffMap = new TreeMap<>();
            List<String> weightSources = new ArrayList<>();
            Long candidateId = null;
            Slot base = entry.getValue().get(baseline);
            for (String key : entry.getValue().keySet().stream().sorted().toList()) {
                Slot slot = entry.getValue().get(key);
                candidateId = slot.candidateId();
                observedMap.put(key, (double) slot.observed());
                if (slot.predicted() != null) {
                    predictedMap.put(key, slot.predicted());
                    residualMap.put(key, slot.residual());
                }
                inWindowMap.put(key, slot.inWindow());
                if (base != null) {
                    observedDiffMap.put(key, (double) (slot.observed() - base.observed()));
                    if (base.predicted() != null && slot.predicted() != null) {
                        predictedDiffMap.put(key, slot.predicted() - base.predicted());
                    }
                }
                weightSources.add(key + " => " + slot.weightSource());
            }
            stationComparisons.add(new StationComparison(parts[0], parts[1], candidateId,
                    observedMap, predictedMap, residualMap, inWindowMap,
                    predictedDiffMap, observedDiffMap, weightSources));
        }
        return new ComparisonResult(windowMs, scores, winners, stationComparisons);
    }

    private ModelScore scoreModel(Repositories.ModelRow model, List<ResidualRow> rows,
                                  Map<String, StationPrediction> predictions, long windowMs) {
        int in = 0;
        double sumSquared = 0.0d;
        double sumResidual = 0.0d;
        double totalWeight = 0.0d;
        for (ResidualRow row : rows) {
            if (row.inWindow()) {
                in++;
                sumSquared += row.weight() * row.residualMs() * row.residualMs();
                sumResidual += row.weight() * row.residualMs();
                totalWeight += row.weight();
            }
        }
        double rms = totalWeight > 0 ? Math.sqrt(sumSquared / totalWeight) : Double.POSITIVE_INFINITY;
        double mean = totalWeight > 0 ? sumResidual / totalWeight : Double.POSITIVE_INFINITY;
        long stationCount = predictions.size();
        return new ModelScore(model.id(), model.modelCode(), model.version(), (int) stationCount,
                in, rows.size() - in, rms, mean, totalWeight, rows,
                predictions.values().stream()
                        .sorted(Comparator.comparing(StationPrediction::stationCode)).toList());
    }

    private static String modelKey(Repositories.ModelRow model) {
        return model.modelCode() + "#v" + model.version();
    }

    static Comparator<ModelScore> scoreComparator() {
        return Comparator.comparingInt(ModelScore::outOfWindowCount)
                .thenComparingDouble(ModelScore::weightedRmsMs)
                .thenComparingDouble(ModelScore::meanResidualMs)
                .thenComparing(ModelScore::modelCode)
                .thenComparingInt(ModelScore::version);
    }

    private List<ModelScore> winners(List<ModelScore> scores) {
        if (scores.isEmpty()) {
            return List.of();
        }
        ModelScore best = scores.get(0);
        List<ModelScore> tied = new ArrayList<>();
        for (ModelScore score : scores) {
            if (scoreComparator().compare(score, best) == 0) {
                tied.add(score);
            }
        }
        return tied;
    }

    private List<Repositories.ModelRow> resolveModels(List<String> modelCodes,
                                                      Repositories.InterpRow interp) {
        List<Repositories.ModelRow> models = new ArrayList<>();
        if (modelCodes == null || modelCodes.isEmpty()) {
            for (Repositories.ModelRow row : repo.modelsLatest()) {
                models.add(row);
            }
        } else {
            for (String code : modelCodes.stream().distinct().sorted().toList()) {
                models.add(modelOrThrow(code, null));
            }
        }
        if (models.isEmpty()) {
            throw new ConflictException("no velocity models available");
        }
        return models;
    }

    private static String weightSource(PickLogic.Candidate chosen,
                                       List<PickLogic.Candidate> all) {
        StringBuilder source = new StringBuilder("candidate#").append(chosen.id)
                .append(":").append(chosen.source)
                .append(":w=").append(String.format(java.util.Locale.ROOT, "%.3f", chosen.weight));
        long active = all.stream().filter(c -> PickLogic.STATUS_ACTIVE.equals(c.status)).count();
        if (active > 1) {
            source.append(";representative-of-").append(active);
        }
        return source.toString();
    }

    // ---------- freeze ----------

    public record FreezeResult(long interpId, long headSeq, long stationPinVersion,
                               long frozenMs, boolean frozen) {
    }

    public ClockConflict.Report clockReport(String interpCode) {
        Repositories.InterpRow interp = requireInterp(interpCode);
        ModelView view = modelView(interp.velocityModelId());
        return clockReport(interp, view);
    }

    private ClockConflict.Report clockReport(Repositories.InterpRow interp, ModelView view) {
        PickLogic.State state = replay(interp.id());
        Map<String, StationPrediction> predictions = predictions(interp, view);
        Map<String, PickLogic.Candidate> chosenP = new TreeMap<>();
        Map<String, List<PickLogic.Candidate>> grouped = new TreeMap<>();
        for (PickLogic.Candidate c : state.ordered()) {
            if ("P".equalsIgnoreCase(c.phase)) {
                grouped.computeIfAbsent(c.stationCode, k -> new ArrayList<>()).add(c);
            }
        }
        List<ClockConflict.Observation> observations = new ArrayList<>();
        for (Map.Entry<String, List<PickLogic.Candidate>> entry : grouped.entrySet()) {
            PickLogic.Candidate c = representativeCandidate(entry.getValue());
            if (c == null) {
                continue;
            }
            StationPrediction prediction = predictions.get(entry.getKey());
            if (prediction == null || prediction.pMs() == null) {
                continue;
            }
            long correction = state.clockCorrectionMs.getOrDefault(entry.getKey(), 0L);
            observations.add(new ClockConflict.Observation(entry.getKey(),
                    c.correctedArrivalMs(correction), prediction.pMs()));
        }
        return ClockConflict.analyze(observations);
    }

    @Transactional
    public FreezeResult freeze(String interpCode) {
        Repositories.InterpRow interp = requireInterp(interpCode);
        if (interp.frozenMs() != null) {
            throw new FrozenException("interpretation already frozen: " + interpCode);
        }
        ModelView view = modelView(interp.velocityModelId());
        ClockConflict.Report report = clockReport(interp, view);
        if (!report.consistent()) {
            throw new FreezeConflictException(report);
        }
        PickLogic.State state = replay(interp.id());
        long pin = repo.latestStationGlobalVersion();
        long now = clock.nowMs();
        repo.freezeInterp(interp.id(), state.lastSeq, pin, now);
        for (Repositories.StationRow station : repo.stationsAtGlobalPin(pin)) {
            repo.insertPin(interp.id(), station.stationCode(), station.version());
        }
        return new FreezeResult(interp.id(), state.lastSeq, pin, now, true);
    }

    // ---------- helpers ----------

    private Repositories.InterpRow requireInterp(String code) {
        Repositories.InterpRow row = repo.interp(code);
        if (row == null) {
            throw new NotFoundException("interpretation not found: " + code);
        }
        return row;
    }

    private Repositories.InterpRow requireWritable(String code) {
        Repositories.InterpRow row = requireInterp(code);
        if (row.frozenMs() != null) {
            throw new FrozenException("interpretation is frozen: " + code);
        }
        return row;
    }

    private Repositories.EventRow requireEvent(String code) {
        Repositories.EventRow row = repo.findEvent(code);
        if (row == null) {
            throw new NotFoundException("event not found: " + code);
        }
        return row;
    }

    private Repositories.ModelRow modelOrThrow(String code, Integer version) {
        Repositories.ModelRow row = repo.model(code, version);
        if (row == null) {
            throw new NotFoundException("velocity model not found: " + code);
        }
        return row;
    }

    private Repositories.ModelRow requireModelRow(Long id) {
        if (id == null) {
            throw new ConflictException("interpretation has no velocity model");
        }
        return repo.jdbc().query("SELECT * FROM velocity_model WHERE id = ?", (rs, i) ->
                new Repositories.ModelRow(rs.getLong("id"), rs.getString("model_code"),
                        rs.getInt("version"), rs.getLong("created_ms")), id).stream()
                .findFirst().orElseThrow(() -> new NotFoundException("model missing: " + id));
    }

    private static void validateCandidate(PickLogic.Candidate c) {
        if (c == null) {
            throw new IllegalArgumentException("candidate required");
        }
        if (c.stationCode == null || c.stationCode.isBlank()) {
            throw new IllegalArgumentException("stationCode required");
        }
        if (c.phase == null || (!c.phase.equals("P") && !c.phase.equals("S"))) {
            throw new IllegalArgumentException("phase must be P or S");
        }
        if (c.confidenceLowMs != null && c.confidenceHighMs != null
                && c.confidenceLowMs > c.confidenceHighMs) {
            throw new IllegalArgumentException("confidence interval inverted");
        }
        if (c.weight < 0 || !Double.isFinite(c.weight)) {
            throw new IllegalArgumentException("weight must be finite and non-negative");
        }
    }

    private PickLogic.Candidate copyCandidate(PickLogic.Candidate source) {
        PickLogic.Candidate copy = new PickLogic.Candidate();
        copy.id = source.id;
        copy.stationCode = source.stationCode;
        copy.channel = source.channel;
        copy.phase = source.phase;
        copy.arrivalMs = source.arrivalMs;
        copy.polarity = source.polarity;
        copy.confidenceLowMs = source.confidenceLowMs;
        copy.confidenceHighMs = source.confidenceHighMs;
        copy.weight = source.weight;
        copy.source = source.source;
        copy.status = source.status;
        copy.mergedInto = source.mergedInto;
        copy.note = source.note;
        return copy;
    }
}
