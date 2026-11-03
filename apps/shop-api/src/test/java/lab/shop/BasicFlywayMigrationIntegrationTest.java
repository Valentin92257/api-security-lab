package lab.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class BasicFlywayMigrationIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    protected JdbcClient jdbcClient;

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

    @Test
    @DisplayName("Все миграции применены успешно")
    void allMigrationsSucceeded() {
        Integer failed = jdbcClient.sql(
                        "SELECT count(*) FROM flyway_schema_history WHERE success = false")
                .query(Integer.class)
                .single();
        assertThat(failed)
                .as("Не должно быть неуспешных миграций")
                .isZero();
    }
}
