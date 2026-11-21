package lab.shop.vulnerable.service;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.Order;
import lab.shop.application.model.CheckoutResult;
import lab.shop.application.service.OrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("vulnerable")
public class VulnerableOrderService implements OrderService {

    @Override
    public CheckoutResult checkout(String idempotencyKey, CheckoutRequest request) {
        return new CheckoutResult(new Order(), false);
    }

    @Override
    public Order getOrder(Long orderId) {
        return new Order();
    }
}
