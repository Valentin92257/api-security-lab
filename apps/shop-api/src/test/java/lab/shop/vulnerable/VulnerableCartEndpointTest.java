package lab.shop.vulnerable;

import lab.shop.api.CartEndpointTest;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ActiveProfiles("vulnerable")
class VulnerableCartEndpointTest extends CartEndpointTest {

    @Override
    protected boolean locksCartOnMutations() {
        return false;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100})
    @DisplayName("BL-02: в vulnerable-режиме quantity = 0, отрицательное и > 99 принимаются без блокировки")
    void invalidQuantityIsAcceptedInVulnerableMode(int quantity) throws Exception {
        CartSnapshot before = new CartSnapshot(CART_ID, CURRENT_USER_ID, 0L, T0, List.of(), null);
        CartSnapshot after = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", quantity, 1000, true)), null);
        when(productRepository.existsPublished(5L)).thenReturn(true);
        stubCartMutationRead(before, after);
        when(cartRepository.setItemQuantity(CART_ID, 5L, quantity)).thenReturn(true);

        HttpResponse<String> response = put("/cart/items/5", Map.of("quantity", quantity), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("items").get(0).get("quantity").asInt()).isEqualTo(quantity);
        verify(cartRepository, never()).lockByUserId(anyLong());
    }

    @Test
    @DisplayName("PUT /cart/coupon не нормализует код купона")
    void selectCartCouponUpdatesCart() throws Exception {
        Coupon coupon = new Coupon(7L, "SAVE10", 10, Instant.now().plusSeconds(3600), 5, 0);
        CartSnapshot before = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1000, true)), null);
        CartSnapshot after = new CartSnapshot(CART_ID, CURRENT_USER_ID, 2L, T0, before.items(), coupon);

        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));
        stubCartMutationRead(before, after);

        HttpResponse<String> response = put("/cart/coupon", Map.of("code", " save10 "), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
