package lab.shop.secured.repository;

import lab.shop.application.model.Coupon;
import lab.shop.application.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedCouponRepository implements CouponRepository {

    private final JdbcClient jdbc;

    @Override
    public Optional<Coupon> findByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, discount_percent, expires_at, max_uses, used_count
                        FROM coupons
                        WHERE code = :code
                        """)
                .param("code", code)
                .query(Coupon.class)
                .optional();
    }

    /**
     * Атомарное увеличение used_count.
     */
    @Override
    public int tryConsume(long couponId, Instant now) {
        return jdbc.sql("""
                        UPDATE coupons
                        SET used_count = used_count + 1
                        WHERE id = :id
                          AND used_count < max_uses
                          AND expires_at > :now
                        """)
                .param("id", couponId)
                .param("now", now)
                .update();
    }
}
