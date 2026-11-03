package lab.shop.secured.service;

import lab.contracts.shop.model.Product;
import lab.shop.application.service.ProductService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Profile("protected")
public class ProtectedProductService implements ProductService {

    @Override
    public List<Product> searchProducts(String query) {
        return List.of();
    }
}