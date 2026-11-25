package lab.control.fixture.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OracleBaseline(
        List<CatalogEntry> catalog,
        List<CouponEntry> coupons,
        List<CouponCounter> couponCounters,
        List<Long> orderIds
) {
    public OracleBaseline {
        catalog = List.copyOf(Objects.requireNonNull(catalog, "catalog"));
        coupons = List.copyOf(Objects.requireNonNull(coupons, "coupons"));
        couponCounters = List.copyOf(Objects.requireNonNull(couponCounters, "couponCounters"));
        orderIds = List.copyOf(Objects.requireNonNull(orderIds, "orderIds"));
    }

    public String toJson(JsonMapper mapper) {
        return Objects.requireNonNull(mapper, "mapper").writeValueAsString(this);
    }

    public record CatalogEntry(
            @JsonProperty("product_id") long productId,
            String name,
            String description,
            long price,
            boolean published
    ) {
    }

    public record CouponEntry(
            @JsonProperty("coupon_id") long couponId,
            String code,
            @JsonProperty("discount_percent") int discountPercent,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("max_uses") long maxUses
    ) {
    }

    public record CouponCounter(
            @JsonProperty("coupon_id") long couponId,
            @JsonProperty("used_count") long usedCount
    ) {
    }
}
