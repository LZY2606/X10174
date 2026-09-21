package com.seismic.deliberation.service;

public final class TravelTime {
    private static final double EARTH_RADIUS_KM = 6371.0;

    private TravelTime() {}

    public static double epicentralKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    public static long arrivalMs(long originMs, double epicentralKm, double depthKm,
                                 double elevationM, double velocityKmS) {
        double vertical = depthKm + elevationM / 1000.0;
        double path = Math.hypot(epicentralKm, vertical);
        return originMs + Math.round(path / velocityKmS * 1000.0);
    }
}
