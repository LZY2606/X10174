package com.gsb.phaseroom.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VelocityModels {

    public record Layer(long id, double topDepthKm, double vpKms, double vsKms, int ord) {
    }

    public record Model(long id, String code, String name, int version, List<Layer> layers) {
    }

    private final JdbcTemplate jdbc;

    public VelocityModels(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Model create(String code, String name, List<double[]> layerInputs) {
        validate(layerInputs);
        jdbc.update(
                "INSERT INTO velocity_model(code, name, version, created_at) VALUES (?,?,?,?)",
                code, name, 1, Clock.now());
        long id = jdbc.queryForObject("SELECT id FROM velocity_model WHERE code = ?",
                Long.class, code);
        int ord = 0;
        for (double[] layer : layerInputs) {
            jdbc.update(
                    "INSERT INTO velocity_layer(model_id, top_depth_km, vp_kms, vs_kms, ord) "
                            + "VALUES (?,?,?,?,?)",
                    id, layer[0], layer[1], layer[2], ord++);
        }
        return get(id);
    }

    private void validate(List<double[]> layers) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("速度模型至少需要一层");
        }
        for (double[] layer : layers) {
            if (layer.length != 3 || layer[1] <= 0 || layer[2] <= 0) {
                throw new IllegalArgumentException("每层必须包含 topDepthKm、正的 Vp 与 Vs");
            }
        }
        List<double[]> sorted = new ArrayList<>(layers);
        sorted.sort(Comparator.comparingDouble(l -> l[0]));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i)[0] <= sorted.get(i - 1)[0]) {
                throw new IllegalArgumentException("层顶深度必须严格递增");
            }
        }
    }

    public Model get(long id) {
        var rows = jdbc.queryForList(
                "SELECT id, code, name, version FROM velocity_model WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("速度模型不存在: " + id);
        }
        var m = rows.get(0);
        List<Layer> layers = jdbc.query(
                "SELECT id, top_depth_km, vp_kms, vs_kms, ord FROM velocity_layer "
                        + "WHERE model_id = ? ORDER BY ord",
                (rs, i) -> new Layer(rs.getLong("id"), rs.getDouble("top_depth_km"),
                        rs.getDouble("vp_kms"), rs.getDouble("vs_kms"), rs.getInt("ord")),
                id);
        return new Model(((Number) m.get("id")).longValue(), (String) m.get("code"),
                (String) m.get("name"), ((Number) m.get("version")).intValue(), layers);
    }

    public List<Model> list() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM velocity_model ORDER BY code ASC, id ASC", Long.class);
        List<Model> out = new ArrayList<>();
        for (Long id : ids) {
            out.add(get(id));
        }
        return out;
    }

    /**
     * Travel time in seconds along a straight receiver->source ray, integrating through
     * the flat layered model. Layers above the shallowest layer top use the top-layer
     * velocity; no ray is allowed below the deepest layer (source depth must be inside).
     */
    public double travelTimeSeconds(Model model, double surfaceKm, double sourceDepthKm,
                                    double receiverElevationM, String phase) {
        List<Layer> layers = new ArrayList<>(model.layers());
        layers.sort(Comparator.comparingDouble(Layer::topDepthKm));
        if (sourceDepthKm < layers.get(0).topDepthKm()) {
            throw new IllegalArgumentException("震源深度浅于模型最顶层: " + model.code());
        }
        double totalKm = Geo.sourceReceiverKm(surfaceKm, sourceDepthKm, receiverElevationM);
        double receiverDepthKm = -receiverElevationM / 1000.0;
        int intervals = Math.max(2000, layers.size() * 200);
        double travel = 0;
        double previousDepth = Geo.rayDepthKm(receiverElevationM, sourceDepthKm, 0);
        double previousV = velocityAt(layers, previousDepth, phase);
        double prevDs = 0;
        for (int i = 1; i <= intervals; i++) {
            double f = (double) i / intervals;
            double depth = Geo.rayDepthKm(receiverElevationM, sourceDepthKm, f);
            double ds = f * totalKm;
            double v = velocityAt(layers, depth, phase);
            double segKm = ds - prevDs;
            travel += segKm / (0.5 * (previousV + v));
            previousV = v;
            prevDs = ds;
            previousDepth = depth;
        }
        return travel;
    }

    private double velocityAt(List<Layer> layersAsc, double depthKm, String phase) {
        double deepest = layersAsc.get(layersAsc.size() - 1).topDepthKm();
        double d = Math.min(Math.max(depthKm, layersAsc.get(0).topDepthKm()), deepest);
        Layer chosen = layersAsc.get(0);
        for (Layer layer : layersAsc) {
            if (d >= layer.topDepthKm()) {
                chosen = layer;
            } else {
                break;
            }
        }
        return "S".equals(phase) ? chosen.vsKms() : chosen.vpKms();
    }
}
