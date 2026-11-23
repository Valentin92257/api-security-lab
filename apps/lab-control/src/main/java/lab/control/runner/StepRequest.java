package lab.control.runner;

import java.util.Map;
import java.util.Objects;

public record StepRequest(GatewayTarget target, String method, String route,
                          Map<String, String> headers, String body) {
    public StepRequest {
        Objects.requireNonNull(target);
        Objects.requireNonNull(method);
        Objects.requireNonNull(route);
        headers = Map.copyOf(headers);
    }

    @Override
    public String toString() {
        return "StepRequest[target=" + target + ", method=" + method + "]";
    }
}
