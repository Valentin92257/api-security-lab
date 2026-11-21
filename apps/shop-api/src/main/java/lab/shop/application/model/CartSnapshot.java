package lab.shop.application.model;

import lab.contracts.shop.model.Cart;
import lab.contracts.shop.model.CartCoupon;
import lab.contracts.shop.model.LineItem;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public record CartSnapshot(
        long id,
        long userId,
        long revision,
        Instant updatedAt,
        List<CartItemSnapshot> items,
        Coupon coupon
) {

    public Cart toDto(Instant now) {
        List<LineItem> lineItems = items.stream()
                .map(CartItemSnapshot::toDto)
                .toList();

        long subtotal = lineItems.stream()
                .mapToLong(LineItem::getLineTotal)
                .sum();

        CartCoupon cartCoupon = coupon == null ? null : coupon.toDto(now);

        int discountPercent = cartCoupon != null
                && cartCoupon.getEligibility() == CartCoupon.EligibilityEnum.VALID
                ? cartCoupon.getDiscountPercent() : 0;

        long discount = Math.floorDiv(subtotal * discountPercent, 100);
        long total = subtotal - discount;

        return new Cart(
                id,
                userId,
                revision,
                lineItems,
                cartCoupon,
                subtotal,
                discount,
                total,
                Cart.CurrencyEnum.RUB,
                updatedAt.atOffset(ZoneOffset.UTC)
        );
    }
}
