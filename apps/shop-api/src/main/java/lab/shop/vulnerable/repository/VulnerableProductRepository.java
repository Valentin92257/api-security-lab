package lab.shop.vulnerable.repository;

import lab.shop.application.model.CatalogProduct;
import lab.shop.application.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableProductRepository implements ProductRepository {

    private final JdbcClient jdbc;

    @Override
    public List<CatalogProduct> search(String query) {
        String normalizedQuery = query == null ? "" : query;
        String sql = """
                SELECT id, name, description, price, published
                FROM products
                WHERE published = true AND 
                      (STRPOS(LOWER(name), LOWER('%s')) > 0 OR STRPOS(LOWER(description), LOWER('%s')) > 0)
                ORDER BY id ASC
                LIMIT 100
                """.formatted(normalizedQuery, normalizedQuery);

        // Намеренная SQL Injection vulnerability.
        return jdbc.sql(sql)
                .query(CatalogProduct.class)
                .list();
    }

    @Override
    public boolean existsPublished(long id) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM products
                    WHERE id = %s
                      AND published = true
                )
                """.formatted(id);

        return jdbc.sql(sql)
                .query(Boolean.class)
                .single();
    }
}