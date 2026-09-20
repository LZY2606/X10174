package com.gsb.phaseroom.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Central, deterministic clock for persistence timestamps. */
public final class Clock {

    private Clock() {
    }

    public static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
