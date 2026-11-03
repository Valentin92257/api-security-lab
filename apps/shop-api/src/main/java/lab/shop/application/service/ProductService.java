package lab.shop.application.service;

import lab.contracts.shop.model.Product;

import java.util.List;

public interface ProductService {

    List<Product> searchProducts(String query);
}
