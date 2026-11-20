package lab.shop.application.repository;

import java.time.Instant;

public interface CartRepository {

    /**
     * Создаёт пустую корзину пользователя.
     */
    void createEmpty(long userId, Instant now);
}
