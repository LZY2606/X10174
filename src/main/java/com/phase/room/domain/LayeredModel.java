package com.phase.room.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Piecewise-constant 1D half-space. Layers are sorted by ascending top depth;
 * the last layer extends to infinite depth.
 *
 * Direct rays are the ascending (source -> surface) ray whose ray parameter is
 * found by bisection; head waves along interfaces with a faster layer below are
 * also considered, and the earliest arrival wins.
 */
public record LayeredModel(List<Layer> layers) {

    public LayeredModel {
        List<Layer> copy = new ArrayList<>(layers);
        copy.sort(Comparator.comparingDouble(Layer::topDepthM));
        layers = List.copyOf(copy);
    }

    public static void validate(List<Layer> layers) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("速度模型至少需要一层");
        }
        if (Math.abs(layers.get(0).topDepthM()) > 1e-9) {
            throw new IllegalArgumentException("第一层顶部深度必须为 0");
        }
        for (Layer layer : layers) {
            if (layer.vpMps() <= 0 || layer.vsMps() <= 0) {
                throw new IllegalArgumentException("层速度必须为正数");
            }
        }
        for (int i = 1; i < layers.size(); i++) {
            if (layers.get(i).topDepthM() <= layers.get(i - 1).topDepthM()) {
                throw new IllegalArgumentException("层顶深度必须严格递增");
            }
        }
    }

    private double[] bounds() {
        double[] tops = new double[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            tops[i] = layers.get(i).topDepthM();
        }
        return tops;
    }

    private record Leg(double dx, double dt) {
    }

    /** Straight ray segment accumulated over depth interval [fromDepth, toDepth]. */
    private Leg leg(double fromDepth, double toDepth, double p, String phase, double[] tops) {
        double dx = 0;
        double dt = 0;
        int startLayer = layerIndex(fromDepth, tops);
        int endLayer = layerIndex(Math.nextUp(toDepth), tops);
        for (int i = startLayer; i <= endLayer; i++) {
            double z0 = Math.max(fromDepth, tops[i]);
            double z1 = Math.min(toDepth, i + 1 < tops.length ? tops[i + 1] : Double.POSITIVE_INFINITY);
            if (z1 <= z0) {
                continue;
            }
            double v = layers.get(i).velocity(phase);
            double pv = p * v;
            if (pv >= 1.0) {
                return null;
            }
            double cos = Math.sqrt(1.0 - pv * pv);
            double dz = z1 - z0;
            dx += dz * pv / cos;
            dt += dz / (v * cos);
        }
        return new Leg(dx, dt);
    }

    private int layerIndex(double depth, double[] tops) {
        int idx = 0;
        for (int i = 0; i < tops.length; i++) {
            if (depth >= tops[i]) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }

    /**
     * Travel time in seconds for horizontal distance x metres, source at sourceDepthM,
     * observed at the surface (z = 0). Phase is P or S.
     */
    public double travelTimeSeconds(double x, double sourceDepthM, String phase) {
        double[] tops = bounds();
        double best = directTime(x, sourceDepthM, phase, tops);

        for (int k = 0; k + 1 < layers.size(); k++) {
            double headDepth = tops[k + 1];
            if (headDepth <= sourceDepthM) {
                continue;
            }
            double vHead = layers.get(k + 1).velocity(phase);
            double p = 1.0 / vHead;
            Leg down = leg(sourceDepthM, headDepth, p, phase, tops);
            Leg up = leg(0.0, headDepth, p, phase, tops);
            if (down == null || up == null) {
                continue;
            }
            double headLength = x - down.dx() - up.dx();
            if (headLength < -1e-9) {
                continue;
            }
            double t = down.dt() + up.dt() + Math.max(0, headLength) / vHead;
            if (!Double.isNaN(t) && (Double.isNaN(best) || t < best)) {
                best = t;
            }
        }
        if (Double.isNaN(best)) {
            throw new IllegalStateException("无法计算走时: x=" + x + " depth=" + sourceDepthM);
        }
        return best;
    }

    private double directTime(double x, double sourceDepthM, String phase, double[] tops) {
        int sourceLayer = layerIndex(Math.nextUp(sourceDepthM), tops);
        double vSource = layers.get(sourceLayer).velocity(phase);
        double hi = (1.0 / vSource) * Math.nextDown(1.0);
        if (range(0.0, sourceDepthM, phase, tops) > x) {
            return Double.NaN;
        }
        if (range(hi, sourceDepthM, phase, tops) < x) {
            return Double.NaN;
        }
        double lo = 0.0;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            double r = range(mid, sourceDepthM, phase, tops);
            if (r < x) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double p = (lo + hi) / 2;
        Leg up = leg(0.0, sourceDepthM, p, phase, tops);
        return up == null ? Double.NaN : up.dt();
    }

    private double range(double p, double sourceDepthM, String phase, double[] tops) {
        Leg up = leg(0.0, sourceDepthM, p, phase, tops);
        return up == null ? Double.NaN : up.dx();
    }
}
