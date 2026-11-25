package lab.shop.api;

import lab.contracts.shop.model.*;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.JwtService;
import lab.shop.application.service.CartService;
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
class CartEndpointTest {

    private static final long CURRENT_USER_ID = 1L;
    private static final long CART_ID = 10L;
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    private CartService cartService;

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
    @DisplayName("GET /cart возвращает 200 и сериализованную корзину")
    void getCartReturnsCart() throws Exception {
        Cart empty = new Cart(CART_ID, CURRENT_USER_ID, 0L, List.of(), null,
                0L, 0L, 0L, Cart.CurrencyEnum.RUB, T0.atOffset(ZoneOffset.UTC));
        when(cartService.getCart()).thenReturn(empty);

        HttpResponse<String> response = get("/cart", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(CART_ID);
        assertThat(body.get("userId").asLong()).isEqualTo(CURRENT_USER_ID);
        assertThat(body.get("revision").asLong()).isZero();
        assertThat(body.get("items").isArray()).isTrue();
        assertThat(body.get("coupon").isNull()).isTrue();
        assertThat(body.get("currency").asString()).isEqualTo("RUB");
    }

    @Test
    @DisplayName("GET /cart без токена возвращает 401")
    void getCartWithoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = get("/cart", null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(cartService);
    }

    @Test
    @DisplayName("GET /cart при NotFoundException возвращает 404 NOT_FOUND")
    void getCartWhenMissingReturns404() throws Exception {
        when(cartService.getCart()).thenThrow(new NotFoundException("Корзина не найдена"));

        HttpResponse<String> response = get("/cart", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} передаёт productId и quantity, возвращает обновлённую корзину")
    void setCartQuantityUpdatesCart() throws Exception {
        Cart updated = new Cart(CART_ID, CURRENT_USER_ID, 1L,
                List.of(new LineItem(5L, "Лампа", 2, 1500, 3000L)),
                null, 3000L, 0L, 3000L, Cart.CurrencyEnum.RUB, T0.atOffset(ZoneOffset.UTC));
        when(cartService.setCartQuantity(eq(5L), eq(new SetQuantityRequest(2)))).thenReturn(updated);

        HttpResponse<String> response = put("/cart/items/5", Map.of("quantity", 2), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("revision").asLong()).isEqualTo(1L);
        assertThat(body.get("subtotal").asLong()).isEqualTo(3000L);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} с BadRequestException возвращает 400 INVALID_REQUEST")
    void setCartQuantityBadRequestReturns400() throws Exception {
        when(cartService.setCartQuantity(eq(5L), any())).thenThrow(new BadRequestException("неверный quantity"));

        HttpResponse<String> response = put("/cart/items/5", Map.of(), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} с нечисловым id возвращает 400 до вызова сервиса")
    void setCartQuantityMalformedIdReturns400() throws Exception {
        HttpResponse<String> response = put("/cart/items/abc", Map.of("quantity", 1), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(cartService);
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} при NotFoundException возвращает 404")
    void setCartQuantityMissingProductReturns404() throws Exception {
        when(cartService.setCartQuantity(eq(99L), any())).thenThrow(new NotFoundException("Товар не найден"));

        HttpResponse<String> response = put("/cart/items/99", Map.of("quantity", 1), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} без токена возвращает 401")
    void setCartQuantityWithoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = put("/cart/items/5", Map.of("quantity", 1), null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(cartService);
    }

    @Test
    @DisplayName("PUT /cart/coupon возвращает 200 и корзину с купоном")
    void selectCartCouponUpdatesCart() throws Exception {
        Cart updated = new Cart(CART_ID, CURRENT_USER_ID, 2L,
                List.of(new LineItem(5L, "Лампа", 1, 1000, 1000L)),
                new CartCoupon("SAVE10", 10, CartCoupon.EligibilityEnum.VALID),
                1000L, 100L, 900L, Cart.CurrencyEnum.RUB, T0.atOffset(ZoneOffset.UTC));
        when(cartService.selectCoupon(eq(new SelectCouponRequest(" save10 ")))).thenReturn(updated);

        HttpResponse<String> response = put("/cart/coupon", Map.of("code", " save10 "), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("revision").asLong()).isEqualTo(2L);
        assertThat(body.get("coupon").get("code").asString()).isEqualTo("SAVE10");
        assertThat(body.get("coupon").get("eligibility").asString()).isEqualTo("VALID");
        assertThat(body.get("discount").asLong()).isEqualTo(100L);
        assertThat(body.get("total").asLong()).isEqualTo(900L);
    }

    @Test
    @DisplayName("PUT /cart/coupon без токена возвращает 401")
    void selectCartCouponWithoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = put("/cart/coupon", Map.of("code", "SAVE10"), null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(cartService);
    }

    @Test
    @DisplayName("PUT /cart/coupon при NotFoundException возвращает 404")
    void selectUnknownCouponReturns404() throws Exception {
        when(cartService.selectCoupon(any())).thenThrow(new NotFoundException("Купон не найден"));

        HttpResponse<String> response = put("/cart/coupon", Map.of("code", "NOPE"), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
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

    private HttpResponse<String> put(String path, Object body, String token) throws Exception {
        String json = jsonMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }
}
