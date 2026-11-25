package lab.shop.secured.service;

import lab.contracts.shop.model.*;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.AuthUtils;
import lab.shop.application.BusinessClock;
import lab.shop.application.CheckoutProperties;
import lab.shop.application.model.*;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.OrderRepository;
import lab.shop.application.service.CheckoutCrypto;
import lab.shop.application.service.CheckoutSupport;
import lab.shop.application.service.CheckoutSupport.Pricing;
import lab.shop.application.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedOrderService implements OrderService {

    private static final int MIN_QUANTITY = 1;
    private static final int MAX_QUANTITY = 99;
    private static final int MAX_DISTINCT_ITEMS = 100;

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CouponRepository couponRepository;
    private final CheckoutCrypto checkoutCrypto;
    private final CheckoutProperties checkoutProperties;
    private final CheckoutSupport checkoutSupport;
    private final BusinessClock businessClock;

    @Override
    @Transactional
    public CheckoutResult checkout(String idempotencyKey, CheckoutRequest request) {
        checkoutSupport.validateRequest(idempotencyKey, request);

        long userId = AuthUtils.getUserId();
        byte[] keyHash = checkoutCrypto.idempotencyKeyHash(idempotencyKey);
        byte[] fingerprint = checkoutCrypto.requestFingerprint(request.getCartRevision(), request.getQuotedTotal());

        lockIdempotencyScope(userId, keyHash);
        Optional<Order> replayed = findReplayedOrder(userId, keyHash, fingerprint);
        if (replayed.isPresent()) {
            return new CheckoutResult(replayed.get(), true);
        }

        CartSnapshot cart = lockAndValidateCart(userId, request.getCartRevision());
        Instant now = businessClock.now();
        Pricing pricing = calculatePricing(cart, request.getQuotedTotal(), now);
        consumeCoupon(cart.coupon(), now);

        Order order = persistOrder(userId, cart, pricing, keyHash, fingerprint, now);
        cartRepository.clear(cart.id(), now);
        return new CheckoutResult(order, false);
    }

    @Override
    public Order getOrder(Long orderId) {
        long userId = AuthUtils.getUserId();
        OrderResponse stored = orderRepository.findResponse(orderId)
                .orElseThrow(() -> new NotFoundException("Заказ не найден"));
        if (stored.userId() != userId) {
            throw new NotFoundException("Заказ не найден");
        }
        return checkoutSupport.deserializeOrder(stored.responseBody());
    }

    private void lockIdempotencyScope(long userId, byte[] keyHash) {
        long lockId = checkoutCrypto.advisoryLockId(userId, keyHash);
        try {
            orderRepository.lockIdempotencyKey(lockId, checkoutProperties.keyLockTimeout());
        } catch (PessimisticLockingFailureException e) {
            throw new ConflictException("Ожидание блокировки Idempotency-Key истекло", e);
        }
    }

    private Optional<Order> findReplayedOrder(long userId, byte[] keyHash, byte[] fingerprint) {
        Optional<StoredOrder> existing = orderRepository.findByIdempotencyKey(userId, keyHash);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(existing.get().requestFingerprint(), fingerprint)) {
            throw new ConflictException("Idempotency-Key уже использован с другим телом запроса");
        }
        return Optional.of(checkoutSupport.deserializeOrder(existing.get().responseBody()));
    }

    private CartSnapshot lockAndValidateCart(long userId, long requestedRevision) {
        CartSnapshot cart = cartRepository.lockByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));

        if (orderRepository.existsForRevision(cart.id(), requestedRevision)) {
            throw new ConflictException("Эта корзина уже оформлена");
        }
        if (cart.revision() != requestedRevision) {
            throw new ConflictException("Неактуальная версия корзины");
        }
        if (cart.items().isEmpty() || cart.items().size() > MAX_DISTINCT_ITEMS) {
            throw new ConflictException("Корзина пуста или превышает лимит позиций");
        }
        return cart;
    }

    private Pricing calculatePricing(CartSnapshot cart, long quotedTotal, Instant now) {
        List<LineItem> lineItems = new ArrayList<>(cart.items().size());
        List<OrderItem> orderItems = new ArrayList<>(cart.items().size());
        long subtotal = 0;

        for (CartItemSnapshot item : cart.items()) {
            if (!item.published() || item.quantity() < MIN_QUANTITY || item.quantity() > MAX_QUANTITY) {
                throw new ConflictException("Недопустимая позиция в корзине: " + item.name());
            }
            subtotal += item.lineTotal();
            lineItems.add(item.toDto());
            orderItems.add(new OrderItem(item.productId(), item.quantity(), item.price()));
        }

        Coupon coupon = cart.coupon();
        long discount = 0;
        OrderCoupon orderCoupon = null;
        if (coupon != null) {
            if (coupon.getEligibility(now) != CartCoupon.EligibilityEnum.VALID) {
                throw new ConflictException("Купон истёк или исчерпан");
            }
            discount = subtotal * coupon.discountPercent() / 100;
            orderCoupon = coupon.toOrderCoupon();
        }

        long total = subtotal - discount;
        if (total != quotedTotal) {
            throw new ConflictException("Итоговая сумма заказа не совпадает с quotedTotal");
        }
        return new Pricing(lineItems, orderItems, subtotal, discount, total, orderCoupon);
    }

    private void consumeCoupon(Coupon coupon, Instant now) {
        if (coupon != null && couponRepository.tryConsume(coupon.id(), now) == 0) {
            throw new ConflictException("Купон истёк или исчерпан");
        }
    }

    private Order persistOrder(long userId,
                               CartSnapshot cart,
                               Pricing pricing,
                               byte[] keyHash,
                               byte[] fingerprint,
                               Instant now) {
        try {
            return checkoutSupport.persist(userId, cart, cart.revision(), pricing, keyHash, fingerprint, now);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Конфликт при сохранении заказа", e);
        }
    }
}
