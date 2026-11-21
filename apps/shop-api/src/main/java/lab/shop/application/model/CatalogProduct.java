package lab.shop.application.model;

import lab.contracts.shop.model.Product;

public record CatalogProduct(Long id, String name, String description, Integer price, Boolean published) {

    public Product toDto() {
        return new Product(id, name, description, price, Product.CurrencyEnum.RUB);
    }
}
