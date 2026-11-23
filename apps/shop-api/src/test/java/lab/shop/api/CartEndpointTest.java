package lab.shop.api;

import lab.shop.api.security.JwtService;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CartEndpointTest.NoOpTransactionManagerConfig.class)
public abstract class CartEndpointTest {

    protected static final long CURRENT_USER_ID = 1L;
    protected static final long CART_ID = 10L;
    protected static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    protected CartRepository cartRepository;

    @MockitoBean
    protected ProductRepository productRepository;

    @MockitoBean
    protected CouponRepository couponRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JsonMapper jsonMapper;

    private HttpClient client;

    protected abstract boolean locksCartOnMutations();

    @BeforeEach
    void setUpClient() {
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDownClient() {
        client.close();
    }

    @Test
    @DisplayName("GET /cart возвращает корзину текущего пользователя")
    void getCartReturnsCart() throws Exception {
        CartSnapshot snapshot = new CartSnapshot(CART_ID, CURRENT_USER_ID, 0L, T0, List.of(), null);
        when(cartRepository.findByUserId(CURRENT_USER_ID)).thenReturn(Optional.of(snapshot));

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
        verifyNoInteractions(cartRepository);
    }

    @Test
    @DisplayName("GET /cart при отсутствии корзины возвращает 404")
    void getCartWhenMissingReturns404() throws Exception {
        when(cartRepository.findByUserId(CURRENT_USER_ID)).thenReturn(Optional.empty());

        HttpResponse<String> response = get("/cart", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} устанавливает количество и возвращает обновлённую корзину")
    void setCartQuantityUpdatesCart() throws Exception {
        CartSnapshot before = new CartSnapshot(CART_ID, CURRENT_USER_ID, 0L, T0, List.of(), null);
        CartSnapshot after = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 2, 1500, true)), null);
        when(productRepository.existsPublished(5L)).thenReturn(true);
        stubCartMutationRead(before, after);
        when(cartRepository.setItemQuantity(CART_ID, 5L, 2)).thenReturn(true);

        HttpResponse<String> response = put("/cart/items/5", Map.of("quantity", 2), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("revision").asLong()).isEqualTo(1L);
        assertThat(body.get("subtotal").asLong()).isEqualTo(3000L);
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        verify(cartRepository).bumpRevision(eq(CART_ID), any(Instant.class));
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} для неизвестного или draft товара возвращает 404")
    void setCartQuantityForMissingProductReturns404() throws Exception {
        when(productRepository.existsPublished(99L)).thenReturn(false);

        HttpResponse<String> response = put("/cart/items/99", Map.of("quantity", 1), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        verifyNoInteractions(cartRepository);
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} без quantity возвращает 400 INVALID_REQUEST")
    void setCartQuantityWithoutQuantityReturns400() throws Exception {
        HttpResponse<String> response = put("/cart/items/5", Map.of(), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(cartRepository, productRepository);
    }

    @Test
    @DisplayName("PUT /cart/items/{productId} без токена возвращает 401")
    void setCartQuantityWithoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = put("/cart/items/5", Map.of("quantity", 1), null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(cartRepository, productRepository);
    }

    @Test
    @DisplayName("PUT /cart/coupon без токена возвращает 401")
    void selectCartCouponWithoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = put("/cart/coupon", Map.of("code", "SAVE10"), null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(cartRepository, couponRepository);
    }

    @Test
    @DisplayName("PUT /cart/coupon для неизвестного кода возвращает 404")
    void selectUnknownCouponReturns404() throws Exception {
        when(couponRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        HttpResponse<String> response = put("/cart/coupon", Map.of("code", "NOPE"), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    protected void stubCartMutationRead(CartSnapshot before, CartSnapshot after) {
        if (locksCartOnMutations()) {
            when(cartRepository.lockByUserId(CURRENT_USER_ID)).thenReturn(Optional.of(before));
            when(cartRepository.findByUserId(CURRENT_USER_ID)).thenReturn(Optional.of(after));
        } else {
            when(cartRepository.findByUserId(CURRENT_USER_ID)).thenReturn(Optional.of(before), Optional.of(after));
        }
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

    protected HttpResponse<String> put(String path, Object body, String token) throws Exception {
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

    protected JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoOpTransactionManagerConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
        }
    }
}
