package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import lab.contracts.json.ContractValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopRequestsTest {
    private final ContractJsonCodec json = new ContractJsonCodec();
    private final ShopRequests requests = new ShopRequests(new ShopContractValidator(json), json);

    @Test
    void buildsAValidatedLoginRequest() {
        var request = requests.login(GatewayTarget.PROTECTED, "198.51.100.10", "cs_benign_01", "password-secret");

        assertThat(request.target()).isEqualTo(GatewayTarget.PROTECTED);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.route()).isEqualTo("/api/v1/auth/login");
        assertThat(request.headers()).isEqualTo(Map.of(
                "Content-Type", "application/json", "X-Forwarded-For", "198.51.100.10"));
        JsonNode body = json.jsonMapper().readTree(request.body());
        assertThat(body.get("login").asString()).isEqualTo("cs_benign_01");
        assertThat(body.get("password").asString()).isEqualTo("password-secret");
        assertThat(request.toString()).doesNotContain("password-secret");
    }

    @Test
    void rejectsALoginThatViolatesTheContract() {
        assertThatThrownBy(() -> requests.login(GatewayTarget.PROTECTED, "198.51.100.10", "cs_benign_01", ""))
                .isInstanceOf(ContractValidationException.class);
    }

    @Test
    void addsBearerAndClientAddressWithoutContentTypeForBodilessRequests() {
        var request = ShopRequests.authorized(GatewayTarget.VULNERABLE, "198.51.100.11", "jwt-secret",
                "GET", "/api/v1/products", null);

        assertThat(request.target()).isEqualTo(GatewayTarget.VULNERABLE);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.route()).isEqualTo("/api/v1/products");
        assertThat(request.headers()).isEqualTo(Map.of(
                "Authorization", "Bearer jwt-secret", "X-Forwarded-For", "198.51.100.11"));
        assertThat(request.body()).isNull();
        assertThat(request.toString()).doesNotContain("jwt-secret");
    }

    @Test
    void addsContentTypeForJsonBodies() {
        var request = ShopRequests.authorized(GatewayTarget.PROTECTED, "198.51.100.11", "jwt-secret",
                "PUT", "/api/v1/cart/items/3", "{\"quantity\":1}");

        assertThat(request.headers()).isEqualTo(Map.of("Authorization", "Bearer jwt-secret",
                "Content-Type", "application/json", "X-Forwarded-For", "198.51.100.11"));
        assertThat(request.body()).isEqualTo("{\"quantity\":1}");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "RESPONSE,200,200,true",
            "RESPONSE,401,200,false",
            "RESPONSE,201,201,true",
            "CONTRACT_VIOLATION,200,200,false",
            "TIMEOUT,null,200,false"
    })
    void checksOutcomeAndExpectedStatus(StepOutcome outcome, Integer status, int expected, boolean succeeded) {
        assertThat(ShopRequests.succeeded(response(outcome, status, null), expected)).isEqualTo(succeeded);
    }

    @Test
    void extractsTheTokenOnlyFromASuccessfulLoginWithABody() {
        JsonNode token = json.jsonMapper().createObjectNode()
                .put("accessToken", "jwt-secret").put("tokenType", "Bearer").put("expiresIn", 900);
        JsonNode withoutToken = json.jsonMapper().createObjectNode().put("id", 1);
        JsonNode nonStringToken = json.jsonMapper().createObjectNode().put("accessToken", 123);

        assertThat(ShopRequests.accessToken(response(StepOutcome.RESPONSE, 200, token))).isEqualTo("jwt-secret");
        assertThat(ShopRequests.accessToken(response(StepOutcome.RESPONSE, 200, null))).isNull();
        assertThat(ShopRequests.accessToken(response(StepOutcome.RESPONSE, 200, withoutToken))).isNull();
        assertThat(ShopRequests.accessToken(response(StepOutcome.RESPONSE, 200, nonStringToken))).isNull();
        assertThat(ShopRequests.accessToken(response(StepOutcome.RESPONSE, 401, token))).isNull();
        assertThat(ShopRequests.accessToken(response(StepOutcome.CONTRACT_VIOLATION, 200, token))).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "1", "1234567890123456789012345678901234567890123456"})
    void rejectsInvalidClientAddresses(String clientIp) {
        assertThatThrownBy(() -> requests.login(GatewayTarget.PROTECTED, clientIp, "cs_benign_01", "password-secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShopRequests.authorized(GatewayTarget.PROTECTED, clientIp, "jwt-secret",
                "GET", "/api/v1/products", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsBlankAccessTokens(String token) {
        assertThatThrownBy(() -> ShopRequests.authorized(GatewayTarget.PROTECTED, "198.51.100.10", token,
                "GET", "/api/v1/products", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StepResponse response(StepOutcome outcome, Integer status, JsonNode body) {
        StepResult result = mock(StepResult.class);
        when(result.outcome()).thenReturn(outcome);
        when(result.status()).thenReturn(status);
        return new StepResponse(result, body);
    }
}
