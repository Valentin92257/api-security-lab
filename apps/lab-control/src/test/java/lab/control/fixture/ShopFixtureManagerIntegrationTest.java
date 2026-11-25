package lab.control.fixture;

import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.model.CredentialPair;
import lab.control.fixture.model.FixtureManifest;
import lab.control.fixture.model.FixtureUser;
import lab.control.fixture.model.InvariantViolation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ShopFixtureManagerIntegrationTest {
    private static final String FIXTURE_PASSWORD = "fixture-admin-test-pw";
    private static final Instant BASE_TIME = Instant.parse("2026-10-25T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("lab_control")
            .withUsername("lab_admin")
            .withPassword("postgres-test")
            .withEnv("LAB_FIXTURE_DB_PASSWORD", FIXTURE_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("db/shop-init/bootstrap.sql"),
                    "/docker-entrypoint-initdb.d/20-databases.sql")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("db/shop-init/fixture-roles.sql"),
                    "/docker-entrypoint-initdb.d/25-shop-fixture-roles.sql");

    private static ShopFixtureConnections connections;
    private static ShopFixtureManager manager;

    @BeforeAll
    static void migrateShopDatabases() {
        String vulnerableUrl = databaseUrl("vulnerable");
        String protectedUrl = databaseUrl("protected");

        Flyway.configure()
                .dataSource(vulnerableUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/shop-migration/vulnerable")
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(protectedUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/shop-migration/vulnerable", "classpath:db/shop-migration/protected")
                .load()
                .migrate();

        ShopFixtureProperties properties = new ShopFixtureProperties("fixture_admin", FIXTURE_PASSWORD, vulnerableUrl, protectedUrl);
        ContractJsonCodec codec = new ContractJsonCodec();
        connections = new ShopFixtureConnections(properties);
        manager = new ShopFixtureManager(new FixtureGenerator(codec), connections, codec);
    }

    @AfterAll
    static void closeConnections() {
        if (connections != null) {
            connections.close();
        }
    }

    @Test
    void loadsFullFixtureV1IntoBothDatabasesAndPassesAllInvariants() {
        FixtureManifest manifest = manager.generate(42, BASE_TIME);
        PreparedFixture prepared = manager.prepare(manifest);

        manager.load(ShopTarget.VULNERABLE, prepared);
        manager.load(ShopTarget.PROTECTED, prepared);

        var encoder = new BCryptPasswordEncoder();
        for (ShopTarget target : ShopTarget.values()) {
            assertThat(manager.verifyInvariants(target, manifest.oracleBaseline())).isEmpty();

            JdbcClient jdbc = connections.forTarget(target).jdbc();
            assertThat(count(jdbc, "users")).isEqualTo(24);
            assertThat(count(jdbc, "products")).isEqualTo(13);
            assertThat(count(jdbc, "coupons")).isEqualTo(5);
            assertThat(count(jdbc, "carts")).isEqualTo(24);
            assertThat(count(jdbc, "orders")).isEqualTo(6);
            assertThat(jdbc.sql("SELECT count(*) FROM products WHERE published = false").query(Long.class).single())
                    .isEqualTo(1);

            for (FixtureUser victim : manifest.credentialStuffing().victims()) {
                String storedHash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
                        .param("id", victim.id()).query(String.class).single();
                assertThat(encoder.matches(victim.password(), storedHash)).isTrue();
            }
            CredentialPair invalidPair = manifest.credentialStuffing().attempts().stream()
                    .filter(pair -> !pair.valid()).findFirst().orElseThrow();
            String targetHash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
                    .param("id", invalidPair.userId()).query(String.class).single();
            assertThat(encoder.matches(invalidPair.password(), targetHash)).isFalse();
        }
    }

    @Test
    void resetsDatabaseCleanlyAndSynchronizesIdentitySequences() {
        manager.load(ShopTarget.PROTECTED, manager.generate(42, BASE_TIME));
        FixtureManifest next = manager.generate(99, BASE_TIME);
        manager.load(ShopTarget.PROTECTED, next);

        assertThat(manager.verifyInvariants(ShopTarget.PROTECTED, next.oracleBaseline())).isEmpty();

        JdbcClient jdbc = connections.forTarget(ShopTarget.PROTECTED).jdbc();
        long generatedUserId = jdbc.sql("""
                INSERT INTO users (login, name, email, password_hash)
                VALUES ('new_registered_user', 'New User', 'new_registered_user@example.com', 'dummy-hash')
                RETURNING id
                """).query(Long.class).single();
        assertThat(generatedUserId).isEqualTo(25L);
    }

    @Test
    void detectsCatalogTamperingThroughOracleInvariants() {
        FixtureManifest manifest = manager.generate(42, BASE_TIME);
        manager.load(ShopTarget.VULNERABLE, manifest);

        JdbcClient jdbc = connections.forTarget(ShopTarget.VULNERABLE).jdbc();
        jdbc.sql("UPDATE products SET price = price + 1 WHERE id = 1").update();

        List<InvariantViolation> violations = manager.verifyInvariants(ShopTarget.VULNERABLE, manifest.oracleBaseline());
        assertThat(violations)
                .extracting(InvariantViolation::invariantId)
                .contains("ORACLE-FIXTURE-CHANGED");
    }

    @Test
    void restrictsFixtureAdminRoleFromDdlAndOtherDatabases() {
        JdbcClient jdbc = connections.forTarget(ShopTarget.VULNERABLE).jdbc();

        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("fixture_admin");
        assertThat(jdbc.sql("SELECT has_schema_privilege('fixture_admin', 'public', 'CREATE')")
                .query(Boolean.class).single()).isFalse();
        assertThatThrownBy(() -> jdbc.sql("CREATE TABLE forbidden_ddl_test (id bigint)").update())
                .isInstanceOf(DataAccessException.class);
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String databaseUrl(String databaseName) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + databaseName;
    }
}
