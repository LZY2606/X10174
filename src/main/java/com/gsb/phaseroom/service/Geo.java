package com.gsb.phaseroom.service;

/** Deterministic geographic helpers (WGS-84 haversine + local depth-aware metric). */
public final class Geo {

    public static final double EARTH_RADIUS_KM = 6371.0;

    private Geo() {
    }

    /** Great-circle surface distance in kilometres. */
    public static double surfaceDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dphi = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dphi / 2) * Math.sin(dphi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Straight-line source->receiver distance in kilometres using surface distance and
     * receiver elevation above sea level (elevation in metres).
     */
    public static double sourceReceiverKm(double surfaceKm, double sourceDepthKm,
                                          double receiverElevationM) {
        double verticalKm = sourceDepthKm + receiverElevationM / 1000.0;
        return Math.sqrt(surfaceKm * surfaceKm + verticalKm * verticalKm);
    }

    /**
     * Depth (kilometres below sea level, positive downward) of the point a fraction
     * {@code f} (0 at receiver, 1 at source) along the straight ray. Receiver is at
     * negative depth when above sea level.
     */
    public static double rayDepthKm(double receiverElevationM, double sourceDepthKm, double f) {
        double receiverDepthKm = -receiverElevationM / 1000.0;
        return receiverDepthKm + f * (sourceDepthKm - receiverDepthKm);
    }
}
