package com.phase.room.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable fold state: the complete candidate set for one event/station. */
public final class PickState {
    public final Map<Long, Candidate> candidates = new LinkedHashMap<>();
    public long nextCandidateId = 1;

    public Candidate active(long id) {
        Candidate candidate = candidates.get(id);
        if (candidate == null) {
            throw new IllegalArgumentException("候选不存在: " + id);
        }
        if (!"active".equals(candidate.status)) {
            throw new IllegalArgumentException("候选不是活动状态: " + id);
        }
        return candidate;
    }
}
