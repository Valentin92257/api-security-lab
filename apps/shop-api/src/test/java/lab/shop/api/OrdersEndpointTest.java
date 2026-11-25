package lab.shop.api;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.LineItem;
import lab.contracts.shop.model.Order;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.JwtService;
import lab.shop.application.model.CheckoutResult;
import lab.shop.application.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ActiveProfiles("protected")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrdersEndpointTest {

    private static final long CURRENT_USER_ID = 1L;
    private static final long CART_ID = 10L;
    private static final long ORDER_ID = 50L;
    private static final String IDEMPOTENCY_KEY = "idem-key-1234567890";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    private OrderService orderService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JsonMapper jsonMapper;

    private HttpClient client;

    @BeforeEach
    void setUpClient() {
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDownClient() {
        client.close();
    }

    @Test
    @DisplayName("GET /orders/{orderId} возвращает 200 и поля Order")
    void getOrderReturns200() throws Exception {
        when(orderService.getOrder(ORDER_ID)).thenReturn(sampleOrder());

        HttpResponse<String> response = get("/orders/" + ORDER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(ORDER_ID);
        assertThat(body.get("userId").asLong()).isEqualTo(CURRENT_USER_ID);
        assertThat(body.get("total").asLong()).isEqualTo(1500L);
        assertThat(body.get("currency").asString()).isEqualTo("RUB");
    }

    @Test
    @DisplayName("GET /orders/{orderId} при NotFoundException возвращает 404 NOT_FOUND")
    void getMissingOrderReturns404() throws Exception {
        when(orderService.getOrder(ORDER_ID)).thenThrow(new NotFoundException("Заказ не найден"));

        HttpResponse<String> response = get("/orders/" + ORDER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("GET /orders/{orderId} с нечисловым id возвращает 400 INVALID_REQUEST до вызова сервиса")
    void getOrderWithMalformedIdReturns400() throws Exception {
        HttpResponse<String> response = get("/orders/not-a-number", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("GET /orders/{orderId} без токена возвращает 401")
    void getOrderWithoutTokenReturns401() throws Exception {
        HttpResponse<String> response = get("/orders/" + ORDER_ID, null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("POST /orders без токена возвращает 401")
    void checkoutWithoutTokenReturns401() throws Exception {
        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("POST /orders без заголовка Idempotency-Key возвращает 400 INVALID_REQUEST до вызова сервиса")
    void checkoutWithoutIdempotencyKeyReturns400() throws Exception {
        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                null, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("POST /orders при первом оформлении возвращает 201, Order и Idempotency-Replayed: false")
    void checkoutCreatesOrderWithReplayedFalse() throws Exception {
        when(orderService.checkout(eq(IDEMPOTENCY_KEY), eq(new CheckoutRequest(1L, 1500L))))
                .thenReturn(new CheckoutResult(sampleOrder(), false));

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(ORDER_ID);
        assertThat(body.get("total").asLong()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("POST /orders при повторе возвращает 201 и Idempotency-Replayed: true")
    void checkoutReplayReturnsHeaderTrue() throws Exception {
        when(orderService.checkout(eq(IDEMPOTENCY_KEY), eq(new CheckoutRequest(1L, 1500L))))
                .thenReturn(new CheckoutResult(sampleOrder(), true));

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(json(response).get("id").asLong()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("POST /orders при BadRequestException возвращает 400 INVALID_REQUEST")
    void checkoutBadRequestReturns400() throws Exception {
        when(orderService.checkout(any(), any())).thenThrow(new BadRequestException("Некорректный Idempotency-Key"));

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                "short", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("POST /orders при ConflictException возвращает 409 CONFLICT")
    void checkoutConflictReturns409() throws Exception {
        when(orderService.checkout(any(), any())).thenThrow(new ConflictException("Конфликт"));

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("GET неизвестного маршрута возвращает 404 NOT_FOUND")
    void unknownRouteReturns404() throws Exception {
        HttpResponse<String> response = get("/unknown-route", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Неподдерживаемый метод известного маршрута возвращает 405 с Allow")
    void unsupportedMethodReturns405WithAllow() throws Exception {
        HttpResponse<String> response = delete("/orders", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(
                allow -> assertThat(allow).contains("POST"));
    }

    private static Order sampleOrder() {
        return new Order(
                ORDER_ID,
                CURRENT_USER_ID,
                CART_ID,
                1L,
                List.of(new LineItem(5L, "Лампа", 1, 1500, 1500L)),
                null,
                1500L,
                0L,
                1500L,
                Order.CurrencyEnum.RUB,
                T0.atOffset(ZoneOffset.UTC));
    }

    private String tokenFor(long userId) {
        return jwtService.issue(userId);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body, String idempotencyKey, String token) throws Exception {
        String json = jsonMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .DELETE();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }
}
