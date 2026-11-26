package lab.control.fixture.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

public record OracleBaseline(
        List<CatalogEntry> catalog,
        List<CouponEntry> coupons,
        List<CouponCounter> couponCounters,
        List<Long> orderIds
) {
    public OracleBaseline {
        catalog = List.copyOf(catalog);
        coupons = List.copyOf(coupons);
        couponCounters = List.copyOf(couponCounters);
        orderIds = List.copyOf(orderIds);
    }

    public String toJson(JsonMapper mapper) {
        return mapper.writeValueAsString(this);
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
