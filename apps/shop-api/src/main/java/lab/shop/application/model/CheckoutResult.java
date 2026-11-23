package lab.shop.application.model;

import lab.contracts.shop.model.Order;

/**
 * Результат checkout: заказ и признак возврата сохранённого ответа по Idempotency-Key.
 */
public record CheckoutResult(Order order, boolean replayed) {
}
