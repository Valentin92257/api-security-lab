package lab.shop.application.service;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.Order;

public interface OrderService {

    Order checkout(String idempotencyKey, CheckoutRequest request);

    Order getOrder(Long orderId);
}
