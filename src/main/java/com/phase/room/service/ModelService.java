package com.phase.room.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phase.room.domain.Geo;
import com.phase.room.domain.Json;
import com.phase.room.domain.Layer;
import com.phase.room.domain.LayeredModel;
import com.phase.room.repo.ModelRepo;
import com.phase.room.repo.Rows;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ModelService {
    private final ModelRepo modelRepo;

    public ModelService(ModelRepo modelRepo) {
        this.modelRepo = modelRepo;
    }

    /** Layers JSON: [{"topDepthM":0,"vpMps":5500,"vsMps":3200}, ...] in fixed order. */
    public List<Layer> parseLayers(String layersJson) {
        JsonNode root = Json.read(layersJson, JsonNode.class);
        if (!root.isArray()) {
            throw new IllegalArgumentException("layers 必须是数组");
        }
        List<Layer> layers = new ArrayList<>();
        for (JsonNode node : root) {
            layers.add(new Layer(node.get("topDepthM").asDouble(),
                    node.get("vpMps").asDouble(), node.get("vsMps").asDouble()));
        }
        LayeredModel.validate(layers);
        return layers;
    }

    public LayeredModel load(long modelId, int version) {
        Rows.ModelVersionRow row = modelRepo.findVersion(modelId, version);
        if (row == null) {
            throw new IllegalArgumentException("模型版本不存在: " + modelId + "#" + version);
        }
        return new LayeredModel(parseLayers(row.layersJson()));
    }

    /** Predicted arrival in epoch milliseconds for a station version. */
    public double predictedArrivalMs(Rows.EventRow event, Rows.StationVersionRow station,
                                     long modelId, int modelVersion, String phase) {
        LayeredModel model = load(modelId, modelVersion);
        double horizontal = Geo.distanceM(event.lat(), event.lon(), station.lat(),
                station.lon());
        double sourceDepth = Math.max(0, event.depthM() - station.elevationM());
        double seconds = model.travelTimeSeconds(horizontal, sourceDepth, phase);
        return event.originTime() + seconds * 1000.0;
    }
}
