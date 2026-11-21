package lab.shop.secured.service;

import lab.contracts.shop.model.Product;
import lab.shop.api.exception.BadRequestException;
import lab.shop.application.model.CatalogProduct;
import lab.shop.application.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProtectedProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProtectedProductService service;

    @BeforeEach
    void setUp() {
        service = new ProtectedProductService(productRepository);
    }

    @Test
    @DisplayName("searchProducts возвращает товары с валютой RUB")
    void searchProductsMapsCatalogProductsToDto() {
        when(productRepository.search("lamp")).thenReturn(List.of(
                new CatalogProduct(1L, "Amber Lamp", "Desk lighting", 1500, true),
                new CatalogProduct(2L, "Night Lamp", "Bedside", 900, true)));

        List<Product> result = service.searchProducts("lamp");

        assertThat(result).containsExactly(
                new Product(1L, "Amber Lamp", "Desk lighting", 1500, Product.CurrencyEnum.RUB),
                new Product(2L, "Night Lamp", "Bedside", 900, Product.CurrencyEnum.RUB));
    }

    @Test
    @DisplayName("Запрос длиной ровно 120 символов допускается")
    void searchProductsAllowsMaxLengthQuery() {
        String query = "a".repeat(120);
        when(productRepository.search(query)).thenReturn(List.of());

        assertThat(service.searchProducts(query)).isEmpty();
        verify(productRepository).search(query);
    }

    @Test
    @DisplayName("Запрос длиннее 120 символов отклоняется до обращения к репозиторию")
    void searchProductsRejectsTooLongQuery() {
        String query = "a".repeat(121);

        assertThatThrownBy(() -> service.searchProducts(query))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(productRepository);
    }
}
