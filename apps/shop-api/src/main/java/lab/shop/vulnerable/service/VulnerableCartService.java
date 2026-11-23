package lab.shop.vulnerable.service;

import lab.contracts.shop.model.Cart;
import lab.contracts.shop.model.SelectCouponRequest;
import lab.contracts.shop.model.SetQuantityRequest;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.AuthUtils;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.ProductRepository;
import lab.shop.application.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Уязвимая реализация корзины:
 * - не берёт строчную блокировку корзины (FOR UPDATE);
 * - не проверяет границы quantity (1..99) и лимит 100 позиций (BL-02).
 */
@Service
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableCartService implements CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;
    private final Clock clock;

    @Override
    public Cart getCart() {
        long userId = AuthUtils.getUserId();
        CartSnapshot cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));
        return cart.toDto(clock.instant());
    }

    @Override
    @Transactional
    public Cart setCartQuantity(Long productId, SetQuantityRequest request) {
        if (request.getQuantity() == null) {
            throw new BadRequestException("quantity не указан");
        }
        if (!productRepository.existsPublished(productId)) {
            throw new NotFoundException("Товар не найден");
        }

        long userId = AuthUtils.getUserId();
        // Намеренная слабость: читаем корзину без строчной блокировки FOR UPDATE.
        CartSnapshot cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));

        // Намеренная слабость (BL-02): границы quantity (1..99) и лимит 100 позиций не проверяются.
        Instant now = clock.instant();
        if (cartRepository.setItemQuantity(cart.id(), productId, request.getQuantity())) {
            cartRepository.bumpRevision(cart.id(), now);
            cart = cartRepository.findByUserId(userId)
                    .orElseThrow(() -> new NotFoundException("Корзина не найдена"));
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
        // Намеренная слабость: читаем корзину без строчной блокировки FOR UPDATE.
        CartSnapshot cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));

        Instant now = clock.instant();
        if (cart.coupon() != null && cart.coupon().id() == coupon.id()) {
            return cart.toDto(now);
        }

        cartRepository.setCoupon(cart.id(), coupon.id(), now);
        CartSnapshot updated = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Корзина не найдена"));
        return updated.toDto(now);
    }

    private String normalizeCouponCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("code не указан");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
