package com.room.phase;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TravelTimeService {
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final CatalogService catalog;

    public TravelTimeService(CatalogService catalog) {
        this.catalog = catalog;
    }

    public double predictedTimeNs(Map<String, Object> event, Map<String, Object> station, Map<String, Object> model, String phase) {
        return ((Number) event.get("origin_time_ns")).longValue() + travelTimeSec(event, station, model, phase) * 1_000_000_000.0;
    }

    public double travelTimeSec(Map<String, Object> event, Map<String, Object> station, Map<String, Object> model, String phase) {
        double horizontalKm = haversine(
                ((Number) event.get("latitude")).doubleValue(), ((Number) event.get("longitude")).doubleValue(),
                ((Number) station.get("latitude")).doubleValue(), ((Number) station.get("longitude")).doubleValue());
        double sourceDepth = ((Number) event.get("depth_km")).doubleValue();
        double stationDepth = -(((Number) station.get("elevation_m")).doubleValue() / 1000.0);
        double verticalKm = Math.max(0.000001, sourceDepth - stationDepth);
        double pathKm = Math.hypot(horizontalKm, verticalKm);
        double travel = 0;
        List<Models.Layer> layers = catalog.layers(model);
        for (int i = 0; i < layers.size(); i++) {
            double top = layers.get(i).topDepthKm();
            double bottom = i + 1 == layers.size() ? Math.max(sourceDepth, top + 0.000001) : layers.get(i + 1).topDepthKm();
            double segmentTop = Math.max(stationDepth, top);
            double segmentBottom = Math.min(sourceDepth, bottom);
            if (segmentBottom <= segmentTop) continue;
            double dz = segmentBottom - segmentTop;
            double segmentLength = pathKm * dz / verticalKm;
            double velocity = "P".equals(phase) ? layers.get(i).vpKmS() : layers.get(i).vsKmS();
            travel += segmentLength / velocity;
        }
        return travel;
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
