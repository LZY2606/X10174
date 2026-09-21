package com.phase.room.service;

import com.phase.room.domain.ConflictReport;
import com.phase.room.domain.Json;
import com.phase.room.repo.InterpretationRepo;
import com.phase.room.repo.ModelRepo;
import com.phase.room.repo.Rows;
import com.phase.room.repo.StationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InterpretationService {
    private final InterpretationRepo interpretationRepo;
    private final StationRepo stationRepo;
    private final ModelRepo modelRepo;
    private final PickService pickService;
    private final CompareService compareService;

    public record StationPin(long stationId, int stationVersion, int pickSeq) {
    }

    public record PinnedVersion(long interpretationVersionId, long interpretationId,
                                String interpretationName, int version, boolean frozen,
                                List<StationPin> stations, Long modelId, Integer modelVersion,
                                List<Long> candidateIds) {
    }

    public record FreezeResult(boolean frozen, long interpretationVersionId,
                               ConflictReport conflict) {
    }

    public InterpretationService(InterpretationRepo interpretationRepo, StationRepo stationRepo,
                                 ModelRepo modelRepo, PickService pickService,
                                 CompareService compareService) {
        this.interpretationRepo = interpretationRepo;
        this.stationRepo = stationRepo;
        this.modelRepo = modelRepo;
        this.pickService = pickService;
        this.compareService = compareService;
    }

    @Transactional
    public PinnedVersion createVersion(long eventId, String interpretationName,
                                       List<StationPin> stations, Long modelId,
                                       Integer modelVersion) {
        List<StationPin> ordered = ordered(stations);
        Rows.InterpretationRow interp = interpretationRepo.findByName(interpretationName);
        if (interp == null) {
            interp = interpretationRepo.insert(interpretationName, null,
                    System.currentTimeMillis());
        }
        long versionId = interpretationRepo.addVersion(interp.id(),
                Json.write(pickPinJson(eventId, ordered)), Json.write(stationPinJson(ordered)),
                modelId, modelVersion, System.currentTimeMillis());
        for (StationPin pin : ordered) {
            var state = pickService.stateAt(eventId, pin.stationId(), pin.pickSeq());
            state.candidates.values().stream()
                    .filter(c -> "active".equals(c.status))
                    .sorted(Comparator.comparingLong(c -> c.id))
                    .forEach(c -> interpretationRepo.addPick(versionId, c.id));
        }
        return loadVersion(versionId);
    }

    /** Freeze only allowed when no correction reverses the predicted order. */
    public FreezeResult freeze(long eventId, long interpretationVersionId) {
        Rows.InterpretationVersionRow row = interpretationRepo.findVersionById(
                interpretationVersionId);
        if (row == null) {
            throw new IllegalArgumentException("解释版本不存在");
        }
        if (row.frozen()) {
            return new FreezeResult(true, interpretationVersionId,
                    new ConflictReport(false, List.of(), List.of()));
        }
        List<StationPin> pins = parsePins(eventIdOf(row), row);
        ConflictReport report = compareService.freezeConflicts(eventId, stationMap(pins),
                pickMap(pins), row.modelId(), row.modelVersion());
        if (report.conflict()) {
            return new FreezeResult(false, interpretationVersionId, report);
        }
        interpretationRepo.freeze(interpretationVersionId);
        return new FreezeResult(true, interpretationVersionId, report);
    }

    private long eventIdOf(Rows.InterpretationVersionRow row) {
        return Json.read(row.pickVersionsJson(), PinDoc.class).eventId;
    }

    /** Branch a (possibly frozen) interpretation; optionally swap the velocity model. */
    public PinnedVersion branch(long eventId, long sourceVersionId, String newName,
                                Long replacementModelId, Integer replacementModelVersion) {
        Rows.InterpretationVersionRow source = interpretationRepo.findVersionById(sourceVersionId);
        if (source == null) {
            throw new IllegalArgumentException("源解释版本不存在");
        }
        List<StationPin> pins = parsePins(eventId, source);
        Long modelId = replacementModelId != null ? replacementModelId : source.modelId();
        Integer modelVersion = replacementModelVersion != null
                ? replacementModelVersion : source.modelVersion();
        Rows.InterpretationRow sourceInterp = interpretationRepo.findById(
                source.interpretationId());
        interpretationRepo.insert(newName, sourceInterp.id(), System.currentTimeMillis());
        return createVersion(eventId, newName, pins, modelId, modelVersion);
    }

    public PinnedVersion loadVersion(long interpretationVersionId) {
        Rows.InterpretationVersionRow row = interpretationRepo.findVersionById(
                interpretationVersionId);
        Rows.InterpretationRow interp = interpretationRepo.findById(row.interpretationId());
        List<StationPin> pins = parsePins(eventIdOf(row), row);
        return new PinnedVersion(row.id(), row.interpretationId(), interp.name(), row.version(),
                row.frozen(), pins, row.modelId(), row.modelVersion(),
                interpretationRepo.pickIds(row.id()));
    }

    public List<PinnedVersion> listVersions(long interpretationId) {
        return interpretationRepo.versions(interpretationId).stream()
                .map(v -> loadVersion(v.id()))
                .toList();
    }

    public List<Rows.InterpretationRow> listInterpretations() {
        return interpretationRepo.findAll();
    }

    private List<StationPin> ordered(List<StationPin> stations) {
        List<StationPin> copy = new ArrayList<>(stations);
        copy.sort(Comparator.comparingLong(StationPin::stationId));
        return List.copyOf(copy);
    }

    private Map<Long, Integer> stationMap(List<StationPin> pins) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (StationPin pin : pins) {
            map.put(pin.stationId(), pin.stationVersion());
        }
        return map;
    }

    private Map<Long, Integer> pickMap(List<StationPin> pins) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (StationPin pin : pins) {
            map.put(pin.stationId(), pin.pickSeq());
        }
        return map;
    }

    private List<StationPin> parsePins(long eventId, Rows.InterpretationVersionRow row) {
        PinDoc doc = Json.read(row.pickVersionsJson(), PinDoc.class);
        List<StationPin> pins = new ArrayList<>();
        for (PinStation station : doc.stations) {
            pins.add(new StationPin(station.stationId, station.stationVersion, station.pickSeq));
        }
        return ordered(pins);
    }

    private PinDoc pickPinJson(long eventId, List<StationPin> stations) {
        PinDoc doc = new PinDoc();
        doc.eventId = eventId;
        doc.stations = new ArrayList<>();
        for (StationPin pin : stations) {
            PinStation station = new PinStation();
            station.stationId = pin.stationId();
            station.stationVersion = pin.stationVersion();
            station.pickSeq = pin.pickSeq();
            doc.stations.add(station);
        }
        return doc;
    }

    private PinDoc stationPinJson(List<StationPin> stations) {
        return pickPinJson(0, stations);
    }

    public static class PinDoc {
        public long eventId;
        public List<PinStation> stations = new ArrayList<>();
    }

    public static class PinStation {
        public long stationId;
        public int stationVersion;
        public int pickSeq;
    }
