package lab.shop.secured;

import lab.shop.api.ProductsEndpointTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ActiveProfiles("protected")
class ProtectedProductsEndpointTest extends ProductsEndpointTest {

    @Test
    @DisplayName("Параметр q длиннее 120 символов возвращает 400 INVALID_REQUEST")
    void queryLongerThan120ReturnsBadRequest() throws Exception {
        String longQuery = "a".repeat(121);

        HttpResponse<String> response = get("/products?q=" + longQuery, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(productRepository);
    }
}
