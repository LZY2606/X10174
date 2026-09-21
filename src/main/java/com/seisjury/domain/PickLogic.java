package com.seisjury.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Append-only event reducer for phase picks. Deterministic for a fixed event stream. */
public final class PickLogic {

    public static final String ADD = "ADD";
    public static final String REMOVE = "REMOVE";
    public static final String UPDATE = "UPDATE";
    public static final String MERGE = "MERGE";
    public static final String UNMERGE = "UNMERGE";
    public static final String CLOCK = "CLOCK";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_NOISE = "NOISE";
    public static final String STATUS_MERGED = "MERGED";

    private PickLogic() {
    }

    public static final class Candidate {
        public long id;
        public String stationCode;
        public String channel;
        public String phase;
        public long arrivalMs;
        public String polarity = "NONE";
        public Long confidenceLowMs;
        public Long confidenceHighMs;
        public double weight = 1.0d;
        public String source = "HUMAN";
        public String status = STATUS_ACTIVE;
        public Long mergedInto;
        public String note;

        public long correctedArrivalMs(long correctionMs) {
            return arrivalMs + correctionMs;
        }
    }

    public static final class State {
        public final LinkedHashMap<Long, Candidate> candidates = new LinkedHashMap<>();
        public final TreeMap<String, Long> clockCorrectionMs = new TreeMap<>();
        public long lastSeq;

        public List<Candidate> ordered() {
            return new ArrayList<>(candidates.values());
        }

        public Candidate require(long id) {
            Candidate c = candidates.get(id);
            if (c == null) {
                throw new IllegalArgumentException("candidate not found: " + id);
            }
            return c;
        }
    }

    public static Candidate candidate(JsonNode node) {
        Candidate c = new Candidate();
        c.id = node.get("id").asLong();
        c.stationCode = node.get("stationCode").asText();
        c.channel = text(node, "channel", "");
        c.phase = node.get("phase").asText();
        c.arrivalMs = node.get("arrivalMs").asLong();
        c.polarity = text(node, "polarity", "NONE");
        c.confidenceLowMs = longOrNull(node, "confidenceLowMs");
        c.confidenceHighMs = longOrNull(node, "confidenceHighMs");
        c.weight = node.hasNonNull("weight") ? node.get("weight").asDouble() : 1.0d;
        c.source = text(node, "source", "HUMAN");
        c.status = text(node, "status", STATUS_ACTIVE);
        c.mergedInto = longOrNull(node, "mergedInto");
        c.note = text(node, "note", null);
        return c;
    }

    public static ObjectNode candidateNode(ObjectMapper mapper, Candidate c) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", c.id);
        n.put("stationCode", c.stationCode);
        n.put("channel", c.channel);
        n.put("phase", c.phase);
        n.put("arrivalMs", c.arrivalMs);
        n.put("polarity", c.polarity);
        if (c.confidenceLowMs != null) {
            n.put("confidenceLowMs", c.confidenceLowMs);
        }
        if (c.confidenceHighMs != null) {
            n.put("confidenceHighMs", c.confidenceHighMs);
        }
        n.put("weight", c.weight);
        n.put("source", c.source);
        n.put("status", c.status);
        if (c.mergedInto != null) {
            n.put("mergedInto", c.mergedInto);
        }
        if (c.note != null) {
            n.put("note", c.note);
        }
        return n;
    }

    public static void apply(State state, String type, JsonNode payload) {
        switch (type) {
            case ADD -> {
                Candidate c = candidate(payload.get("candidate"));
                state.candidates.put(c.id, c);
            }
            case REMOVE -> state.candidates.remove(payload.get("candidate").get("id").asLong());
            case UPDATE -> {
                Candidate updated = candidate(payload.get("after"));
                state.candidates.put(updated.id, updated);
            }
            case MERGE -> {
                Candidate survivor = candidate(payload.get("survivor"));
                state.candidates.put(survivor.id, survivor);
                for (JsonNode m : payload.get("merged")) {
                    Candidate old = candidate(m);
                    old.status = STATUS_MERGED;
                    old.mergedInto = survivor.id;
                    state.candidates.put(old.id, old);
                }
            }
            case UNMERGE -> {
                Candidate survivor = candidate(payload.get("survivor"));
                state.candidates.put(survivor.id, survivor);
                for (JsonNode r : payload.get("restored")) {
                    Candidate old = candidate(r);
                    old.status = STATUS_ACTIVE;
                    old.mergedInto = null;
                    state.candidates.put(old.id, old);
                }
            }
            case CLOCK -> state.clockCorrectionMs.put(
                    payload.get("stationCode").asText(), payload.get("afterMs").asLong());
            default -> throw new IllegalArgumentException("unknown pick event: " + type);
        }
    }

    public static final Comparator<Candidate> BY_STATION_PHASE_ARRIVAL =
            Comparator.comparing((Candidate c) -> c.stationCode)
                    .thenComparing(c -> c.phase)
                    .thenComparingLong(c -> c.arrivalMs)
                    .thenComparingLong(c -> c.id);

    private static String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.get(field).asText() : fallback;
    }

    private static Long longOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }
}
