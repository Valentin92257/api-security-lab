package lab.shop.secured.service;

import lab.contracts.shop.model.Product;
import lab.shop.api.exception.BadRequestException;
import lab.shop.application.model.CatalogProduct;
import lab.shop.application.repository.ProductRepository;
import lab.shop.application.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedProductService implements ProductService {

    private final ProductRepository productRepository;

    @Override
    public List<Product> searchProducts(String query) {
        if (query != null && query.length() > 120) {
            throw new BadRequestException("query должен быть короче 120 символов");
        }

        return productRepository.search(query).stream()
                .map(CatalogProduct::toDto)
                .toList();
    }
}