package lab.shop.secured.repository;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.application.model.CatalogProduct;
import lab.shop.application.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("protected")
public class ProtectedProductRepositoryIntegrationTest extends PostgreSQLIntegrationTest {

    private static final String INJECTION_PAYLOAD = "')) > 0) OR published = false -- ";

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected JdbcClient jdbc;

    protected long lampId;
    protected long notebookId;
    protected long percentId;
    protected long underscoreId;
    protected long draftId;

    protected boolean shouldRevealDraftWithInjection() {
        return false;
    }

    @BeforeEach
    void setUp() {
        lampId = insertProduct(
                "Amber Lamp",
                "Desk lighting",
                1500,
                true
        );

        notebookId = insertProduct(
                "Notebook",
                "Portable amber notebook",
                500,
                true
        );

        percentId = insertProduct(
                "Price 100% marker",
                "Percent product",
                100,
                true
        );

        underscoreId = insertProduct(
                "Under_score marker",
                "Underscore product",
                200,
                true
        );

        draftId = insertProduct(
                "Draft Catalog Canary",
                "Secret unpublished product",
                999,
                false
        );
    }

    protected long insertProduct(
            String name,
            String description,
            int price,
            boolean published
    ) {
        return jdbc.sql("""
                        INSERT INTO products (name, description, price, published)
                        VALUES (:name, :description, :price, :published)
                        RETURNING id
                        """)
                .param("name", name)
                .param("description", description)
                .param("price", price)
                .param("published", published)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("Пустой запрос возвращает все опубликованные товары")
    void searchWithEmptyQueryReturnsPublishedProducts() {
        List<CatalogProduct> products = productRepository.search("");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .containsExactly(
                        lampId,
                        notebookId,
                        percentId,
                        underscoreId
                );
    }

    @Test
    @DisplayName("Поиск выполняется по названию")
    void searchByName() {
        List<CatalogProduct> products =
                productRepository.search("Amber Lamp");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .containsExactly(lampId);
    }

    @Test
    @DisplayName("Поиск выполняется по описанию")
    void searchByDescription() {
        List<CatalogProduct> products =
                productRepository.search("PORTABLE AMBER");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .containsExactly(notebookId);
    }

    @Test
    @DisplayName("Поиск регистронезависимый")
    void searchIsCaseInsensitive() {
        List<CatalogProduct> products =
                productRepository.search("AMBER");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .containsExactly(lampId, notebookId);
    }

    @Test
    @DisplayName("Процент является обычным символом")
    void percentIsLiteral() {
        List<CatalogProduct> products =
                productRepository.search("%");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .containsExactly(percentId);
    }

    @Test
    @DisplayName("Подчёркивание является обычным символом")
    void underscoreIsLiteral() {
        List<CatalogProduct> products =
                productRepository.search("_");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .containsExactly(underscoreId);
    }

    @Test
    @DisplayName("Результат отсортирован по возрастанию ID")
    void searchResultIsOrderedById() {
        List<CatalogProduct> products =
                productRepository.search("");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .isSorted();
    }

    @Test
    @DisplayName("Обычный поиск не возвращает draft")
    void draftProductIsNotReturnedByNormalSearch() {
        List<CatalogProduct> products =
                productRepository.search("Draft Catalog Canary");

        assertThat(products)
                .extracting(CatalogProduct::id)
                .doesNotContain(draftId);
    }

    @Test
    @DisplayName("При отсутствии совпадений возвращается пустой список")
    void searchReturnsEmptyList() {
        List<CatalogProduct> products =
                productRepository.search("unknown-product-12345");

        assertThat(products).isEmpty();
    }

    @Test
    @DisplayName("SQL Injection: проверка раскрытия draft canary")
    void sqlInjectionBehaviour() {
        List<CatalogProduct> products =
                productRepository.search(INJECTION_PAYLOAD);

        if (shouldRevealDraftWithInjection()) {
            assertThat(products)
                    .extracting(CatalogProduct::id)
                    .contains(draftId);
        } else {
            assertThat(products)
                    .extracting(CatalogProduct::id)
                    .doesNotContain(draftId);
        }
    }

    @Test
    @DisplayName("existsPublished возвращает true только для существующего опубликованного товара")
    void existsPublishedChecksExistenceAndPublishedFlag() {
        assertThat(productRepository.existsPublished(lampId)).isTrue();
        assertThat(productRepository.existsPublished(draftId)).isFalse();
        assertThat(productRepository.existsPublished(999_999L)).isFalse();
    }
}
