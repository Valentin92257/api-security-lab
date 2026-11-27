package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;

/**
 * Типовые запросы сценариев к Shop: вход, вызовы с JWT и разбор результата.
 */
@Component
class ShopRequests {
    private static final String LOGIN_ROUTE = "/api/v1/auth/login";

    private final ShopContractValidator validator;
    private final JsonMapper json;

    ShopRequests(ShopContractValidator validator, ContractJsonCodec codec) {
        this.validator = validator;
        this.json = codec.jsonMapper();
    }

    StepRequest login(GatewayTarget target, String clientIp, String login, String password) {
        Objects.requireNonNull(login);
        Objects.requireNonNull(password);
        requireClientIp(clientIp);
        var body = json.createObjectNode().put("login", login).put("password", password);
        String payload = json.writeValueAsString(body);
        validator.validateLoginRequest(payload);
        return new StepRequest(target, "POST", LOGIN_ROUTE,
                Map.of("Content-Type", "application/json", "X-Forwarded-For", clientIp), payload);
    }

    static StepRequest authorized(GatewayTarget target, String clientIp, String token,
                                  String method, String route, String jsonBody) {
        requireClientIp(clientIp);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Access token must not be blank");
        }
        Map<String, String> headers = jsonBody == null
                ? Map.of("Authorization", "Bearer " + token, "X-Forwarded-For", clientIp)
                : Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json",
                "X-Forwarded-For", clientIp);
        return new StepRequest(target, method, route, headers, jsonBody);
    }

    static boolean succeeded(StepResponse response, int status) {
        return response.result().outcome() == StepOutcome.RESPONSE
                && Integer.valueOf(status).equals(response.result().status());
    }

    static String accessToken(StepResponse login) {
        if (!succeeded(login, 200) || login.body() == null) {
            return null;
        }
        JsonNode token = login.body().get("accessToken");
        return token != null && token.isString() ? token.asString() : null;
    }

    private static void requireClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank() || clientIp.length() < 2 || clientIp.length() > 45) {
            throw new IllegalArgumentException("Client IP must be 2..45 non-blank characters");
        }
    }
}
