package com.phase.room.domain;

import java.util.List;

public record ConflictReport(boolean conflict, List<Long> stationIds, List<long[]> pairs) {
}
