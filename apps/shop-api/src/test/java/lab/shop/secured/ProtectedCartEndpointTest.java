package lab.shop.secured;

import lab.shop.api.CartEndpointTest;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ActiveProfiles("protected")
class ProtectedCartEndpointTest extends CartEndpointTest {

    @Override
    protected boolean locksCartOnMutations() {
        return true;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100})
    @DisplayName("quantity вне 1..99 возвращает 400 INVALID_REQUEST без записи в корзину")
    void invalidQuantityReturns400(int quantity) throws Exception {
        HttpResponse<String> response = put("/cart/items/5", Map.of("quantity", quantity), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(cartRepository, productRepository);
    }

    @Test
    @DisplayName("PUT /cart/coupon с невалидным форматом кода возвращает 400 INVALID_REQUEST")
    void invalidCouponCodeReturns400() throws Exception {
        HttpResponse<String> response = put("/cart/coupon", Map.of("code", "BAD CODE!"), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(cartRepository, couponRepository);
    }

    @Test
    @DisplayName("PUT /cart/coupon выбирает купон с нормализацией кода")
    void selectCartCouponUpdatesCart() throws Exception {
        Coupon coupon = new Coupon(7L, "SAVE10", 10, Instant.now().plusSeconds(3600), 5, 0);
        CartSnapshot before = new CartSnapshot(CART_ID, CURRENT_USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 1, 1000, true)), null);
        CartSnapshot after = new CartSnapshot(CART_ID, CURRENT_USER_ID, 2L, T0, before.items(), coupon);

        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));
        stubCartMutationRead(before, after);

        HttpResponse<String> response = put("/cart/coupon", Map.of("code", " save10 "), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("revision").asLong()).isEqualTo(2L);
        assertThat(body.get("coupon").get("code").asString()).isEqualTo("SAVE10");
        assertThat(body.get("coupon").get("eligibility").asString()).isEqualTo("VALID");
        assertThat(body.get("discount").asLong()).isEqualTo(100L);
        assertThat(body.get("total").asLong()).isEqualTo(900L);
    }
}
