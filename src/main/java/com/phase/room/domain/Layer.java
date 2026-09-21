package com.phase.room.domain;

public record Layer(double topDepthM, double vpMps, double vsMps) {
    public double velocity(String phase) {
        return "S".equalsIgnoreCase(phase) ? vsMps : vpMps;
    }
}
