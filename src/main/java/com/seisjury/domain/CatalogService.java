package com.seisjury.domain;

import com.seisjury.db.Repositories;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final Repositories repo;
    private final Clock clock;

    public CatalogService(Repositories repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional
    public void createEvent(String code, long originMs, double lat, double lon, double depthKm) {
        if (repo.findEvent(code) != null) {
            throw new ConflictException("event already exists: " + code);
        }
        repo.insertEvent(code, originMs, lat, lon, depthKm);
    }

    @Transactional
    public Repositories.StationRow addStationVersion(String code, double lat, double lon, double elevationM) {
        repo.insertStation(code, lat, lon, elevationM, clock.nowMs());
        return repo.stationAtVersion(code, null);
    }

    @Transactional
    public Repositories.ModelRow addModelVersion(String code, List<double[]> layers) {
        validateLayers(layers);
        long id = repo.insertModel(code, layers, clock.nowMs());
        return repo.jdbc().query("SELECT * FROM velocity_model WHERE id = ?",
                (rs, i) -> new Repositories.ModelRow(rs.getLong("id"), rs.getString("model_code"),
                        rs.getInt("version"), rs.getLong("created_ms")), id).get(0);
    }

    private void validateLayers(List<double[]> layers) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("model must contain at least one layer");
        }
        double prevBottom = Double.NEGATIVE_INFINITY;
        double top = layers.get(0)[0];
        if (top > 0.0d) {
            throw new IllegalArgumentException("first layer top must start at or above sea level (0 km)");
        }
        for (double[] layer : layers) {
            if (layer[1] <= layer[0] || layer[2] <= 0 || layer[3] <= 0) {
                throw new IllegalArgumentException("invalid layer: "
                        + layer[0] + "," + layer[1] + "," + layer[2] + "," + layer[3]);
            }
            if (layer[0] != top && layer[0] != prevBottom) {
                throw new IllegalArgumentException("layers must be contiguous");
            }
            top = layer[1];
            prevBottom = layer[1];
        }
    }
}
