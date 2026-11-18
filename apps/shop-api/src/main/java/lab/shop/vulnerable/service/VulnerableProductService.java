package lab.shop.vulnerable.service;

import lab.contracts.shop.model.Product;
import lab.shop.application.model.CatalogProduct;
import lab.shop.application.repository.ProductRepository;
import lab.shop.application.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableProductService implements ProductService {

    private final ProductRepository productRepository;

    @Override
    public List<Product> searchProducts(String query) {
        return productRepository.search(query).stream()
                .map(CatalogProduct::toDto)
                .toList();
    }
}