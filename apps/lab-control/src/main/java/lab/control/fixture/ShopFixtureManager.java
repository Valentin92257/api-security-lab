package lab.control.fixture;

import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Административная загрузка, сброс фикстур и проверка бизнес-инвариантов
 * в БД {@code vulnerable} и {@code protected} под ролью {@code fixture_admin}.
 */
@Component
public class ShopFixtureManager {
    private static final List<String> IDENTITY_TABLES = List.of("users", "products", "coupons", "carts", "orders");

    private final FixtureGenerator generator;
    private final ShopFixtureConnections connections;
    private final JsonMapper json;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public ShopFixtureManager(FixtureGenerator generator, ShopFixtureConnections connections, ContractJsonCodec codec) {
        this(generator, connections, codec, new BCryptPasswordEncoder());
    }

    ShopFixtureManager(
            FixtureGenerator generator,
            ShopFixtureConnections connections,
            ContractJsonCodec codec,
            PasswordEncoder passwordEncoder
    ) {
        this.generator = generator;
        this.connections = connections;
        this.json = codec.jsonMapper();
        this.passwordEncoder = passwordEncoder;
    }

    public FixtureManifest generate(int seed, Instant businessTimeBase) {
        return generator.generate(seed, businessTimeBase);
    }

    public PreparedFixture prepare(FixtureManifest manifest) {
        Map<Long, String> hashes = manifest.users().parallelStream()
                .collect(Collectors.toConcurrentMap(
                        FixtureUser::id,
                        user -> passwordEncoder.encode(user.password())
                ));
        return new PreparedFixture(manifest, hashes);
    }

    public void load(ShopTarget target, FixtureManifest manifest) {
        load(target, prepare(manifest));
    }

    public void load(ShopTarget target, PreparedFixture prepared) {
        FixtureManifest manifest = prepared.manifest();
        String baselineJson = manifest.oracleBaseline().toJson(json);
        var connection = connections.forTarget(target);

        connection.transactions().executeWithoutResult(_ -> {
            JdbcClient jdbc = connection.jdbc();
            truncateAll(jdbc);
            insertUsers(jdbc, manifest.users(), prepared);
            insertProducts(jdbc, manifest.products());
            insertCoupons(jdbc, manifest.coupons());
            insertCarts(jdbc, manifest.carts());
            insertOrders(jdbc, manifest.orders());

            List<InvariantViolation> violations = queryInvariants(jdbc, baselineJson);
            if (!violations.isEmpty()) {
                throw new FixtureVerificationException(target, violations);
            }
            syncSequences(jdbc);
        });
    }

    public List<InvariantViolation> verifyInvariants(ShopTarget target, OracleBaseline baseline) {
        String baselineJson = baseline.toJson(json);
        var connection = connections.forTarget(target);
        return queryInvariants(connection.jdbc(), baselineJson);
    }

    private void truncateAll(JdbcClient jdbc) {
        jdbc.sql("TRUNCATE TABLE order_items, orders, cart_items, carts, coupons, products, users CASCADE")
                .update();
    }

    private void insertUsers(JdbcClient jdbc, List<FixtureUser> users, PreparedFixture prepared) {
        for (FixtureUser user : users) {
            jdbc.sql("""
                            INSERT INTO users (id, login, name, email, password_hash)
                            VALUES (:id, :login, :name, :email, :passwordHash)
                            """)
                    .param("id", user.id())
                    .param("login", user.login())
                    .param("name", user.name())
                    .param("email", user.email())
                    .param("passwordHash", prepared.passwordHash(user.id()))
                    .update();
        }
    }

    private void insertProducts(JdbcClient jdbc, List<FixtureProduct> products) {
        for (FixtureProduct product : products) {
            jdbc.sql("""
                            INSERT INTO products (id, name, description, price, published)
                            VALUES (:id, :name, :description, :price, :published)
                            """)
                    .param("id", product.id())
                    .param("name", product.name())
                    .param("description", product.description())
                    .param("price", product.price())
                    .param("published", product.published())
                    .update();
        }
    }

    private void insertCoupons(JdbcClient jdbc, List<FixtureCoupon> coupons) {
        for (FixtureCoupon coupon : coupons) {
            jdbc.sql("""
                            INSERT INTO coupons (id, code, discount_percent, expires_at, max_uses, used_count)
                            VALUES (:id, :code, :discountPercent, :expiresAt, :maxUses, :usedCount)
                            """)
                    .param("id", coupon.id())
                    .param("code", coupon.code())
                    .param("discountPercent", coupon.discountPercent())
                    .param("expiresAt", Timestamp.from(coupon.expiresAt()))
                    .param("maxUses", coupon.maxUses())
                    .param("usedCount", coupon.usedCount())
                    .update();
        }
    }

    private void insertCarts(JdbcClient jdbc, List<FixtureCart> carts) {
        for (FixtureCart cart : carts) {
            jdbc.sql("""
                            INSERT INTO carts (id, user_id, coupon_id, revision, updated_at)
                            VALUES (:id, :userId, :couponId, :revision, :updatedAt)
                            """)
                    .param("id", cart.id())
                    .param("userId", cart.userId())
                    .param("couponId", cart.couponId())
                    .param("revision", cart.revision())
                    .param("updatedAt", Timestamp.from(cart.updatedAt()))
                    .update();

            for (FixtureCart.Item item : cart.items()) {
                jdbc.sql("""
                                INSERT INTO cart_items (cart_id, product_id, quantity)
                                VALUES (:cartId, :productId, :quantity)
                                """)
                        .param("cartId", cart.id())
                        .param("productId", item.productId())
                        .param("quantity", item.quantity())
                        .update();
            }
        }
    }

    private void insertOrders(JdbcClient jdbc, List<FixtureOrder> orders) {
        HexFormat hex = HexFormat.of();
        for (FixtureOrder order : orders) {
            jdbc.sql("""
                            INSERT INTO orders (
                                id, user_id, cart_id, cart_revision, subtotal, discount, total, coupon_id,
                                created_at, idempotency_key_hash, request_fingerprint, response_body
                            )
                            VALUES (
                                :id, :userId, :cartId, :cartRevision, :subtotal, :discount, :total, :couponId,
                                :createdAt, :keyHash, :fingerprint, CAST(:responseBody AS jsonb)
                            )
                            """)
                    .param("id", order.id())
                    .param("userId", order.userId())
                    .param("cartId", order.cartId())
                    .param("cartRevision", order.cartRevision())
                    .param("subtotal", order.subtotal())
                    .param("discount", order.discount())
                    .param("total", order.total())
                    .param("couponId", order.couponId())
                    .param("createdAt", Timestamp.from(order.createdAt()))
                    .param("keyHash", hex.parseHex(order.idempotencyKeyHash()))
                    .param("fingerprint", hex.parseHex(order.requestFingerprint()))
                    .param("responseBody", order.responseBody())
                    .update();

            for (FixtureOrder.Item item : order.items()) {
                jdbc.sql("""
                                INSERT INTO order_items (order_id, product_id, quantity, unit_price)
                                VALUES (:orderId, :productId, :quantity, :unitPrice)
                                """)
                        .param("orderId", order.id())
                        .param("productId", item.productId())
                        .param("quantity", item.quantity())
                        .param("unitPrice", item.unitPrice())
                        .update();
            }
        }
    }

    private void syncSequences(JdbcClient jdbc) {
        for (String table : IDENTITY_TABLES) {
            jdbc.sql("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), COALESCE((SELECT max(id) FROM "
                            + table + "), 1), true)")
                    .query(Long.class)
                    .single();
        }
    }

    private List<InvariantViolation> queryInvariants(JdbcClient jdbc, String baselineJson) {
        return jdbc.sql("""
                        SELECT invariant_id, entity, entity_id, details::text
                        FROM check_business_invariants(CAST(:baseline AS jsonb))
                        """)
                .param("baseline", baselineJson)
                .query(InvariantViolation.class)
                .list();
    }
}
