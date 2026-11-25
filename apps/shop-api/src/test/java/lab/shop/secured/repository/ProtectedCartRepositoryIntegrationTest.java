package lab.shop.secured.repository;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("protected")
public class ProtectedCartRepositoryIntegrationTest extends PostgreSQLIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected CartRepository cartRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    protected long userId;
    protected long cartId;
    protected long lampId;
    protected long notebookId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
        cartRepository.createEmpty(userId, T0);
        cartId = cartId(userId);
        lampId = insertProduct("Лампа", 1500, true);
        notebookId = insertProduct("Блокнот", 250, true);
    }

    @Test
    @DisplayName("createEmpty создаёт ровно одну корзину с переданным временем")
    void createEmptyCreatesOneCartRow() {
        long anotherUser = insertUser();

        cartRepository.createEmpty(anotherUser, T0.plusSeconds(30));

        Integer count = jdbc.sql("SELECT count(*) FROM carts WHERE user_id = :userId")
                .param("userId", anotherUser)
                .query(Integer.class)
                .single();
        Instant updatedAt = jdbc.sql("SELECT updated_at FROM carts WHERE user_id = :userId")
                .param("userId", anotherUser)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();

        assertThat(count).isOne();
        assertThat(updatedAt).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("findByUserId возвращает пустую корзину с revision 0")
    void findByUserIdReturnsEmptyCart() {
        CartSnapshot snapshot = cartRepository.findByUserId(userId).orElseThrow();

        assertThat(snapshot.id()).isEqualTo(cartId);
        assertThat(snapshot.userId()).isEqualTo(userId);
        assertThat(snapshot.revision()).isZero();
        assertThat(snapshot.items()).isEmpty();
        assertThat(snapshot.coupon()).isNull();
        assertThat(snapshot.updatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("findByUserId для неизвестного пользователя возвращает Optional.empty")
    void findByUserIdMissingReturnsEmpty() {
        assertThat(cartRepository.findByUserId(999_999)).isEmpty();
    }

    @Test
    @DisplayName("Позиции читаются по возрастанию product_id с названием, ценой и published")
    void findByUserIdReturnsItemsOrderedByProductId() {
        cartRepository.setItemQuantity(cartId, notebookId, 2);
        cartRepository.setItemQuantity(cartId, lampId, 3);
        cartRepository.bumpRevision(cartId, T0.plusSeconds(1));

        CartSnapshot snapshot = cartRepository.findByUserId(userId).orElseThrow();

        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.items()).containsExactly(
                new CartItemSnapshot(lampId, "Лампа", 3, 1500, true),
                new CartItemSnapshot(notebookId, "Блокнот", 2, 250, true));
    }

    @Test
    @DisplayName("Повторная установка того же количества не меняет строку и revision")
    void setItemQuantityNoOpKeepsRevision() {
        assertThat(cartRepository.setItemQuantity(cartId, lampId, 2)).isTrue();
        cartRepository.bumpRevision(cartId, T0.plusSeconds(1));

        assertThat(cartRepository.setItemQuantity(cartId, lampId, 2)).isFalse();

        CartSnapshot snapshot = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.items()).singleElement()
                .extracting(CartItemSnapshot::quantity)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("bumpRevision увеличивает revision и обновляет updated_at")
    void bumpRevisionUpdatesRevisionAndTime() {
        cartRepository.bumpRevision(cartId, T0.plusSeconds(5));
        cartRepository.bumpRevision(cartId, T0.plusSeconds(6));

        CartSnapshot snapshot = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(snapshot.revision()).isEqualTo(2);
        assertThat(snapshot.updatedAt()).isEqualTo(T0.plusSeconds(6));
    }

    @Test
    @DisplayName("setCoupon сохраняет купон и повышает revision, null убирает купон")
    void setCouponStoresCouponAndBumpsRevision() {
        long couponId = insertCoupon("SAVE10", 10, T0.plusSeconds(3600), 5);

        cartRepository.setCoupon(cartId, couponId, T0.plusSeconds(1));

        CartSnapshot withCoupon = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(withCoupon.revision()).isEqualTo(1);
        assertThat(withCoupon.coupon()).isNotNull();
        assertThat(withCoupon.coupon().id()).isEqualTo(couponId);
        assertThat(withCoupon.coupon().code()).isEqualTo("SAVE10");
        assertThat(withCoupon.coupon().discountPercent()).isEqualTo(10);
        assertThat(withCoupon.coupon().expiresAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(withCoupon.coupon().maxUses()).isEqualTo(5);
        assertThat(withCoupon.coupon().usedCount()).isZero();

        cartRepository.setCoupon(cartId, null, T0.plusSeconds(2));

        CartSnapshot withoutCoupon = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(withoutCoupon.revision()).isEqualTo(2);
        assertThat(withoutCoupon.coupon()).isNull();
    }

    @Test
    @DisplayName("clear удаляет позиции, купон и повышает revision")
    void clearRemovesItemsCouponAndBumpsRevision() {
        long couponId = insertCoupon("CLEAR20", 20, T0.plusSeconds(3600), 5);
        cartRepository.setItemQuantity(cartId, lampId, 1);
        cartRepository.bumpRevision(cartId, T0.plusSeconds(1));
        cartRepository.setCoupon(cartId, couponId, T0.plusSeconds(2));

        cartRepository.clear(cartId, T0.plusSeconds(3));

        CartSnapshot snapshot = cartRepository.findByUserId(userId).orElseThrow();
        assertThat(snapshot.revision()).isEqualTo(3);
        assertThat(snapshot.items()).isEmpty();
        assertThat(snapshot.coupon()).isNull();
        assertThat(snapshot.updatedAt()).isEqualTo(T0.plusSeconds(3));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("lockByUserId реально удерживает строчную блокировку FOR UPDATE на carts")
    void lockByUserIdHoldsRowLockUntilCommit() {
        try {
            transactionTemplate.executeWithoutResult(_ -> {
                CartSnapshot locked = cartRepository.lockByUserId(userId).orElseThrow();
                assertThat(locked.id()).isEqualTo(cartId);

                assertThatThrownBy(() -> {
                    try (Connection second = dataSource.getConnection()) {
                        second.setAutoCommit(false);
                        try (PreparedStatement ps = second.prepareStatement(
                                "SELECT 1 FROM carts WHERE id = ? FOR UPDATE NOWAIT")) {
                            ps.setLong(1, cartId);
                            ps.executeQuery();
                        } finally {
                            second.rollback();
                        }
                    }
                }).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("55P03"));
            });
        } finally {
            jdbc.sql("DELETE FROM carts WHERE id = :id").param("id", cartId).update();
            jdbc.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
            jdbc.sql("DELETE FROM products WHERE id IN (:lampId, :notebookId)")
                    .param("lampId", lampId)
                    .param("notebookId", notebookId)
                    .update();
        }
    }

    @Test
    @DisplayName("lockByUserId для неизвестного пользователя возвращает Optional.empty")
    void lockByUserIdMissingReturnsEmpty() {
        assertThat(cartRepository.lockByUserId(999_999)).isEmpty();
    }

    protected long insertUser() {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return userRepository.insert(login, "Test User", login + "@example.com", "hash");
    }

    protected long insertProduct(String name, long price, boolean published) {
        return jdbc.sql("""
                        INSERT INTO products (name, price, published)
                        VALUES (:name, :price, :published)
                        RETURNING id
                        """)
                .param("name", name)
                .param("price", price)
                .param("published", published)
                .query(Long.class)
                .single();
    }

    protected long insertCoupon(String code, int discountPercent, Instant expiresAt, long maxUses) {
        return jdbc.sql("""
                        INSERT INTO coupons (code, discount_percent, expires_at, max_uses)
                        VALUES (:code, :discountPercent, :expiresAt, :maxUses)
                        RETURNING id
                        """)
                .param("code", code)
                .param("discountPercent", discountPercent)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("maxUses", maxUses)
                .query(Long.class)
                .single();
    }

    private long cartId(long userId) {
        return jdbc.sql("SELECT id FROM carts WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
