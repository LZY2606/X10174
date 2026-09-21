package com.phase.room.domain;

/** Half-open absolute time interval in epoch milliseconds. */
public record Interval(double startMs, double endMs) {

    public boolean overlaps(Interval other, double toleranceMs) {
        return startMs < other.endMs - toleranceMs && other.startMs < endMs - toleranceMs;
    }

    public double gapBeforeMs(Interval other) {
        return other.startMs - endMs;
    }
}
