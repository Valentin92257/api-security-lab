package lab.shop.secured;

import lab.contracts.shop.model.Order;
import lab.shop.api.OrdersEndpointTest;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import lab.shop.application.model.OrderResponse;
import lab.shop.application.model.StoredOrder;
import lab.shop.application.service.CheckoutCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ActiveProfiles("protected")
class ProtectedOrdersEndpointTest extends OrdersEndpointTest {

    @Autowired
    private CheckoutCrypto checkoutCrypto;

    @Override
    protected boolean locksCartOnCheckout() {
        return true;
    }

    @Test
    @DisplayName("BO-01: чужой заказ возвращает 404 без раскрытия данных")
    void foreignOrderReturns404() throws Exception {
        Order foreign = sampleOrder(OTHER_USER_ID);
        when(orderRepository.findResponse(ORDER_ID))
                .thenReturn(Optional.of(new OrderResponse(OTHER_USER_ID, jsonMapper.writeValueAsString(foreign))));

        HttpResponse<String> response = get("/orders/" + ORDER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("Лампа");
    }

    @Test
    @DisplayName("RP-01: повтор того же Idempotency-Key и тела возвращает 201 и Idempotency-Replayed: true")
    void replayReturnsCachedOrderWithHeaderTrue() throws Exception {
        Order cached = sampleOrder(CURRENT_USER_ID);
        byte[] keyHash = checkoutCrypto.idempotencyKeyHash(IDEMPOTENCY_KEY);
        byte[] fingerprint = checkoutCrypto.requestFingerprint(1L, 1500L);
        when(orderRepository.findByIdempotencyKey(CURRENT_USER_ID, keyHash))
                .thenReturn(Optional.of(new StoredOrder(ORDER_ID, fingerprint, jsonMapper.writeValueAsString(cached))));

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(json(response).get("id").asLong()).isEqualTo(ORDER_ID);
        verifyNoInteractions(cartRepository, couponRepository);
    }

    @Test
    @DisplayName("RP-02: повтор того же Idempotency-Key с другим телом возвращает 409 CONFLICT")
    void replayWithDifferentBodyReturns409() throws Exception {
        byte[] keyHash = checkoutCrypto.idempotencyKeyHash(IDEMPOTENCY_KEY);
        byte[] otherFingerprint = checkoutCrypto.requestFingerprint(1L, 1000L);
        when(orderRepository.findByIdempotencyKey(CURRENT_USER_ID, keyHash))
                .thenReturn(Optional.of(new StoredOrder(ORDER_ID, otherFingerprint, "{}")));

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("BL-04: заниженный quotedTotal отклоняется с 409 CONFLICT")
    void tamperedQuotedTotalReturns409() throws Exception {
        CartSnapshot cart = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1500, true)), null);
        when(orderRepository.findByIdempotencyKey(eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());
        stubCheckoutCart(cart);
        when(orderRepository.existsForRevision(CART_ID, 1L)).thenReturn(false);

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("RP-02: новый key с уже оформленной cartRevision возвращает 409 CONFLICT")
    void usedCartRevisionReturns409() throws Exception {
        CartSnapshot cart = new CartSnapshot(CART_ID, CURRENT_USER_ID, 2L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1500, true)), null);
        when(orderRepository.findByIdempotencyKey(eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());
        stubCheckoutCart(cart);
        when(orderRepository.existsForRevision(CART_ID, 2L)).thenReturn(true);

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 2, "quotedTotal", 1500),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CONFLICT");
        verifyNoInteractions(couponRepository);
    }

    @Test
    @DisplayName("BL-01: истёкший купон отклоняется на checkout с 409 CONFLICT без списания")
    void expiredCouponReturns409() throws Exception {
        Coupon expired = new Coupon(7L, "OLD10", 10, T0, 5, 0);
        CartSnapshot cart = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1500, true)), expired);
        when(orderRepository.findByIdempotencyKey(eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());
        stubCheckoutCart(cart);
        when(orderRepository.existsForRevision(CART_ID, 1L)).thenReturn(false);

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1350),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CONFLICT");
        verifyNoInteractions(couponRepository);
    }
}
