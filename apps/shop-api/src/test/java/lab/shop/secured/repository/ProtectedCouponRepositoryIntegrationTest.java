package lab.shop.secured.repository;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.application.model.Coupon;
import lab.shop.application.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("protected")
public class ProtectedCouponRepositoryIntegrationTest extends PostgreSQLIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected CouponRepository couponRepository;

    @Autowired
    protected JdbcClient jdbc;

    protected long validId;
    protected long exhaustedId;
    protected long expiringId;

    /**
     * Vulnerable-профиль допускает неатомарное расходование: под конкуренцией
     * число успешных tryConsume превышает max_uses.
     */
    protected boolean shouldOverconsumeUnderConcurrency() {
        return false;
    }

    @BeforeEach
    void setUp() {
        validId = insertCoupon("SPRING10", 10, T0.plusSeconds(3600), 5);
        exhaustedId = insertCoupon("OLD20", 20, T0.plusSeconds(3600), 2, 2);
        expiringId = insertCoupon("EDGE30", 30, T0.plusSeconds(60), 1);
    }

    @Test
    @DisplayName("Поиск по коду возвращает все поля купона")
    void findByCodeReturnsCouponFields() {
        Optional<Coupon> coupon = couponRepository.findByCode("SPRING10");

        assertThat(coupon).isPresent();
        assertThat(coupon.get().id()).isEqualTo(validId);
        assertThat(coupon.get().code()).isEqualTo("SPRING10");
        assertThat(coupon.get().discountPercent()).isEqualTo(10);
        assertThat(coupon.get().expiresAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(coupon.get().maxUses()).isEqualTo(5);
        assertThat(coupon.get().usedCount()).isZero();
    }

    @Test
    @DisplayName("Сравнение кода точное")
    void findByCodeIsExactMatch() {
        assertThat(couponRepository.findByCode("spring10")).isEmpty();
        assertThat(couponRepository.findByCode(" SPRING10 ")).isEmpty();
        assertThat(couponRepository.findByCode("SPRING10")).isPresent();
    }

    @Test
    @DisplayName("Неизвестный код возвращает Optional.empty")
    void findByCodeUnknownReturnsEmpty() {
        assertThat(couponRepository.findByCode("NO_SUCH_CODE")).isEmpty();
    }

    @Test
    @DisplayName("Расходование увеличивает used_count на единицу")
    void tryConsumeIncrementsUsedCount() {
        int result = couponRepository.tryConsume(validId, T0);

        assertThat(result).isEqualTo(1);
        assertThat(usedCount(validId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Исчерпанный купон не расходуется, счётчик не меняется")
    void tryConsumeFailsWhenExhausted() {
        int result = couponRepository.tryConsume(exhaustedId, T0);

        assertThat(result).isZero();
        assertThat(usedCount(exhaustedId)).isEqualTo(2);
    }

    @Test
    @DisplayName("Граница срока: now равное expires_at уже означает истечение")
    void tryConsumeFailsAtExpiryBoundary() {
        int atBoundary = couponRepository.tryConsume(expiringId, T0.plusSeconds(60));
        int before = couponRepository.tryConsume(validId, T0.plusSeconds(3599));

        assertThat(atBoundary).isZero();
        assertThat(usedCount(expiringId)).isZero();
        assertThat(before).isEqualTo(1);
    }

    @Test
    @DisplayName("Расходование отсутствующего купона возвращает 0")
    void tryConsumeMissingReturnsZero() {
        assertThat(couponRepository.tryConsume(999_999, T0)).isZero();
    }

    @Test
    @DisplayName("Конкуренция: не более max_uses успешных расходований")
    void concurrentConsumptionRespectsMaxUses() throws Exception {
        long couponId = insertCoupon("RACE99", 99, T0.plusSeconds(3600), 1);
        int attempts = 8;

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<Integer>> futures = IntStream.range(0, attempts)
                    .mapToObj(_ -> pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return couponRepository.tryConsume(couponId, T0);
                    }))
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int successes = 0;
            for (Future<Integer> future : futures) {
                successes += future.get(10, TimeUnit.SECONDS);
            }

            // Инвариант обоих профилей: потерянных обновлений нет.
            assertThat(usedCount(couponId)).isEqualTo(successes);

            if (shouldOverconsumeUnderConcurrency()) {
                assertThat(successes).isGreaterThan(1);
            } else {
                assertThat(successes).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long insertCoupon(String code, int discountPercent, Instant expiresAt, int maxUses) {
        return insertCoupon(code, discountPercent, expiresAt, maxUses, 0);
    }

    private long insertCoupon(String code, int discountPercent, Instant expiresAt, int maxUses, int usedCount) {
        return jdbc.sql("""
                        INSERT INTO coupons (code, discount_percent, expires_at, max_uses, used_count)
                        VALUES (:code, :discountPercent, :expiresAt, :maxUses, :usedCount)
                        RETURNING id
                        """)
                .param("code", code)
                .param("discountPercent", discountPercent)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("maxUses", maxUses)
                .param("usedCount", usedCount)
                .query(Long.class)
                .single();
    }

    private long usedCount(long couponId) {
        return jdbc.sql("SELECT used_count FROM coupons WHERE id = :id")
                .param("id", couponId)
                .query(Long.class)
                .single();
    }
}
