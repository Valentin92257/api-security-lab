package lab.shop.vulnerable.repository;

import lab.shop.application.model.Coupon;
import lab.shop.application.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableCouponRepository implements CouponRepository {

    private final JdbcClient jdbc;

    @Override
    public Optional<Coupon> findByCode(String code) {
        String sql = """
                SELECT id, code, discount_percent, expires_at, max_uses, used_count
                FROM coupons
                WHERE code = '%s'
                """.formatted(code);

        return jdbc.sql(sql)
                .query(Coupon.class)
                .optional();
    }

    /**
     * Проверка и инкремент НЕ атомарны.
     */
    @Override
    public int tryConsume(long couponId, Instant now) {
        String sql = """
                SELECT id, code, discount_percent, expires_at, max_uses, used_count
                FROM coupons
                WHERE id = %s
                """.formatted(couponId);

        Optional<Coupon> found = jdbc.sql(sql)
                .query(Coupon.class)
                .optional();

        if (found.isEmpty()) {
            return 0;
        }

        Coupon coupon = found.get();
        if (coupon.usedCount() >= coupon.maxUses() || !coupon.expiresAt().isAfter(now)) {
            return 0;
        }

        try {
            // искусственное окно для Race Condition
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return jdbc.sql("""
                        UPDATE coupons
                        SET used_count = used_count + 1
                        WHERE id = %s
                        """.formatted(couponId))
                .update();
    }
}