package lab.shop.application.model;

import java.time.Instant;
import java.util.List;

/**
 * Заказ, готовый к сохранению: заголовок, позиции и заранее собранный response.
 * {@code id} берётся из последовательности до вставки, чтобы {@code responseBody}
 * уже содержал финальный id; {@code items} пишутся вместе с заголовком.
 */
public record NewOrder(
        long id,
        long userId,
        long cartId,
        long cartRevision,
        long subtotal,
        long discount,
        long total,
        Long couponId,
        Instant createdAt,
        byte[] idempotencyKeyHash,
        byte[] requestFingerprint,
        String responseBody,
        List<OrderItem> items
) {
}
