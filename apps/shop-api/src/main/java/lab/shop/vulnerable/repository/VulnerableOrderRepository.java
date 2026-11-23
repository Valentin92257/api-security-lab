package lab.shop.vulnerable.repository;

import lab.shop.application.model.NewOrder;
import lab.shop.application.model.OrderItem;
import lab.shop.application.model.OrderResponse;
import lab.shop.application.model.StoredOrder;
import lab.shop.application.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Уязвимая реализация хранилища заказов.
 * Advisory lock намеренно отсутствует; проверка владельца выполняется сервисом
 * и в vulnerable-режиме намеренно пропускается.
 */
@Repository
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableOrderRepository implements OrderRepository {

    private final JdbcClient jdbc;

    /**
     * Намеренная слабость: параллельные checkout с одним ключом не сериализуются.
     */
    @Override
    public void lockIdempotencyKey(long lockId, Duration timeout) {
        // no-op
    }

    @Override
    public Optional<StoredOrder> findByIdempotencyKey(long userId, byte[] keyHash) {
        String sql = """
                SELECT id, request_fingerprint, response_body
                FROM orders
                WHERE user_id = %s
                  AND idempotency_key_hash = decode('%s', 'hex')
                """.formatted(userId, HexFormat.of().formatHex(keyHash));

        return jdbc.sql(sql)
                .query(StoredOrder.class)
                .optional();
    }

    @Override
    public boolean existsForRevision(long cartId, long revision) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM orders
                    WHERE cart_id = %s
                      AND cart_revision = %s
                )
                """.formatted(cartId, revision);

        return jdbc.sql(sql)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<OrderResponse> findResponse(long orderId) {
        String sql = """
                SELECT user_id, response_body
                FROM orders
                WHERE id = %s
                """.formatted(orderId);

        return jdbc.sql(sql)
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
        String escapedResponseBody = order.responseBody().replace("'", "''");
        String sql = """
                INSERT INTO orders (id, user_id, cart_id, cart_revision, subtotal, discount, total,
                                    coupon_id, created_at, idempotency_key_hash, request_fingerprint,
                                    response_body)
                VALUES (%s, %s, %s, %s, %s, %s, %s,
                        %s, '%s', decode('%s', 'hex'), decode('%s', 'hex'),
                        '%s'::jsonb)
                """.formatted(
                order.id(), order.userId(), order.cartId(), order.cartRevision(), order.subtotal(),
                order.discount(), order.total(), order.couponId(), Timestamp.from(order.createdAt()),
                HexFormat.of().formatHex(order.idempotencyKeyHash()), HexFormat.of().formatHex(order.requestFingerprint()),
                escapedResponseBody);

        jdbc.sql(sql)
                .update();

        for (OrderItem item : order.items()) {
            jdbc.sql("""
                            INSERT INTO order_items (order_id, product_id, quantity, unit_price)
                            VALUES (%s, %s, %s, %s)
                            """.formatted(order.id(), item.productId(), item.quantity(), item.unitPrice()))
                    .update();
        }
    }
}
