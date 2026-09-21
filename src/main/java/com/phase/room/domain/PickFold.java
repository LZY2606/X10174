package com.phase.room.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Applies one persisted pick event (forward or its stored inverse) onto fold state. */
public final class PickFold {

    private PickFold() {
    }

    public static void applyForward(PickState state, JsonNode event) {
        PickEventType type = PickEventType.valueOf(event.get("type").asText());
        JsonNode p = event.get("payload");
        switch (type) {
            case ADD -> putSnapshot(state, p, p.get("candidateId").asLong());
            case MOVE -> {
                Candidate c = state.candidates.get(p.get("candidateId").asLong());
                c.arrivalMs = p.get("newArrivalMs").asDouble();
                c.ciLowMs = p.get("newCiLowMs").asDouble();
                c.ciHighMs = p.get("newCiHighMs").asDouble();
            }
            case SET_POLARITY -> state.candidates.get(p.get("candidateId").asLong()).polarity =
                    textOrNull(p, "newPolarity");
            case SET_CI -> {
                Candidate c = state.candidates.get(p.get("candidateId").asLong());
                c.ciLowMs = p.get("newLow").asDouble();
                c.ciHighMs = p.get("newHigh").asDouble();
            }
            case SET_WEIGHT -> {
                Candidate c = state.candidates.get(p.get("candidateId").asLong());
                c.weight = p.get("newWeight").asDouble();
                c.weightSource = p.get("newSource").asText();
            }
            case MERGE -> applyMerge(state, p);
            case NOISE -> {
                Candidate c = state.candidates.get(p.get("candidateId").asLong());
                c.status = "noise";
                c.mergedInto = null;
            }
            case RESTORE -> {
                Candidate c = state.candidates.get(p.get("candidateId").asLong());
                c.status = textOrNull(p, "oldStatus") == null ? "active"
                        : textOrNull(p, "oldStatus");
                c.mergedInto = longOrNull(p, "oldMergedInto");
            }
            case CLOCK -> {
                // Corrections live on station versions; nothing to fold here.
            }
            default -> throw new IllegalStateException("未知事件类型: " + type);
        }
    }

    public static void applyInverse(PickState state, JsonNode event) {
        PickEventType type = PickEventType.valueOf(event.get("type").asText());
        JsonNode inv = event.get("inverse");
        switch (type) {
            case ADD -> state.candidates.remove(inv.get("candidateId").asLong());
            case MOVE -> {
                Candidate c = state.candidates.get(inv.get("candidateId").asLong());
                c.arrivalMs = inv.get("oldArrivalMs").asDouble();
                c.ciLowMs = inv.get("oldCiLowMs").asDouble();
                c.ciHighMs = inv.get("oldCiHighMs").asDouble();
            }
            case SET_POLARITY -> state.candidates.get(inv.get("candidateId").asLong()).polarity =
                    textOrNull(inv, "oldPolarity");
            case SET_CI -> {
                Candidate c = state.candidates.get(inv.get("candidateId").asLong());
                c.ciLowMs = inv.get("oldLow").asDouble();
                c.ciHighMs = inv.get("oldHigh").asDouble();
            }
            case SET_WEIGHT -> {
                Candidate c = state.candidates.get(inv.get("candidateId").asLong());
                c.weight = inv.get("oldWeight").asDouble();
                c.weightSource = inv.get("oldSource").asText();
            }
            case MERGE -> {
                for (JsonNode snapshot : inv.get("snapshots")) {
                    long id = snapshot.get("candidateId").asLong();
                    putSnapshot(state, snapshot, id);
                }
            }
            case NOISE, RESTORE -> {
                Candidate c = state.candidates.get(inv.get("candidateId").asLong());
                c.status = inv.get("oldStatus").asText();
                c.mergedInto = longOrNull(inv, "oldMergedInto");
            }
            case CLOCK -> {
                // Station version pointer is restored by the caller; fold state is unchanged.
            }
            default -> throw new IllegalStateException("未知事件类型: " + type);
        }
    }

    private static void applyMerge(PickState state, JsonNode p) {
        ArrayNode snapshots = (ArrayNode) p.get("snapshots");
        long survivor = p.get("survivorId").asLong();
        double weightSum = 0;
        double arrivalWeighted = 0;
        for (JsonNode snapshot : snapshots) {
            weightSum += snapshot.get("weight").asDouble();
            arrivalWeighted += snapshot.get("weight").asDouble()
                    * snapshot.get("arrivalMs").asDouble();
        }
        for (JsonNode snapshot : snapshots) {
            long id = snapshot.get("candidateId").asLong();
            if (id != survivor) {
                Candidate c = state.candidates.get(id);
                c.status = "merged";
                c.mergedInto = survivor;
            }
        }
        Candidate target = state.candidates.get(survivor);
        target.ciLowMs = p.get("ciLowMs").asDouble();
        target.ciHighMs = p.get("ciHighMs").asDouble();
        target.arrivalMs = arrivalWeighted / weightSum;
        target.weight = weightSum;
        target.weightSource = "merge-sum";
    }

    private static void putSnapshot(PickState state, JsonNode node, long id) {
        Candidate c = new Candidate();
        c.id = id;
        c.eventId = node.path("eventId").asLong(0);
        c.stationId = node.path("stationId").asLong(0);
        c.phase = textOrNull(node, "phase");
        c.segmentId = longOrNull(node, "segmentId");
        c.arrivalMs = node.get("arrivalMs").asDouble();
        c.ciLowMs = node.get("ciLowMs").asDouble();
        c.ciHighMs = node.get("ciHighMs").asDouble();
        c.polarity = textOrNull(node, "polarity");
        c.weight = node.path("weight").asDouble(1.0);
        c.weightSource = node.path("weightSource").asText("manual");
        c.status = node.path("status").asText("active");
        c.mergedInto = longOrNull(node, "mergedInto");
        state.candidates.put(id, c);
        if (node.has("nextCandidateId")) {
            state.nextCandidateId = Math.max(state.nextCandidateId,
                    node.get("nextCandidateId").asLong());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asLong() : null;
    }
}
