package lab.shop.application.repository;

import lab.shop.application.model.Coupon;

import java.time.Instant;
import java.util.Optional;

public interface CouponRepository {

    /**
     * Поиск купона по коду. Код ожидается нормализованным - сравнение точное.
     *
     * @return {@code Optional.empty()} неизвестный код
     */
    Optional<Coupon> findByCode(String code);

    /**
     * Атомарно расходует одно использование купона.
     *
     * @return 1 - использование списано; 0 - купон истёк, исчерпан или отсутствует
     */
    int tryConsume(long couponId, Instant now);
}
