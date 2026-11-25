package lab.shop.secured.service;

import lab.contracts.shop.model.Cart;
import lab.contracts.shop.model.SelectCouponRequest;
import lab.contracts.shop.model.SetQuantityRequest;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.AuthUtils;
import lab.shop.application.BusinessClock;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.ProductRepository;
import lab.shop.application.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedCartService implements CartService {

    private static final int MIN_QUANTITY = 1;
    private static final int MAX_QUANTITY = 99;
    private static final int MAX_DISTINCT_ITEMS = 100;
    private static final Pattern COUPON_CODE_PATTERN = Pattern.compile("[A-Z0-9_-]{1,32}");

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final BusinessClock businessClock;

    @Override
    public Cart getCart() {
        long userId = AuthUtils.getUserId();
        CartSnapshot cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));
        return cart.toDto(businessClock.now());
    }

    @Override
    @Transactional
    public Cart setCartQuantity(Long productId, SetQuantityRequest request) {
        Integer quantity = request.getQuantity();
        if (quantity == null || quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new BadRequestException("quantity должен быть в диапазоне 1..99");
        }
        if (!productRepository.existsPublished(productId)) {
            throw new NotFoundException("Товар не найден");
        }

        long userId = AuthUtils.getUserId();
        CartSnapshot cart = cartRepository.lockByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));

        boolean existsInCart = cart.items().stream()
                .anyMatch(item -> item.productId() == productId);
        if (!existsInCart && cart.items().size() >= MAX_DISTINCT_ITEMS) {
            throw new BadRequestException("В корзине не может быть больше 100 различных товаров");
        }

        Instant now = businessClock.now();
        try {
            if (cartRepository.setItemQuantity(cart.id(), productId, quantity)) {
                cartRepository.bumpRevision(cart.id(), now);
                cart = cartRepository.findByUserId(userId)
                        .orElseThrow(() -> new NotFoundException("Корзина не найдена"));
            }
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("Недопустимые данные позиции корзины", e);
        }
        return cart.toDto(now);
    }

    @Override
    @Transactional
    public Cart selectCoupon(SelectCouponRequest request) {
        String code = normalizeCouponCode(request.getCode());
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Купон не найден"));

        long userId = AuthUtils.getUserId();
        CartSnapshot cart = cartRepository.lockByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));

        Instant now = businessClock.now();
        if (cart.coupon() != null && cart.coupon().id() == coupon.id()) {
            return cart.toDto(now);
        }

        cartRepository.setCoupon(cart.id(), coupon.id(), now);
        CartSnapshot updated = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));
        return updated.toDto(now);
    }

    private static String normalizeCouponCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("code не указан");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!COUPON_CODE_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException("code не соответствует [A-Z0-9_-]{1,32}");
        }
        return normalized;
    }
}
