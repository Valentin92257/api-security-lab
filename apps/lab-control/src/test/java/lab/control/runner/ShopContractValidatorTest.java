package lab.control.runner;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import lab.contracts.json.ContractJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopContractValidatorTest {
    private static final UUID REQUEST_ID = UUID.fromString("e0729341-b0d3-40e1-9870-4b9e41c8af8a");
    private static final String LOGIN = "{\"accessToken\":\"test-token\",\"tokenType\":\"Bearer\",\"expiresIn\":900}";
    private static final String USER = "{\"id\":1,\"login\":\"test_user\",\"name\":\"Test\",\"email\":\"user@example.com\"}";
    private static final String PRODUCT = "{\"id\":1,\"name\":\"Product\",\"description\":\"\",\"price\":100,\"currency\":\"RUB\"}";
    private static final String CART = """
            {"id":1,"userId":1,"revision":0,"items":[],"coupon":null,"subtotal":0,"discount":0,
             "total":0,"currency":"RUB","updatedAt":"2026-11-23T10:00:00Z"}
            """;
    private static final String ORDER = """
            {"id":1,"userId":1,"cartId":1,"cartRevision":0,
             "items":[{"productId":1,"name":"Product","quantity":1,"unitPrice":100,"lineTotal":100}],
             "coupon":null,"subtotal":100,"discount":0,"total":100,"currency":"RUB","createdAt":"2026-11-23T10:00:00Z"}
            """;

    private final ShopContractValidator validator = new ShopContractValidator(new ContractJsonCodec());

    @ParameterizedTest
    @MethodSource("requests")
    void validatesRequestSchemas(ShopRoute route, String body) {
        validator.validateRequest(route, body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"login\":1,\"password\":\"password-secret\"}",
            "{\"login\":\"test_user\",\"password\":null}",
            "{\"login\":\"test_user\",\"password\":\"password-secret\",\"extra\":true}",
            "{\"login\":\"test_user\",\"login\":\"other\",\"password\":\"password-secret\"}",
            "{\"login\":\"test_user\",\"password\":\"password-secret\"} {}",
            "{\"login\":\"test_user\",\"password\":\"password-secret\",}"
    })
    void rejectsInvalidRequestsWithoutExposingPayload(String body) {
        assertThatThrownBy(() -> validator.validateRequest(ShopRoute.LOGIN, body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Shop request violates contract").hasNoCause();
    }

    @Test
    void rejectsMissingAndUnexpectedRequestBodies() {
        assertThatThrownBy(() -> validator.validateRequest(ShopRoute.LOGIN, null))
                .hasMessage("Shop request violates contract").hasNoCause();
        assertThatThrownBy(() -> validator.validateRequest(ShopRoute.PRODUCTS, "{}"))
                .hasMessage("Shop request violates contract").hasNoCause();
        assertThatThrownBy(() -> validator.validateRequest(ShopRoute.CART_ITEM, "{\"quantity\":0}"))
                .hasMessage("Shop request violates contract").hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("responses")
    void validatesSuccessResponsesFromOpenApi(ShopRoute route, int status, String body) {
        var json = validator.validateResponse(route, status, headers("application/json; charset=UTF-8"),
                bytes(body), REQUEST_ID);

        assertThat(json).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"401,UNAUTHORIZED", "429,RATE_LIMITED", "502,UNAVAILABLE"})
    void validatesProblemResponses(int status, String code) {
        var json = validator.validateResponse(ShopRoute.LOGIN, status, headers("application/problem+json"),
                bytes(problem(status, code, REQUEST_ID)), REQUEST_ID);

        assertThat(json.get("status").intValue()).isEqualTo(status);
        assertThat(json.get("code").asString()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"accessToken\":\"token-secret\",\"tokenType\":\"bearer\",\"expiresIn\":900}",
            "{\"accessToken\":\"token-secret\",\"tokenType\":\"Bearer\",\"expiresIn\":901}",
            "{\"accessToken\":\"token-secret\",\"tokenType\":\"Bearer\",\"expiresIn\":900,\"extra\":true}",
            "{\"accessToken\":\"token-secret\",\"tokenType\":\"Bearer\",\"expiresIn\":900} {}",
            "{\"accessToken\":\"token-secret\",\"tokenType\":\"Bearer\",\"expiresIn\":900,}"
    })
    void rejectsMalformedResponsesWithoutExposingToken(String body) {
        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.LOGIN, 200, headers("application/json"),
                bytes(body), REQUEST_ID)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Shop response violates contract").hasNoCause();
    }

    @Test
    void checksProductArrayAndReferencedDefinitions() {
        String tooManyProducts = "[" + String.join(",", Collections.nCopies(101, PRODUCT)) + "]";

        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.PRODUCTS, 200, headers("application/json"),
                bytes(tooManyProducts), REQUEST_ID)).hasMessage("Shop response violates contract");
        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.PRODUCTS, 200, headers("application/json"),
                bytes("[" + PRODUCT.replace("\"id\":1", "\"id\":0") + "]"), REQUEST_ID))
                .hasMessage("Shop response violates contract");
        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.USER, 200, headers("application/json"),
                bytes(USER.replace("user@example.com", "invalid-email")), REQUEST_ID))
                .hasMessage("Shop response violates contract");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/problem+json", "application/*", "*/*", "invalid"})
    void rejectsUnexpectedResponseContentType(String mediaType) {
        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.LOGIN, 200, headers(mediaType),
                bytes(LOGIN), REQUEST_ID)).hasMessage("Shop response violates contract").hasNoCause();
    }

    @Test
    void requiresSingleCorrelatedResponseHeaders() {
        for (HttpHeaders headers : List.of(
                HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (name, value) -> true),
                HttpHeaders.of(Map.of("X-Request-Id", List.of(REQUEST_ID.toString())), (name, value) -> true),
                HttpHeaders.of(Map.of("Content-Type", List.of("application/json", "application/json"),
                        "X-Request-Id", List.of(REQUEST_ID.toString())), (name, value) -> true),
                HttpHeaders.of(Map.of("Content-Type", List.of("application/json"),
                        "X-Request-Id", List.of(UUID.randomUUID().toString())), (name, value) -> true))) {
            assertThatThrownBy(() -> validator.validateResponse(ShopRoute.LOGIN, 200, headers,
                    bytes(LOGIN), REQUEST_ID)).hasMessage("Shop response violates contract").hasNoCause();
        }
    }

    @Test
    void checksProblemCorrelationAndHttpStatus() {
        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.LOGIN, 401, headers("application/problem+json"),
                bytes(problem(429, "RATE_LIMITED", REQUEST_ID)), REQUEST_ID))
                .hasMessage("Shop response violates contract").hasNoCause();
        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.LOGIN, 401, headers("application/problem+json"),
                bytes(problem(401, "UNAUTHORIZED", UUID.randomUUID())), REQUEST_ID))
                .hasMessage("Shop response violates contract").hasNoCause();
        assertThatThrownBy(() -> validator.validateResponse(ShopRoute.LOGIN, 201, headers("application/json"),
                bytes(LOGIN), REQUEST_ID)).hasMessage("Shop response violates contract").hasNoCause();
    }

    private static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of(ShopRoute.REGISTER, "{\"login\":\"test_user\",\"password\":\"password\",\"name\":\"Test\",\"email\":\"user@example.com\"}"),
                Arguments.of(ShopRoute.LOGIN, "{\"login\":\"test_user\",\"password\":\"password\"}"),
                Arguments.of(ShopRoute.CART_ITEM, "{\"quantity\":1}"),
                Arguments.of(ShopRoute.CART_COUPON, "{\"code\":\"WELCOME\"}"),
                Arguments.of(ShopRoute.CHECKOUT, "{\"cartRevision\":0,\"quotedTotal\":100}"),
                Arguments.of(ShopRoute.USER, null), Arguments.of(ShopRoute.PRODUCTS, null),
                Arguments.of(ShopRoute.CART, null), Arguments.of(ShopRoute.ORDER, null));
    }

    private static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of(ShopRoute.REGISTER, 201, USER), Arguments.of(ShopRoute.LOGIN, 200, LOGIN),
                Arguments.of(ShopRoute.USER, 200, USER), Arguments.of(ShopRoute.PRODUCTS, 200, "[" + PRODUCT + "]"),
                Arguments.of(ShopRoute.CART, 200, CART), Arguments.of(ShopRoute.CART_ITEM, 200, CART),
                Arguments.of(ShopRoute.CART_COUPON, 200, CART), Arguments.of(ShopRoute.CHECKOUT, 201, ORDER),
                Arguments.of(ShopRoute.ORDER, 200, ORDER));
    }

    private static HttpHeaders headers(String mediaType) {
        return HttpHeaders.of(Map.of("Content-Type", List.of(mediaType),
                "X-Request-Id", List.of(REQUEST_ID.toString())), (name, value) -> true);
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String problem(int status, String code, UUID requestId) {
        return """
                {"type":"urn:lab:problem:http-error","title":"Request failed","status":%d,"code":"%s","requestId":"%s"}
                """.formatted(status, code, requestId);
    }
}
