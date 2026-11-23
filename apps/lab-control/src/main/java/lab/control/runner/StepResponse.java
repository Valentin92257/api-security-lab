package lab.control.runner;

import tools.jackson.databind.JsonNode;

public record StepResponse(StepResult result, JsonNode body) {
    @Override
    public String toString() {
        return "StepResponse[result=" + result + "]";
    }
}
