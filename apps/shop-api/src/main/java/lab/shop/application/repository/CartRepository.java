package lab.shop.application.repository;

import lab.shop.application.model.CartSnapshot;

import java.time.Instant;
import java.util.Optional;

public interface CartRepository {

    /**
     * Создаёт пустую корзину пользователя.
     */
    void createEmpty(long userId, Instant now);

    /**
     * Читает корзину без блокировки.
     *
     * @return {@code Optional.empty()}, если корзины нет
     */
    Optional<CartSnapshot> findByUserId(long userId);

    /**
     * Читает корзину с блокировкой строки {@code carts} ({@code SELECT ... FOR UPDATE}).
     *
     * @return {@code Optional.empty()}, если корзины нет
     */
    Optional<CartSnapshot> lockByUserId(long userId);

    /**
     * Устанавливает абсолютное количество товара в корзине.
     *
     * @return {@code true}, если строка корзины реально изменилась
     * и revision нужно повысить через {@link #bumpRevision(long, Instant)}
     */
    boolean setItemQuantity(long cartId, long productId, int quantity);

    /**
     * Повышает revision и {@code updated_at} после реального изменения корзины.
     * Revision - версия состояния для проверки устаревшего checkout.
     */
    void bumpRevision(long cartId, Instant now);

    /**
     * Выбирает купон ({@code null} - убрать купон).
     * Вызывается только при реальном изменении, поэтому сразу повышает revision.
     */
    void setCoupon(long cartId, Long couponId, Instant now);

    /**
     * Очищает позиции и купон после успешного checkout и повышает revision.
     * Повторный checkout той же revision после очистки должен быть отклонён.
     */
    void clear(long cartId, Instant now);
}
