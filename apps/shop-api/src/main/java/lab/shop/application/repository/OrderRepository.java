package lab.shop.application.repository;

import lab.shop.application.model.NewOrder;
import lab.shop.application.model.OrderResponse;
import lab.shop.application.model.StoredOrder;

import java.time.Duration;
import java.util.Optional;

public interface OrderRepository {

    /**
     * Берёт транзакционный advisory lock по сокращённому idempotency scope.
     *
     * @throws org.springframework.dao.CannotAcquireLockException protected при ожидании дольше {@code timeout}
     */
    void lockIdempotencyKey(long lockId, Duration timeout);

    /**
     * Ищет успешный заказ по scope {@code (user, key hash)} для replay.
     * Вызывается до проверки текущей корзины и купона.
     */
    Optional<StoredOrder> findByIdempotencyKey(long userId, byte[] keyHash);

    /**
     * Проверяет, что пара {@code (cart_id, cart_revision)} уже израсходована заказом.
     */
    boolean existsForRevision(long cartId, long revision);

    /**
     * Возвращает сохранённый Order response и его владельца. Не проверяет владельца
     */
    Optional<OrderResponse> findResponse(long orderId);

    /**
     * Выдаёт id из последовательности {@code orders} до вставки.
     * Нужен, чтобы построить полный response_body с id и вставить заказ.
     */
    long nextId();

    /**
     * Вставляет заказ вместе с позициями.
     */
    void insert(NewOrder order);
}
