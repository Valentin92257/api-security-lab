package lab.shop.application.model;

/**
 * Сохранённый ответ заказа вместе с владельцем.
 * Репозиторий только читает данные; решение о доступе принимает сервис.
 */
public record OrderResponse(long userId, String responseBody) {
}
