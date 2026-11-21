package lab.shop.vulnerable;

import lab.shop.api.ProductsEndpointTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("vulnerable")
class VulnerableProductsEndpointTest extends ProductsEndpointTest {

    @Test
    @DisplayName("В vulnerable-режиме параметр q длиннее 120 символов проходит до репозитория")
    void queryLongerThan120IsForwardedToRepository() throws Exception {
        String longQuery = "a".repeat(121);
        when(productRepository.search(longQuery)).thenReturn(List.of());

        HttpResponse<String> response = get("/products?q=" + longQuery, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        verify(productRepository).search(longQuery);
    }
}
