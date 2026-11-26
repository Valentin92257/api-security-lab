package lab.control.fixture.model;

import java.time.Instant;
import java.util.List;

public record FixtureOrder(
        long id,
        long userId,
        long cartId,
        long cartRevision,
        long subtotal,
        long discount,
        long total,
        Long couponId,
        Instant createdAt,
        String idempotencyKeyHash,
        String requestFingerprint,
        String responseBody,
        List<Item> items
) {
    public FixtureOrder {
        items = List.copyOf(items);
    }

    @Override
    public String toString() {
        return "FixtureOrder[id=" + id + ", userId=" + userId + ", cartId=" + cartId
                + ", cartRevision=" + cartRevision + ", total=" + total + "]";
    }

    public record Item(long productId, String name, int quantity, long unitPrice) {
        public long lineTotal() {
            return quantity * unitPrice;
        }
    }
}
