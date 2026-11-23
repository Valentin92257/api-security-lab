package lab.shop.application.model;

import lab.contracts.shop.model.CartCoupon;
import lab.contracts.shop.model.OrderCoupon;

import java.time.Instant;

public record Coupon(
        long id,
        String code,
        int discountPercent,
        Instant expiresAt,
        long maxUses,
        long usedCount
) {

    public CartCoupon toDto(Instant now) {
        return new CartCoupon(code, discountPercent, getEligibility(now));
    }

    public OrderCoupon toOrderCoupon() {
        return new OrderCoupon(code, discountPercent);
    }

    public CartCoupon.EligibilityEnum getEligibility(Instant now) {
        if (usedCount >= maxUses) {
            return CartCoupon.EligibilityEnum.EXHAUSTED;
        } else if (!expiresAt.isAfter(now)) {
            return CartCoupon.EligibilityEnum.EXPIRED;
        }

        return CartCoupon.EligibilityEnum.VALID;
    }
}
