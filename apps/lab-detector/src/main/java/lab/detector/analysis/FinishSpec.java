package lab.detector.analysis;

import java.time.Instant;

import lab.contracts.detector.model.FinishAnalysisRequest;
import tools.jackson.databind.JsonNode;

public record FinishSpec(int gatewayLastSequence, int shopLastSequence, Instant deadlineAt) {
    public static FinishSpec from(FinishAnalysisRequest request) {
        return new FinishSpec(request.getGatewayLastSequence(), request.getShopLastSequence(),
                request.getDeadlineAt().toInstant());
    }

    public static FinishSpec from(JsonNode json) {
        return new FinishSpec(json.get("gatewayLastSequence").asInt(), json.get("shopLastSequence").asInt(),
                Instant.parse(json.get("deadlineAt").asString()));
    }
}
