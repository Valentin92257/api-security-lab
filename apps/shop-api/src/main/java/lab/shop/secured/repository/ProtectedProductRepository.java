package lab.shop.secured.repository;

import lab.shop.application.model.CatalogProduct;
import lab.shop.application.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedProductRepository implements ProductRepository {

    private final JdbcClient jdbc;

    @Override
    public List<CatalogProduct> search(String query) {
        return jdbc.sql("""
                        SELECT id, name, description, price, published
                        FROM products
                        WHERE published = true
                          AND (
                              STRPOS(LOWER(name), LOWER(:query)) > 0
                              OR STRPOS(LOWER(description), LOWER(:query)) > 0
                          )
                        ORDER BY id ASC
                        LIMIT 100
                        """)
                .param("query", query == null ? "" : query)
                .query(CatalogProduct.class)
                .list();
    }
}