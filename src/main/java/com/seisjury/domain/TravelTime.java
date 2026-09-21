package com.seisjury.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Flat-layered constant-velocity ray tracer. The ray parameter p is found by
 * bisection over a piecewise stack spanning the source depth up to the station
 * elevation (elevation above sea level reduces the first layer thickness).
 */
public final class TravelTime {

    public record Layer(double topKm, double bottomKm, double vp, double vs) {
        public double thickness() {
            return bottomKm - topKm;
        }
    }

    public record Ray(double distanceKm, double timeSec, double rayParam, boolean grazing) {
    }

    private TravelTime() {
    }

    private static double speed(Layer layer, String phase) {
        return "S".equalsIgnoreCase(phase) ? layer.vs : layer.vp;
    }

    private static double[] contribution(double thickness, double velocity, double p) {
        double denom = 1.0d / (velocity * velocity) - p * p;
        if (denom <= 0.0d) {
            return null;
        }
        double cosOverV = Math.sqrt(denom);
        double distance = thickness * p / cosOverV;
        double time = thickness / (velocity * velocity * cosOverV);
        return new double[] {distance, time};
    }

    private static double[] eval(List<Layer> stack, String phase, double p) {
        double distance = 0.0d;
        double time = 0.0d;
        for (Layer layer : stack) {
            double[] c = contribution(layer.thickness(), speed(layer, phase), p);
            if (c == null) {
                return null;
            }
            distance += c[0];
            time += c[1];
        }
        return new double[] {distance, time};
    }

    private static double pMax(List<Layer> stack, String phase) {
        double max = Double.POSITIVE_INFINITY;
        for (Layer layer : stack) {
            max = Math.min(max, 1.0d / speed(layer, phase));
        }
        return max;
    }

    public static Ray trace(List<Layer> modelLayers,
                            double sourceDepthKm,
                            double stationElevationKm,
                            String phase,
                            double targetDistanceKm) {
        List<Layer> ordered = modelLayers.stream()
                .sorted(Comparator.comparingDouble(Layer::topKm))
                .toList();
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("velocity model has no layers");
        }
        double topKm = -stationElevationKm;
        double bottomKm = Math.max(sourceDepthKm, topKm);

        List<Layer> stack = new ArrayList<>();
        for (Layer layer : ordered) {
            double segTop = Math.max(layer.topKm(), topKm);
            double segBottom = Math.min(layer.bottomKm(), bottomKm);
            if (segBottom > segTop + 1.0e-9) {
                stack.add(new Layer(segTop, segBottom, layer.vp, layer.vs));
            }
        }
        if (stack.isEmpty()) {
            Layer shallow = ordered.get(0);
            stack.add(new Layer(topKm, bottomKm, shallow.vp, shallow.vs));
        }

        double maxP = pMax(stack, phase);
        double[] zeroP = eval(stack, phase, 0.0d);
        double distanceAtZero = zeroP[0];
        if (targetDistanceKm <= 0.0d) {
            return new Ray(0.0d, eval(stack, phase, 0.0d)[1], 0.0d, false);
        }
        double[] atMax = eval(stack, phase, Math.nextDown(maxP));
        if (atMax == null || targetDistanceKm > atMax[0]) {
            return new Ray(atMax == null ? Double.POSITIVE_INFINITY : atMax[0],
                    atMax == null ? Double.POSITIVE_INFINITY : atMax[1],
                    maxP, true);
        }

        double lo = 0.0d;
        double hi = Math.nextDown(maxP);
        double[] best = null;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2.0d;
            double[] v = eval(stack, phase, mid);
            if (v == null) {
                hi = mid;
                continue;
            }
            best = v;
            if (v[0] < targetDistanceKm) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double[] result = eval(stack, phase, (lo + hi) / 2.0d);
        if (result == null) {
            result = best;
        }
        return new Ray(targetDistanceKm, result[1], (lo + hi) / 2.0d, false);
    }

    /** Great-circle distance in kilometres (haversine). */
    public static double epicentralKm(double lat1, double lon1, double lat2, double lon2) {
        double earth = 6371.0088d;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2.0d) * Math.sin(dPhi / 2.0d)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLambda / 2.0d) * Math.sin(dLambda / 2.0d);
        return 2.0d * earth * Math.asin(Math.min(1.0d, Math.sqrt(a)));
    }
}
