package lab.shop.application.service;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.LineItem;
import lab.contracts.shop.model.Order;
import lab.contracts.shop.model.OrderCoupon;
import lab.shop.api.exception.BadRequestException;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.NewOrder;
import lab.shop.application.model.OrderItem;
import lab.shop.application.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class CheckoutSupport {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{16,128}");

    private final OrderRepository orderRepository;
    private final JsonMapper jsonMapper;

    public void validateRequest(String idempotencyKey, CheckoutRequest request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("Некорректный Idempotency-Key");
        }
        if (request.getCartRevision() == null || request.getQuotedTotal() == null) {
            throw new BadRequestException("cartRevision и quotedTotal обязательны");
        }
        if (request.getCartRevision() < 0 || request.getQuotedTotal() < 0) {
            throw new BadRequestException("cartRevision и quotedTotal должны быть неотрицательными");
        }
    }

    public Order persist(long userId, CartSnapshot cart, long cartRevision, Pricing pricing,
                         byte[] keyHash, byte[] fingerprint, Instant now) {
        long orderId = orderRepository.nextId();
        Order order = new Order(
                orderId,
                userId,
                cart.id(),
                cartRevision,
                pricing.lineItems(),
                pricing.coupon(),
                pricing.subtotal(),
                pricing.discount(),
                pricing.total(),
                Order.CurrencyEnum.RUB,
                now.atOffset(ZoneOffset.UTC));

        String responseBody = jsonMapper.writeValueAsString(order);
        orderRepository.insert(new NewOrder(
                orderId,
                userId,
                cart.id(),
                cartRevision,
                pricing.subtotal(),
                pricing.discount(),
                pricing.total(),
                cart.coupon() == null ? null : cart.coupon().id(),
                now,
                keyHash,
                fingerprint,
                responseBody,
                pricing.orderItems()));
        return order;
    }

    public Order deserializeOrder(String responseBody) {
        return jsonMapper.readValue(responseBody, Order.class);
    }

    public record Pricing(
            List<LineItem> lineItems,
            List<OrderItem> orderItems,
            long subtotal,
            long discount,
            long total,
            OrderCoupon coupon
    ) {
    }
}
