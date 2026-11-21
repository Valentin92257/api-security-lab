package lab.shop.api;

import lab.contracts.shop.model.LineItem;
import lab.contracts.shop.model.Order;
import lab.shop.api.security.JwtService;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.OrderResponse;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CartEndpointTest.NoOpTransactionManagerConfig.class)
public abstract class OrdersEndpointTest {

    protected static final long CURRENT_USER_ID = 1L;
    protected static final long OTHER_USER_ID = 2L;
    protected static final long CART_ID = 10L;
    protected static final long ORDER_ID = 50L;
    protected static final String IDEMPOTENCY_KEY = "idem-key-1234567890";
    protected static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    protected OrderRepository orderRepository;

    @MockitoBean
    protected CartRepository cartRepository;

    @MockitoBean
    protected CouponRepository couponRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    protected JsonMapper jsonMapper;

    private HttpClient client;

    protected abstract boolean locksCartOnCheckout();

    @BeforeEach
    void setUpClient() {
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDownClient() {
        client.close();
    }

    @Test
    @DisplayName("GET /orders/{orderId} возвращает собственный заказ")
    void getOwnOrderReturns200() throws Exception {
        Order order = sampleOrder(CURRENT_USER_ID);
        when(orderRepository.findResponse(ORDER_ID))
                .thenReturn(Optional.of(new OrderResponse(CURRENT_USER_ID, jsonMapper.writeValueAsString(order))));

        HttpResponse<String> response = get("/orders/" + ORDER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(ORDER_ID);
        assertThat(body.get("userId").asLong()).isEqualTo(CURRENT_USER_ID);
        assertThat(body.get("total").asLong()).isEqualTo(1500L);
        assertThat(body.get("currency").asString()).isEqualTo("RUB");
    }

    @Test
    @DisplayName("GET /orders/{orderId} для отсутствующего заказа возвращает 404")
    void getMissingOrderReturns404() throws Exception {
        when(orderRepository.findResponse(ORDER_ID)).thenReturn(Optional.empty());

        HttpResponse<String> response = get("/orders/" + ORDER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /orders/{orderId} с нечисловым id возвращает 400 INVALID_REQUEST")
    void getOrderWithMalformedIdReturns400() throws Exception {
        HttpResponse<String> response = get("/orders/not-a-number", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("GET /orders/{orderId} без токена возвращает 401")
    void getOrderWithoutTokenReturns401() throws Exception {
        HttpResponse<String> response = get("/orders/" + ORDER_ID, null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("POST /orders без токена возвращает 401")
    void checkoutWithoutTokenReturns401() throws Exception {
        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(orderRepository, cartRepository);
    }

    @Test
    @DisplayName("POST /orders без заголовка Idempotency-Key возвращает 400 INVALID_REQUEST")
    void checkoutWithoutIdempotencyKeyReturns400() throws Exception {
        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                null, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(orderRepository, cartRepository);
    }

    @Test
    @DisplayName("POST /orders с Idempotency-Key не по шаблону возвращает 400 INVALID_REQUEST")
    void checkoutWithInvalidIdempotencyKeyReturns400() throws Exception {
        HttpResponse<String> shortKey = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                "short", tokenFor(CURRENT_USER_ID));
        HttpResponse<String> badChars = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                "idem key with spaces!", tokenFor(CURRENT_USER_ID));

        assertThat(shortKey.statusCode()).isEqualTo(400);
        assertThat(badChars.statusCode()).isEqualTo(400);
        assertThat(json(shortKey).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(orderRepository, cartRepository);
    }

    @Test
    @DisplayName("POST /orders с отрицательными cartRevision или quotedTotal возвращает 400 INVALID_REQUEST")
    void checkoutWithNegativeFieldsReturns400() throws Exception {
        HttpResponse<String> negativeRevision = post("/orders", Map.of("cartRevision", -1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));
        HttpResponse<String> negativeTotal = post("/orders", Map.of("cartRevision", 1, "quotedTotal", -1),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(negativeRevision.statusCode()).isEqualTo(400);
        assertThat(negativeTotal.statusCode()).isEqualTo(400);
        verifyNoInteractions(orderRepository, cartRepository);
    }

    @Test
    @DisplayName("POST /orders с пустой корзиной возвращает 409 CONFLICT")
    void checkoutWithEmptyCartReturns409() throws Exception {
        stubCheckoutCart(new CartSnapshot(CART_ID, CURRENT_USER_ID, 0L, T0, List.of(), null));
        when(orderRepository.findByIdempotencyKey(eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 0, "quotedTotal", 0),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CONFLICT");
        verifyNoInteractions(couponRepository);
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

    @Test
    @DisplayName("POST /orders при первом оформлении возвращает 201, Order и Idempotency-Replayed: false")
    void checkoutCreatesOrderWithReplayedFalse() throws Exception {
        CartSnapshot cart = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1500, true)), null);
        stubCheckoutCart(cart);
        when(orderRepository.findByIdempotencyKey(eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());
        when(orderRepository.existsForRevision(CART_ID, 1L)).thenReturn(false);
        when(orderRepository.nextId()).thenReturn(ORDER_ID);

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(ORDER_ID);
        assertThat(body.get("total").asLong()).isEqualTo(1500L);
    }

    protected void stubCheckoutCart(CartSnapshot cart) {
        if (locksCartOnCheckout()) {
            when(cartRepository.lockByUserId(CURRENT_USER_ID)).thenReturn(Optional.of(cart));
        } else {
            when(cartRepository.findByUserId(CURRENT_USER_ID)).thenReturn(Optional.of(cart));
        }
    }

    protected Order sampleOrder(long ownerId) {
        return new Order(
                ORDER_ID,
                ownerId,
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

    protected String tokenFor(long userId) {
        return jwtService.issue(userId);
    }

    protected HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> post(String path, Object body, String idempotencyKey, String token) throws Exception {
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

    protected HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .DELETE();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }
}
