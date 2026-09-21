package com.phase.room.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.phase.room.domain.Candidate;
import com.phase.room.domain.Json;
import com.phase.room.domain.PickEventType;
import com.phase.room.domain.PickState;
import com.phase.room.repo.Rows;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class PickCommandService {
    private final PickService pickService;

    public PickCommandService(PickService pickService) {
        this.pickService = pickService;
    }

    public Candidate add(long eventId, long stationId, String phase, Long segmentId,
                         double arrivalMs, double ciLowMs, double ciHighMs, String polarity,
                         Double weight, String weightSource) {
        validateCi(ciLowMs, arrivalMs, ciHighMs);
        PickState state = pickService.state(eventId, stationId);
        long candidateId = state.nextCandidateId;
        long nextId = candidateId + 1;
        ObjectNode payload = Json.CODEC.createObjectNode();
        payload.put("candidateId", candidateId);
        payload.put("nextCandidateId", nextId);
        payload.put("eventId", eventId);
        payload.put("stationId", stationId);
        payload.put("phase", phase);
        if (segmentId != null) {
            payload.put("segmentId", segmentId);
        } else {
            payload.putNull("segmentId");
        }
        payload.put("arrivalMs", arrivalMs);
        payload.put("ciLowMs", ciLowMs);
        payload.put("ciHighMs", ciHighMs);
        putNullable(payload, "polarity", polarity);
        payload.put("weight", weight == null ? 1.0 : weight);
        payload.put("weightSource", weightSource == null ? "manual" : weightSource);
        payload.put("status", "active");

        ObjectNode inverse = Json.CODEC.createObjectNode();
        inverse.put("candidateId", candidateId);

        pickService.append(eventId, stationId, PickEventType.ADD, payload, inverse);
        return pickService.state(eventId, stationId).candidates.get(candidateId);
    }

    public Candidate move(long eventId, long stationId, long candidateId, double newArrivalMs,
                          double newCiLowMs, double newCiHighMs) {
        validateCi(newCiLowMs, newArrivalMs, newCiHighMs);
        Candidate current = pickService.state(eventId, stationId).active(candidateId);
        ObjectNode payload = Json.CODEC.createObjectNode();
        payload.put("candidateId", candidateId);
        payload.put("oldArrivalMs", current.arrivalMs);
        payload.put("newArrivalMs", newArrivalMs);
        payload.put("oldCiLowMs", current.ciLowMs);
        payload.put("newCiLowMs", newCiLowMs);
        payload.put("oldCiHighMs", current.ciHighMs);
        payload.put("newCiHighMs", newCiHighMs);
        pickService.append(eventId, stationId, PickEventType.MOVE, payload,
                swapMove(payload));
        return pickService.state(eventId, stationId).candidates.get(candidateId);
    }

    public Candidate polarity(long eventId, long stationId, long candidateId, String value) {
        Candidate current = pickService.state(eventId, stationId).active(candidateId);
        ObjectNode payload = Json.CODEC.createObjectNode();
        payload.put("candidateId", candidateId);
        putNullable(payload, "oldPolarity", current.polarity);
        putNullable(payload, "newPolarity", value);
        ObjectNode inverse = Json.CODEC.createObjectNode();
        inverse.put("candidateId", candidateId);
        putNullable(inverse, "oldPolarity", current.polarity);
        pickService.append(eventId, stationId, PickEventType.SET_POLARITY, payload, inverse);
        return pickService.state(eventId, stationId).candidates.get(candidateId);
    }

    public Candidate ci(long eventId, long stationId, long candidateId, double low,
                        double high) {
        Candidate current = pickService.state(eventId, stationId).active(candidateId);
        validateCi(low, current.arrivalMs, high);
        ObjectNode payload = Json.CODEC.createObjectNode();
        payload.put("candidateId", candidateId);
        payload.put("oldLow", current.ciLowMs);
        payload.put("newLow", low);
        payload.put("oldHigh", current.ciHighMs);
        payload.put("newHigh", high);
        ObjectNode inverse = Json.CODEC.createObjectNode();
        inverse.put("candidateId", candidateId);
        inverse.put("oldLow", current.ciLowMs);
        inverse.put("oldHigh", current.ciHighMs);
        pickService.append(eventId, stationId, PickEventType.SET_CI, payload, inverse);
        return pickService.state(eventId, stationId).candidates.get(candidateId);
    }

    public Candidate weight(long eventId, long stationId, long candidateId, double weight,
                            String source) {
        if (weight < 0) {
            throw new IllegalArgumentException("权重不能为负");
        }
        Candidate current = pickService.state(eventId, stationId).active(candidateId);
        ObjectNode payload = Json.CODEC.createObjectNode();
        payload.put("candidateId", candidateId);
        payload.put("oldWeight", current.weight);
        payload.put("oldSource", current.weightSource);
        payload.put("newWeight", weight);
        payload.put("newSource", source);
        ObjectNode inverse = Json.CODEC.createObjectNode();
        inverse.put("candidateId", candidateId);
        inverse.put("oldWeight", current.weight);
        inverse.put("oldSource", current.weightSource);
        pickService.append(eventId, stationId, PickEventType.SET_WEIGHT, payload, inverse);
        return pickService.state(eventId, stationId).candidates.get(candidateId);
    }

    /**
     * Merge active candidates of the same phase. Confidence intervals must
     * overlap; the smallest-id survivor absorbs the others (merged arrival is
     * the weight-weighted mean, weight becomes the sum).
     */
    public Candidate merge(long eventId, long stationId, List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.size() < 2) {
            throw new IllegalArgumentException("合并至少需要两个候选");
        }
        List<Long> ordered = new ArrayList<>(candidateIds);
        ordered.sort(Comparator.naturalOrder());
        PickState state = pickService.state(eventId, stationId);
        List<Candidate> involved = new ArrayList<>();
        for (Long id : ordered) {
            involved.add(state.active(id));
        }
        String phase = involved.get(0).phase;
        for (Candidate candidate : involved) {
            if (!phase.equals(candidate.phase)) {
                throw new IllegalArgumentException("只能合并同一震相的候选");
            }
        }
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : involved) {
            low = Math.min(low, candidate.ciLowMs);
            high = Math.max(high, candidate.ciHighMs);
        }
        for (int i = 0; i < involved.size(); i++) {
            for (int j = i + 1; j < involved.size(); j++) {
                Candidate a = involved.get(i);
                Candidate b = involved.get(j);
                if (a.ciHighMs < b.ciLowMs || b.ciHighMs < a.ciLowMs) {
                    throw new IllegalArgumentException(
                            "置信区间不重叠，无法合并候选 " + a.id + " 与 " + b.id);
                }
            }
        }
        long survivor = involved.get(0).id;
        ObjectNode payload = Json.CODEC.createObjectNode();
        payload.put("survivorId", survivor);
        payload.put("ciLowMs", low);
        payload.put("ciHighMs", high);
        var snapshots = payload.putArray("snapshots");
        for (Candidate candidate : involved) {
            snapshots.add(snapshot(candidate));
        }
        ObjectNode inverse = Json.CODEC.createObjectNode();
        var undoSnapshots = inverse.putArray("snapshots");
        for (Candidate candidate : involved) {
            undoSnapshots.add(snapshot(candidate));
        }
        pickService.append(eventId, stationId, PickEventType.MERGE, payload, inverse);
        return pickService.state(eventId, stationId).candidates.get(survivor);
    }

    public Candidate noise(long eventId, long stationId, long candidateId) {
        Candidate current = pickService.state(eventId, stationId).active(candidateId);
        ObjectNode payload = Json.CODEC.createObjectNode();
        payload.put("candidateId", candidateId);
        payload.put("oldStatus", current.status);
        putNullableLong(payload, "oldMergedInto", current.mergedInto);
        ObjectNode inverse = Json.CODEC.createObjectNode();
        inverse.put("candidateId", candidateId);
        inverse.put("oldStatus", current.status);
        putNullableLong(inverse, "oldMergedInto", current.mergedInto);
        pickService.append(eventId, stationId, PickEventType.NOISE, payload, inverse);
        return pickService.state(eventId, stationId).candidates.get(candidateId);
    }

    public Rows.PickEventRow undo(long eventId, long stationId) {
        return pickService.undo(eventId, stationId);
    }

    private void validateCi(double low, double arrival, double high) {
        if (low > arrival || arrival > high) {
            throw new IllegalArgumentException("到时必须位于置信区间内");
        }
    }

    private ObjectNode swapMove(ObjectNode p) {
        ObjectNode inverse = Json.CODEC.createObjectNode();
        inverse.put("candidateId", p.get("candidateId").asLong());
        inverse.put("oldArrivalMs", p.get("oldArrivalMs").asDouble());
        inverse.put("oldCiLowMs", p.get("oldCiLowMs").asDouble());
        inverse.put("oldCiHighMs", p.get("oldCiHighMs").asDouble());
        return inverse;
    }

    private static ObjectNode snapshot(Candidate c) {
        ObjectNode node = Json.CODEC.createObjectNode();
        node.put("candidateId", c.id);
        node.put("eventId", c.eventId);
        node.put("stationId", c.stationId);
        node.put("phase", c.phase);
        putNullableLong(node, "segmentId", c.segmentId);
        node.put("arrivalMs", c.arrivalMs);
        node.put("ciLowMs", c.ciLowMs);
        node.put("ciHighMs", c.ciHighMs);
        putNullable(node, "polarity", c.polarity);
        node.put("weight", c.weight);
        node.put("weightSource", c.weightSource);
        node.put("status", c.status);
        putNullableLong(node, "mergedInto", c.mergedInto);
        return node;
    }

    static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    static void putNullableLong(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
