package lab.control.fixture;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.EnumMap;
import java.util.Map;

/**
 * Ленивый держатель JDBC-подключений к целевым БД магазина ({@code vulnerable} и {@code protected}).
 * Намеренно не регистрирует {@link DataSource} как Spring-бины, чтобы не влиять на основной
 * {@code DataSource} ({@code lab_control}), Flyway и Actuator {@code /health}.
 */
@Component
@EnableConfigurationProperties(ShopFixtureProperties.class)
public class ShopFixtureConnections implements AutoCloseable {
    private final ShopFixtureProperties properties;
    private final Map<ShopTarget, TargetConnection> connections = new EnumMap<>(ShopTarget.class);
    private boolean closed;

    @Autowired
    public ShopFixtureConnections(ShopFixtureProperties properties) {
        this.properties = properties;
    }

    ShopFixtureConnections(Map<ShopTarget, TargetConnection> preconfigured) {
        this.properties = null;
        this.connections.putAll(preconfigured);
    }

    public synchronized TargetConnection forTarget(ShopTarget target) {
        if (closed) {
            throw new IllegalStateException("ShopFixtureConnections is already closed");
        }
        return connections.computeIfAbsent(target, this::openConnection);
    }

    private TargetConnection openConnection(ShopTarget target) {
        if (properties == null) {
            throw new IllegalStateException("No connection configured for target: " + target);
        }
        String url = properties.urlFor(target);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Fixture database URL is not configured for " + target);
        }
        if (properties.username() == null || properties.username().isBlank()) {
            throw new IllegalStateException("Fixture database username is not configured");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("fixture-" + target.name().toLowerCase());
        config.setJdbcUrl(url);
        config.setUsername(properties.username());
        config.setPassword(properties.password() == null ? "" : properties.password());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        HikariDataSource dataSource = new HikariDataSource(config);
        return TargetConnection.from(dataSource);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (TargetConnection connection : connections.values()) {
            connection.closeQuietly();
        }
        connections.clear();
    }

    public record TargetConnection(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            AutoCloseable closeable
    ) {
        public static TargetConnection from(DataSource dataSource) {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            AutoCloseable closeable = dataSource instanceof AutoCloseable c ? c : null;
            return new TargetConnection(jdbc, transactions, closeable);
        }

        void closeQuietly() {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception _) {
                }
            }
        }
    }
}
