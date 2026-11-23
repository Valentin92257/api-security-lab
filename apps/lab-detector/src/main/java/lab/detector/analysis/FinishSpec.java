package lab.detector.analysis;

import java.time.Instant;

import lab.contracts.detector.model.FinishAnalysisRequest;
import lab.contracts.json.ContractJsonSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record FinishSpec(int gatewayLastSequence, int shopLastSequence, Instant deadlineAt) {
    private static final JsonMapper JSON = ContractJsonSupport.createJsonMapper();

    /** Used by Spring's standard String-to-object conversion during JDBC record mapping. */
    public FinishSpec(String json) {
        this(JSON.readTree(json));
    }

    private FinishSpec(JsonNode json) {
        this(json.get("gatewayLastSequence").asInt(), json.get("shopLastSequence").asInt(),
                Instant.parse(json.get("deadlineAt").asString()));
    }

    public static FinishSpec from(FinishAnalysisRequest request) {
        return new FinishSpec(request.getGatewayLastSequence(), request.getShopLastSequence(),
                request.getDeadlineAt().toInstant());
    }

    public static FinishSpec from(JsonNode json) {
        return new FinishSpec(json);
    }
}
