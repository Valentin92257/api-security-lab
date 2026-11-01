package lab.shop.secured;

import lab.shop.PostgreSQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("protected")
class ProtectedFlywayMigrationIT extends PostgreSQLIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @ParameterizedTest(name = "Таблица {0}")
    @ValueSource(strings = {"users", "products", "carts", "cart_items", "orders", "order_items", "coupons"})
    @DisplayName("Присутствуют таблицы базовой миграции")
    void shouldContainBaseTables(String tableName) {
        Integer count = jdbcClient.sql(
                        "SELECT count(*) FROM information_schema.tables " +
                                "WHERE table_schema = 'public' AND table_name = ?"
                )
                .param(tableName)
                .query(Integer.class)
                .single();

        assertThat(count)
                .as("Таблица '%s' должна присутствовать в базовой миграции", tableName)
                .isOne();
    }

    @ParameterizedTest(name = "Констрейнт {0}")
    @ValueSource(strings = {
            "cart_items_protected_1",
            "order_items_protected_1",
            "order_items_protected_2",
            "coupons_protected_1",
            "orders_protected_1",
            "orders_protected_2",
            "orders_protected_3",
            "orders_protected_4",
            "orders_cart_revision_once",
            "orders_idempotency_scope_once"
    })
    @DisplayName("В защищенном профиле присутствуют защитные констрейнты")
    void shouldContainProtectedConstraints(String constraintName) {
        Integer count = jdbcClient.sql(
                        "SELECT count(*) FROM information_schema.table_constraints " +
                                "WHERE table_schema = 'public' AND constraint_name = ?"
                )
                .param(constraintName)
                .query(Integer.class)
                .single();

        assertThat(count)
                .as("Защитный констрейнт '%s' должен отсутствовать в уязвимом профиле", constraintName)
                .isOne();
    }
}