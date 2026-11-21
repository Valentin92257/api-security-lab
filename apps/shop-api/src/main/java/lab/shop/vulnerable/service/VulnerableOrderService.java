package lab.shop.vulnerable.service;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.LineItem;
import lab.contracts.shop.model.Order;
import lab.contracts.shop.model.OrderCoupon;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.AuthUtils;
import lab.shop.application.model.*;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.OrderRepository;
import lab.shop.application.service.CheckoutCrypto;
import lab.shop.application.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Уязвимая реализация заказов с намеренными слабостями:
 * <ul>
 * <li> BO-01: отсутствие проверки владельца в getOrder</li>
 * <li> RP-01/RP-02: пропуск advisory lock, поиска по Idempotency-Key, проверки revision и очистки корзины</li>
 * <li> BL-01/BL-03: применение истёкших/исчерпанных купонов и неатомарное списание без блокировок</li>
 * <li> BL-02: отсутствие проверки quantity (1..99) и published</li>
 * <li> BL-04: доверие клиентскому quotedTotal как итоговой цене заказа</li>
 * </ul>
 */
@Service
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableOrderService implements OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CouponRepository couponRepository;
    private final CheckoutCrypto checkoutCrypto;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Override
    @Transactional
    public CheckoutResult checkout(String idempotencyKey, CheckoutRequest request) {
        validateRequest(idempotencyKey, request);

        long userId = AuthUtils.getUserId();
        byte[] keyHash = checkoutCrypto.idempotencyKeyHash(idempotencyKey);
        byte[] fingerprint = checkoutCrypto.requestFingerprint(request.getCartRevision(), request.getQuotedTotal());

        // Намеренные слабости: пропускаем advisory lock, lookup по Idempotency-Key, FOR UPDATE и проверку revision.
        CartSnapshot cart = loadNonEmptyCart(userId);
        Instant now = clock.instant();
        Pricing pricing = calculatePricingUnchecked(cart, request.getQuotedTotal(), now);

        Order order = persistOrder(userId, cart, request.getCartRevision(), pricing, keyHash, fingerprint, now);

        // Намеренная слабость: не очищаем корзину и не повышаем revision после checkout.
        return new CheckoutResult(order, false);
    }

    @Override
    public Order getOrder(Long orderId) {
        // Намеренная слабость: владелец заказа не проверяется.
        OrderResponse stored = orderRepository.findResponse(orderId)
                .orElseThrow(() -> new NotFoundException("Заказ не найден"));
        return jsonMapper.readValue(stored.responseBody(), Order.class);
    }

    private void validateRequest(String idempotencyKey, CheckoutRequest request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("Некорректный Idempotency-Key");
        }
        if (request.getCartRevision() == null || request.getQuotedTotal() == null) {
            throw new BadRequestException("cartRevision и quotedTotal обязательны");
        }
        // Схемные границы общие для режимов.
        if (request.getCartRevision() < 0 || request.getQuotedTotal() < 0) {
            throw new BadRequestException("cartRevision и quotedTotal должны быть неотрицательными");
        }
    }

    private CartSnapshot loadNonEmptyCart(long userId) {
        CartSnapshot cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));
        if (cart.items().isEmpty()) {
            throw new ConflictException("Корзина пуста");
        }
        return cart;
    }

    private Pricing calculatePricingUnchecked(CartSnapshot cart, long quotedTotal, Instant now) {
        List<LineItem> lineItems = new ArrayList<>(cart.items().size());
        List<OrderItem> orderItems = new ArrayList<>(cart.items().size());
        long subtotal = 0;

        // Намеренная слабость: не проверяем quantity (1..99) и published.
        for (CartItemSnapshot item : cart.items()) {
            subtotal += item.lineTotal();
            lineItems.add(item.toDto());
            orderItems.add(new OrderItem(item.productId(), item.quantity(), item.price()));
        }

        // Намеренные слабости: купон применяется без проверки срока и остатка, а списание выполняется неатомарно.
        Coupon coupon = cart.coupon();
        long discount = 0;
        OrderCoupon orderCoupon = null;
        if (coupon != null) {
            discount = subtotal * coupon.discountPercent() / 100;
            orderCoupon = coupon.toOrderCoupon();
            couponRepository.tryConsume(coupon.id(), now);
        }

        // Намеренная слабость: доверяем клиентскому quotedTotal как итоговой сумме.
        return new Pricing(lineItems, orderItems, subtotal, discount, quotedTotal, orderCoupon);
    }

    private Order persistOrder(long userId,
                               CartSnapshot cart,
                               long cartRevision,
                               Pricing pricing,
                               byte[] keyHash,
                               byte[] fingerprint,
                               Instant now) {
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

    private record Pricing(
            List<LineItem> lineItems,
            List<OrderItem> orderItems,
            long subtotal,
            long discount,
            long total,
            OrderCoupon coupon
    ) {
    }
}
