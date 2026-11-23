package lab.shop.application.service;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.Order;
import lab.shop.application.model.CheckoutResult;

import java.util.regex.Pattern;

public interface OrderService {

    Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{16,128}");

    CheckoutResult checkout(String idempotencyKey, CheckoutRequest request);

    Order getOrder(Long orderId);
}
