package lab.control.fixture.model;

import java.time.Instant;

public record FixtureCoupon(
        long id,
        String code,
        int discountPercent,
        Instant expiresAt,
        int maxUses,
        int usedCount,
        Kind kind
) {
    public enum Kind {
        VALID,
        EXPIRED,
        EXHAUSTED,
        LAST_USE,
        FULL_DISCOUNT
    }
}
