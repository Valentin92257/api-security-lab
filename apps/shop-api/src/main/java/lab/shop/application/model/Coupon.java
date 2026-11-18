package lab.shop.application.model;

import lab.contracts.shop.model.CartCoupon;

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

    private CartCoupon.EligibilityEnum getEligibility(Instant now) {
        if (usedCount >= maxUses) {
            return CartCoupon.EligibilityEnum.EXHAUSTED;
        }

        if (!expiresAt.isAfter(now)) {
            return CartCoupon.EligibilityEnum.EXPIRED;
        }

        return CartCoupon.EligibilityEnum.VALID;
    }
}
