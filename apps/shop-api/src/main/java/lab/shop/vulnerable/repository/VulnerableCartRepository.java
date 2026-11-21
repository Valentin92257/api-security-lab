package lab.shop.vulnerable.repository;

import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import lab.shop.application.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Уязвимая реализация хранилища корзины.
 * Отличия от protected находятся в сервисах (owner-check, checkout)
 */
@Repository
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableCartRepository implements CartRepository {

    private final JdbcClient jdbc;

    @Override
    public void createEmpty(long userId, Instant now) {
        String sql = "INSERT INTO carts (user_id, updated_at) VALUES (%s, '%s')"
                .formatted(userId, Timestamp.from(now));

        jdbc.sql(sql)
                .update();
    }

    @Override
    public Optional<CartSnapshot> findByUserId(long userId) {
        return findCart(userId, false);
    }

    @Override
    public Optional<CartSnapshot> lockByUserId(long userId) {
        return findCart(userId, true);
    }

    @Override
    public boolean setItemQuantity(long cartId, long productId, int quantity) {
        String sql = """
                INSERT INTO cart_items (cart_id, product_id, quantity)
                VALUES (%s, %s, %s)
                ON CONFLICT (cart_id, product_id) DO UPDATE
                    SET quantity = EXCLUDED.quantity
                    WHERE cart_items.quantity <> EXCLUDED.quantity
                """.formatted(cartId, productId, quantity);

        return jdbc.sql(sql)
                .update() > 0;
    }

    @Override
    public void bumpRevision(long cartId, Instant now) {
        String sql = """
                UPDATE carts
                SET revision = revision + 1,
                    updated_at = '%s'
                WHERE id = %s
                """.formatted(Timestamp.from(now), cartId);

        jdbc.sql(sql)
                .update();
    }

    @Override
    public void setCoupon(long cartId, Long couponId, Instant now) {
        String sql = """
                UPDATE carts
                SET coupon_id = %s,
                    revision = revision + 1,
                    updated_at = '%s'
                WHERE id = %s
                """.formatted(couponId, Timestamp.from(now), cartId);

        jdbc.sql(sql)
                .update();
    }

    @Override
    public void clear(long cartId, Instant now) {
        jdbc.sql("DELETE FROM cart_items WHERE cart_id = %s".formatted(cartId))
                .update();

        String sql = """
                UPDATE carts
                SET coupon_id = NULL,
                    revision = revision + 1,
                    updated_at = '%s'
                WHERE id = %s
                """.formatted(Timestamp.from(now), cartId);
        jdbc.sql(sql)
                .update();
    }

    private Optional<CartSnapshot> findCart(long userId, boolean forUpdate) {
        String sql = """
                SELECT c.id, c.user_id, c.revision, c.updated_at,
                       k.id AS coupon_id, k.code, k.discount_percent,
                       k.expires_at, k.max_uses, k.used_count
                FROM carts c
                LEFT JOIN coupons k ON k.id = c.coupon_id
                WHERE c.user_id = %s
                """.formatted(userId) + (forUpdate ? " FOR UPDATE OF c" : "");

        Optional<CartSnapshot> cart = jdbc.sql(sql)
                .query((rs, _) -> {
                    long couponId = rs.getLong("coupon_id");
                    Coupon coupon = rs.wasNull() ? null : new Coupon(
                            couponId,
                            rs.getString("code"),
                            rs.getInt("discount_percent"),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            rs.getLong("max_uses"),
                            rs.getLong("used_count"));

                    return new CartSnapshot(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getLong("revision"),
                            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                            List.of(),
                            coupon);
                })
                .optional();

        return cart.map(snapshot -> new CartSnapshot(
                snapshot.id(),
                snapshot.userId(),
                snapshot.revision(),
                snapshot.updatedAt(),
                findItems(snapshot.id()),
                snapshot.coupon()));
    }

    private List<CartItemSnapshot> findItems(long cartId) {
        String sql = """
                SELECT ci.product_id, p.name, ci.quantity, p.price, p.published
                FROM cart_items ci
                JOIN products p ON p.id = ci.product_id
                WHERE ci.cart_id = %s
                ORDER BY ci.product_id
                """.formatted(cartId);

        return jdbc.sql(sql)
                .query(CartItemSnapshot.class)
                .list();
    }
}
