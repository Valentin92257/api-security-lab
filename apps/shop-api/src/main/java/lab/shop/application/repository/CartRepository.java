package lab.shop.application.repository;

public interface CartRepository {

    /**
     * Создаёт пустую корзину пользователя.
     */
    void createEmpty(long userId);
}
