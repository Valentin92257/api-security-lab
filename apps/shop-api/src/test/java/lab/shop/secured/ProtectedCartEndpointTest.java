package lab.shop.secured;

import lab.shop.api.CartEndpointTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
