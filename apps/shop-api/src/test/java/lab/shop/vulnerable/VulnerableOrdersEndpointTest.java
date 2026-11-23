package lab.shop.vulnerable;

import lab.contracts.shop.model.Order;
import lab.shop.api.OrdersEndpointTest;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import lab.shop.application.model.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("vulnerable")
class VulnerableOrdersEndpointTest extends OrdersEndpointTest {

    @Override
    protected boolean locksCartOnCheckout() {
        return false;
    }

    @Test
    @DisplayName("BO-01: в vulnerable-режиме чужой заказ раскрывается с 200")
    void foreignOrderIsReturnedInVulnerableMode() throws Exception {
        Order foreign = sampleOrder(OTHER_USER_ID);
        when(orderRepository.findResponse(ORDER_ID))
                .thenReturn(Optional.of(new OrderResponse(OTHER_USER_ID, jsonMapper.writeValueAsString(foreign))));

        HttpResponse<String> response = get("/orders/" + ORDER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("userId").asLong()).isEqualTo(OTHER_USER_ID);
    }

    @Test
    @DisplayName("BL-04 / RP-01: в vulnerable-режиме заниженный quotedTotal принимается без блокировок и без очистки корзины")
    void tamperedQuotedTotalIsAcceptedInVulnerableMode() throws Exception {
        CartSnapshot cart = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1500, true)), null);
        stubCheckoutCart(cart);
        when(orderRepository.nextId()).thenReturn(ORDER_ID);

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(json(response).get("total").asLong()).isEqualTo(1L);
        verify(orderRepository, never()).lockIdempotencyKey(anyLong(), any());
        verify(orderRepository, never()).findByIdempotencyKey(anyLong(), any());
        verify(cartRepository, never()).clear(anyLong(), any());
    }

    @Test
    @DisplayName("BL-01: в vulnerable-режиме истёкший купон применяется и заказ создаётся")
    void expiredCouponIsAppliedInVulnerableMode() throws Exception {
        Coupon expired = new Coupon(7L, "OLD10", 10, T0, 5, 0);
        CartSnapshot cart = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1500, true)), expired);
        stubCheckoutCart(cart);
        when(couponRepository.tryConsume(7L, T0)).thenReturn(1);
        when(orderRepository.nextId()).thenReturn(ORDER_ID);

        HttpResponse<String> response = post("/orders", Map.of("cartRevision", 1, "quotedTotal", 1350),
                IDEMPOTENCY_KEY, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).get("discount").asLong()).isEqualTo(150L);
        assertThat(json(response).get("total").asLong()).isEqualTo(1350L);
    }
}
