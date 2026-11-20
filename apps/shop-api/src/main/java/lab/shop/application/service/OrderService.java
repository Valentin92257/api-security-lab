package lab.shop.application.service;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.Order;
import lab.shop.application.model.CheckoutResult;

public interface OrderService {

    CheckoutResult checkout(String idempotencyKey, CheckoutRequest request);

    Order getOrder(Long orderId);
}
