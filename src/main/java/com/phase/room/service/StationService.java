package com.phase.room.service;

import com.phase.room.repo.EventRepo;
import com.phase.room.repo.Rows;
import com.phase.room.repo.StationRepo;
import org.springframework.stereotype.Service;

@Service
public class StationService {
    private final StationRepo stationRepo;
    private final EventRepo eventRepo;
    private final PickService pickService;

    public StationService(StationRepo stationRepo, EventRepo eventRepo, PickService pickService) {
        this.stationRepo = stationRepo;
        this.eventRepo = eventRepo;
        this.pickService = pickService;
    }

    /** Metadata edit creates a new immutable station version, carrying the current clock. */
    public Rows.StationVersionRow updateMetadata(long stationId, double lat, double lon,
                                                 double elevation) {
        Rows.StationRow station = require(stationId);
        Rows.StationVersionRow current = stationRepo.findVersion(stationId,
                station.currentVersion());
        return stationRepo.addVersion(stationId, lat, lon, elevation,
                current.clockCorrectionMs(), System.currentTimeMillis());
    }

    /**
     * A clock correction is an undoable pick-log action; the new station version
     * stores the correction so frozen interpretations stay pinned to the old one.
     */
    public Rows.StationVersionRow setClockCorrection(long stationId, double correctionMs) {
        Rows.StationRow station = require(stationId);
        Rows.StationVersionRow current = stationRepo.findVersion(stationId,
                station.currentVersion());
        if (current.clockCorrectionMs() == correctionMs) {
            return current;
        }
        Rows.StationVersionRow next = stationRepo.addVersion(stationId, current.lat(),
                current.lon(), current.elevationMs(), correctionMs, System.currentTimeMillis());
        for (Rows.EventRow event : eventRepo.findAll()) {
            pickService.appendClock(event.id(), stationId, current.clockCorrectionMs(),
                    correctionMs, null);
        }
        return next;
    }

    private Rows.StationRow require(long stationId) {
        Rows.StationRow station = stationRepo.findById(stationId);
        if (station == null) {
            throw new IllegalArgumentException("台站不存在: " + stationId);
        }
        return station;
    }
}
