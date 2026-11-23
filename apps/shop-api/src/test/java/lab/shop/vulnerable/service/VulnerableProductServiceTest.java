package lab.shop.vulnerable.service;

import lab.contracts.shop.model.Product;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VulnerableProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private VulnerableProductService service;

    @BeforeEach
    void setUp() {
        service = new VulnerableProductService(productRepository);
    }

    @Test
    @DisplayName("searchProducts возвращает товары с валютой RUB")
    void searchProductsMapsCatalogProductsToDto() {
        when(productRepository.search("lamp")).thenReturn(List.of(
                new CatalogProduct(1L, "Amber Lamp", "Desk lighting", 1500, true)));

        List<Product> result = service.searchProducts("lamp");

        assertThat(result).containsExactly(
                new Product(1L, "Amber Lamp", "Desk lighting", 1500, Product.CurrencyEnum.RUB));
    }

    @Test
    @DisplayName("В vulnerable-режиме запрос длиннее 120 символов не блокируется")
    void searchProductsDoesNotRejectLongQuery() {
        String query = "a".repeat(121);
        when(productRepository.search(query)).thenReturn(List.of());

        assertThat(service.searchProducts(query)).isEmpty();
        verify(productRepository).search(query);
    }
}
