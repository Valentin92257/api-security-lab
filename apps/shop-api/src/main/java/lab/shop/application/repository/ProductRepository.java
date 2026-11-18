package lab.shop.application.repository;

import lab.shop.application.model.CatalogProduct;

import java.util.List;

public interface ProductRepository {

    List<CatalogProduct> search(String query);
}
