package lab.shop.secured.repository;

import lab.shop.application.model.NewOrder;
import lab.shop.application.model.OrderItem;
import lab.shop.application.model.OrderResponse;
import lab.shop.application.model.StoredOrder;
import lab.shop.application.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Repository
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedOrderRepository implements OrderRepository {

    /**
     * SQLState, которым PostgreSQL сообщает об истечении {@code lock_timeout}.
     */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbc;

    /**
     * Ограничивает ожидание advisory lock на время checkout, затем снимает лимит,
     * чтобы блокировка корзины и купона дальше работала без таймаута.
     * Истечение {@code lock_timeout} отдаётся как {@link CannotAcquireLockException}
     */
    @Override
    public void lockIdempotencyKey(long lockId, Duration timeout) {
        jdbc.sql("SET LOCAL lock_timeout = " + Math.max(1, timeout.toMillis()))
                .update();

        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(:lockId)")
                    .param("lockId", lockId)
                    .query()
                    .singleRow();
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                throw new CannotAcquireLockException("Idempotency key is locked by another checkout", e);
            }
            throw e;
        }

        jdbc.sql("SET LOCAL lock_timeout = 0")
                .update();
    }

    @Override
    public Optional<StoredOrder> findByIdempotencyKey(long userId, byte[] keyHash) {
        return jdbc.sql("""
                        SELECT id, request_fingerprint, response_body
                        FROM orders
                        WHERE user_id = :userId
                          AND idempotency_key_hash = decode(:keyHash, 'hex')
                        """)
                .param("userId", userId)
                .param("keyHash", HexFormat.of().formatHex(keyHash))
                .query(StoredOrder.class)
                .optional();
    }

    @Override
    public boolean existsForRevision(long cartId, long revision) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM orders
                            WHERE cart_id = :cartId
                              AND cart_revision = :revision
                        )
                        """)
                .param("cartId", cartId)
                .param("revision", revision)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<OrderResponse> findResponse(long orderId) {
        return jdbc.sql("""
                        SELECT user_id, response_body
                        FROM orders
                        WHERE id = :orderId
                        """)
                .param("orderId", orderId)
                .query(OrderResponse.class)
                .optional();
    }

    @Override
    public long nextId() {
        return jdbc.sql("SELECT nextval(pg_get_serial_sequence('orders', 'id'))")
                .query(Long.class)
                .single();
    }

    @Override
    public void insert(NewOrder order) {
        jdbc.sql("""
                        INSERT INTO orders (id, user_id, cart_id, cart_revision, subtotal, discount, total,
                                            coupon_id, created_at, idempotency_key_hash, request_fingerprint,
                                            response_body)
                        VALUES (:id, :userId, :cartId, :cartRevision, :subtotal, :discount, :total,
                                :couponId, :createdAt, decode(:keyHash, 'hex'), decode(:fingerprint, 'hex'),
                                CAST(:responseBody AS jsonb))
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
                .param("keyHash", HexFormat.of().formatHex(order.idempotencyKeyHash()))
                .param("fingerprint", HexFormat.of().formatHex(order.requestFingerprint()))
                .param("responseBody", order.responseBody())
                .update();

        for (OrderItem item : order.items()) {
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
