package com.phase.room.service;

import com.phase.room.domain.Candidate;
import com.phase.room.domain.ClockConflictSolver;
import com.phase.room.domain.ConflictReport;
import com.phase.room.domain.PickState;
import com.phase.room.repo.EventRepo;
import com.phase.room.repo.ModelRepo;
import com.phase.room.repo.Rows;
import com.phase.room.repo.StationRepo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares velocity models on the same frozen/working version triple.
 * Models with any out-of-window active candidate are ineligible to win even when
 * their average residual is smaller; all ranking ties are broken deterministically.
 */
@Service
public class CompareService {
    private final EventRepo eventRepo;
    private final StationRepo stationRepo;
    private final ModelRepo modelRepo;
    private final ModelService modelService;
    private final PickService pickService;

    public CompareService(EventRepo eventRepo, StationRepo stationRepo, ModelRepo modelRepo,
                          ModelService modelService, PickService pickService) {
        this.eventRepo = eventRepo;
        this.stationRepo = stationRepo;
        this.modelRepo = modelRepo;
        this.modelService = modelService;
        this.pickService = pickService;
    }

    public record CandidateResult(long candidateId, String phase, double observedRawMs,
                                  double observedCorrectedMs, double predictedMs,
                                  double residualMs, double weight, String weightSource,
                                  boolean inWindow) {
    }

    public record StationResult(long stationId, String stationCode, int stationVersion,
                                double clockCorrectionMs, List<CandidateResult> candidates,
                                int eligibleCount, int outOfWindowCount) {
    }

    public record ModelResult(long modelId, String modelName, int modelVersion,
                              List<StationResult> stations, int eligibleCandidates,
                              int outOfWindowCandidates, double weightedRmsMs,
                              boolean eligible, String reason) {
    }

    public record Comparison(long eventId, long modelAId, long modelBId, double windowMs,
                             List<ModelResult> models, Long winnerModelId, String winnerReason) {
    }

    public Comparison compare(long eventId, List<long[]> modelRefs,
                              Map<Long, Integer> stationVersions, Map<Long, Integer> pickSeqs,
                              double windowMs) {
        Rows.EventRow event = eventRepo.findById(eventId);
        if (event == null) {
            throw new IllegalArgumentException("事件不存在: " + eventId);
        }
        List<ModelResult> results = new ArrayList<>();
        for (long[] ref : modelRefs) {
            results.add(evaluate(event, ref[0], (int) ref[1], stationVersions, pickSeqs,
                    windowMs));
        }
        results.sort(Comparator
                .comparing((ModelResult r) -> !r.eligible())
                .thenComparing(r -> r.eligible() ? r.weightedRmsMs() : Double.MAX_VALUE)
                .thenComparing(r -> r.modelName()));
        Long winner = null;
        String reason = "没有模型满足全窗口条件";
        if (!results.isEmpty() && results.get(0).eligible()) {
            winner = results.get(0).modelId();
            reason = "全在窗口内，加权 RMS 最小";
            if (results.size() > 1 && results.get(0).eligible() && results.get(1).eligible()
                    && results.get(0).weightedRmsMs() == results.get(1).weightedRmsMs()) {
                reason = "加权 RMS 并列，按模型名称字典序决胜";
            }
        }
        return new Comparison(eventId, modelRefs.get(0)[0], modelRefs.get(1)[0], windowMs,
                results, winner, reason);
    }

    private ModelResult evaluate(Rows.EventRow event, long modelId, int modelVersion,
                                 Map<Long, Integer> stationVersions,
                                 Map<Long, Integer> pickSeqs, double windowMs) {
        Rows.ModelRow model = modelRepo.findById(modelId);
        if (model == null) {
            throw new IllegalArgumentException("模型不存在: " + modelId);
        }
        List<StationResult> stationResults = new ArrayList<>();
        double weightedSquares = 0;
        double weightSum = 0;
        int eligible = 0;
        int outside = 0;

        List<Long> stationIds = new ArrayList<>(stationVersions.keySet());
        stationIds.sort(Comparator
                .comparing((Long id) -> stationRepo.findById(id).code()));
        for (long stationId : stationIds) {
            int stationVersion = stationVersions.get(stationId);
            Rows.StationVersionRow station = stationRepo.findVersion(stationId, stationVersion);
            int seqAt = pickSeqs.getOrDefault(stationId, 0);
            PickState state = pickService.stateAt(event.id(), stationId, seqAt);
            List<CandidateResult> candidateResults = new ArrayList<>();
            List<Candidate> active = new ArrayList<>(state.candidates.values()).stream()
                    .filter(c -> "active".equals(c.status))
                    .sorted(Comparator.comparingLong(c -> c.id))
                    .toList();
            int stationOutside = 0;
            for (Candidate candidate : active) {
                double predicted = modelService.predictedArrivalMs(event, station, modelId,
                        modelVersion, candidate.phase);
                double corrected = candidate.arrivalMs + station.clockCorrectionMs();
                double residual = corrected - predicted;
                boolean inWindow = Math.abs(residual) <= windowMs;
                if (!inWindow) {
                    stationOutside++;
                    outside++;
                } else {
                    eligible++;
                    weightedSquares += candidate.weight * residual * residual;
                    weightSum += candidate.weight;
                }
                candidateResults.add(new CandidateResult(candidate.id, candidate.phase,
                        candidate.arrivalMs, corrected, predicted, residual, candidate.weight,
                        candidate.weightSource, inWindow));
            }
            stationResults.add(new StationResult(stationId,
                    stationRepo.findById(stationId).code(), stationVersion,
                    station.clockCorrectionMs(), candidateResults,
                    active.size() - stationOutside, stationOutside));
        }
        boolean isEligible = outside == 0 && eligible > 0;
        double rms = weightSum > 0 ? Math.sqrt(weightedSquares / weightSum) : Double.NaN;
        String why = isEligible ? "ok"
                : eligible == 0 ? "没有窗口内活动候选" : "存在窗口外活动候选，平均残差再小也不能获胜";
        return new ModelResult(modelId, model.name(), modelVersion, stationResults, eligible,
                outside, rms, isEligible, why);
    }

    /** Ordering-conflict analysis before freezing a correction. */
    public ConflictReport freezeConflicts(long eventId, Map<Long, Integer> stationVersions,
                                          Map<Long, Integer> pickSeqs, long modelId,
                                          int modelVersion) {
        Rows.EventRow event = eventRepo.findById(eventId);
        List<ClockConflictSolver.StationOrder> orders = new ArrayList<>();
        List<Long> stationIds = new ArrayList<>(stationVersions.keySet());
        stationIds.sort(Comparator.comparing(id -> stationRepo.findById(id).code()));
        for (long stationId : stationIds) {
            int stationVersion = stationVersions.get(stationId);
            Rows.StationVersionRow station = stationRepo.findVersion(stationId, stationVersion);
            PickState state = pickService.stateAt(eventId, stationId,
                    pickSeqs.getOrDefault(stationId, 0));
            Candidate pCandidate = earliestActive(state, "P");
            if (pCandidate == null) {
                continue;
            }
            double predicted = modelService.predictedArrivalMs(event, station, modelId,
                    modelVersion, "P");
            orders.add(new ClockConflictSolver.StationOrder(stationId, pCandidate.arrivalMs,
                    pCandidate.arrivalMs + station.clockCorrectionMs(), predicted));
        }
        return ClockConflictSolver.analyze(orders);
    }

    private Candidate earliestActive(PickState state, String phase) {
        Candidate best = null;
        for (Candidate candidate : state.candidates.values()) {
            if ("active".equals(candidate.status) && phase.equals(candidate.phase)) {
                if (best == null || candidate.arrivalMs < best.arrivalMs
                        || candidate.arrivalMs == best.arrivalMs && candidate.id < best.id) {
                    best = candidate;
                }
            }
        }
        return best;
    }
}
