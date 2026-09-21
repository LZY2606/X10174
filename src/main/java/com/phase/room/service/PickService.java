package com.phase.room.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phase.room.domain.Candidate;
import com.phase.room.domain.Json;
import com.phase.room.domain.PickEventType;
import com.phase.room.domain.PickFold;
import com.phase.room.domain.PickState;
import com.phase.room.repo.PickEventRepo;
import com.phase.room.repo.Rows;
import com.phase.room.repo.StationRepo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-sourced pick log per (event, station). State is folded lazily and cached;
 * undo appends an inverse event so the whole history stays append-only and
 * survives restarts.
 */
@Service
public class PickService {
    private final PickEventRepo eventRepo;
    private final StationRepo stationRepo;
    private final Map<Key, PickState> cache = new LinkedHashMap<>();

    public PickService(PickEventRepo eventRepo, StationRepo stationRepo) {
        this.eventRepo = eventRepo;
        this.stationRepo = stationRepo;
    }

    public record Key(long eventId, long stationId) {
    }

    public synchronized PickState state(long eventId, long stationId) {
        Key key = new Key(eventId, stationId);
        PickState state = cache.get(key);
        if (state != null) {
            return state;
        }
        state = foldAll(eventRepo.findStream(eventId, stationId));
        cache.put(key, state);
        return state;
    }

    public synchronized List<Candidate> candidates(long eventId, long stationId) {
        return new ArrayList<>(state(eventId, stationId).candidates.values());
    }

    /** Current pick version = highest event seq for this station (0 if empty). */
    public int currentSeq(long eventId, long stationId) {
        return eventRepo.nextSeq(eventId, stationId) - 1;
    }

    /** Reconstruct state by replaying events up to (and including) seqAt. */
    public synchronized PickState stateAt(long eventId, long stationId, int seqAt) {
        PickState state = new PickState();
        for (Rows.PickEventRow row : eventRepo.findStream(eventId, stationId)) {
            if (row.seq() > seqAt) {
                break;
            }
            applyRow(state, row);
        }
        return state;
    }

    /** Full append-only replay, as used after a restart and by replay tests. */
    public static PickState foldAll(List<Rows.PickEventRow> rows) {
        PickState state = new PickState();
        for (Rows.PickEventRow row : rows) {
            applyRow(state, row);
        }
        return state;
    }

    private static void applyRow(PickState state, Rows.PickEventRow row) {
        var node = Json.CODEC.createObjectNode();
        node.put("type", row.type());
        node.set("payload", Json.read(row.payloadJson(), JsonNode.class));
        if (row.inverseJson() != null) {
            node.set("inverse", Json.read(row.inverseJson(), JsonNode.class));
        }
        PickEventType type = PickEventType.valueOf(row.type());
        if (row.undoOf() != null) {
            // The compensating row's payload IS the original action's inverse.
            var undoNode = Json.CODEC.createObjectNode();
            undoNode.put("type", row.type());
            undoNode.set("inverse", Json.read(row.payloadJson(), JsonNode.class));
            PickFold.applyInverse(state, undoNode);
        } else {
            PickFold.applyForward(state, node);
        }
    }

    /** Append an action and apply it to the cached state. */
    public synchronized Rows.PickEventRow append(long eventId, long stationId, PickEventType type,
                                                 Object payload, Object inverse) {
        return appendInternal(eventId, stationId, type, payload, inverse, null);
    }

    public synchronized Rows.PickEventRow appendClock(long eventId, long stationId,
                                                      double oldCorrection, double newCorrection,
                                                      Long undoOf) {
        var payload = Json.CODEC.createObjectNode();
        payload.put("oldCorrectionMs", oldCorrection);
        payload.put("newCorrectionMs", newCorrection);
        var inverse = Json.CODEC.createObjectNode();
        inverse.put("oldCorrectionMs", oldCorrection);
        return appendInternal(eventId, stationId, PickEventType.CLOCK, payload, inverse, undoOf);
    }

    private Rows.PickEventRow appendInternal(long eventId, long stationId, PickEventType type,
                                             Object payload, Object inverse, Long undoOf) {
        int seq = eventRepo.nextSeq(eventId, stationId);
        double now = System.currentTimeMillis();
        eventRepo.append(eventId, stationId, seq, type.name(), Json.write(payload),
                inverse == null ? null : Json.write(inverse), undoOf, now);
        List<Rows.PickEventRow> rows = eventRepo.findStream(eventId, stationId);
        PickState rebuilt = foldAll(rows);
        cache.put(new Key(eventId, stationId), rebuilt);
        return rows.get(rows.size() - 1);
    }

    /**
     * Undo the latest non-undo action for this station. The compensating event
     * stores the original action's inverse and references it via undo_of.
     */
    public synchronized Rows.PickEventRow undo(long eventId, long stationId) {
        List<Rows.PickEventRow> rows = eventRepo.findStream(eventId, stationId);
        Rows.PickEventRow target = null;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).undoOf() == null) {
                target = rows.get(i);
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("没有可撤销的动作");
        }
        PickEventType type = PickEventType.valueOf(target.type());
        JsonNode inverse = Json.read(target.inverseJson(), JsonNode.class);
        Rows.PickEventRow undoRow = appendInternal(eventId, stationId, type, inverse,
                Json.read(target.payloadJson(), JsonNode.class), target.seq());
        if (type == PickEventType.CLOCK) {
            var payload = Json.read(target.payloadJson(), JsonNode.class);
            double oldCorrection = payload.get("oldCorrectionMs").asDouble();
            restoreStationVersion(stationId, oldCorrection);
        }
        return undoRow;
    }

    /** Re-add a previous station version carrying the given correction (corrections immutable). */
    private void restoreStationVersion(long stationId, double correctionMs) {
        Rows.StationRow station = stationRepo.findById(stationId);
        for (Rows.StationVersionRow version : stationRepo.findVersions(stationId)) {
            if (version.clockCorrectionMs() == correctionMs) {
                stationRepo.addVersion(stationId, version.lat(), version.lon(),
                        version.elevationMs(), correctionMs, System.currentTimeMillis());
                return;
            }
        }
        Rows.StationVersionRow current = stationRepo.findVersion(stationId,
                station.currentVersion());
        stationRepo.addVersion(stationId, current.lat(), current.lon(), current.elevationMs(),
                correctionMs, System.currentTimeMillis());
    }

    public List<Rows.PickEventRow> stream(long eventId, long stationId) {
        return eventRepo.findStream(eventId, stationId);
    }
}
