package lab.shop.application.model;

/**
 * Сохранённый успешный заказ, найденный по Idempotency-Key.
 * Используется для повтора: fingerprint сравнивается с текущим телом запроса,
 * а responseBody возвращается клиенту без нового бизнес-эффекта.
 */
public record StoredOrder(long id, byte[] requestFingerprint, String responseBody) {
}
