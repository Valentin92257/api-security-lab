package lab.shop.vulnerable.service;

import lab.contracts.shop.model.CheckoutRequest;
import lab.contracts.shop.model.LineItem;
import lab.contracts.shop.model.Order;
import lab.contracts.shop.model.OrderCoupon;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.application.CheckoutProperties;
import lab.shop.application.model.*;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.OrderRepository;
import lab.shop.application.service.CheckoutCrypto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VulnerableOrderServiceTest {

    private static final long USER_ID = 10L;
    private static final long OTHER_USER_ID = 20L;
    private static final long CART_ID = 100L;
    private static final long ORDER_ID = 500L;
    private static final String IDEMPOTENCY_KEY = "idem-key-1234567890";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private CouponRepository couponRepository;

    private JsonMapper jsonMapper;
    private VulnerableOrderService service;

    @BeforeEach
    void setUp() {
        CheckoutCrypto crypto = new CheckoutCrypto(
                new CheckoutProperties("test-idempotency-secret-at-least-32-bytes", Duration.ofSeconds(2)));
        jsonMapper = JsonMapper.builder().build();
        service = new VulnerableOrderService(
                orderRepository,
                cartRepository,
                couponRepository,
                crypto,
                jsonMapper,
                Clock.fixed(T0, ZoneOffset.UTC));
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("BO-01: vulnerable getOrder возвращает чужой заказ без проверки владельца")
    void getOrderReturnsForeignOrder() {
        Order foreignOrder = new Order(
                ORDER_ID,
                OTHER_USER_ID,
                200L,
                1L,
                List.of(new LineItem(1L, "Лампа", 1, 1000, 1000L)),
                null,
                1000L,
                0L,
                1000L,
                Order.CurrencyEnum.RUB,
                T0.atOffset(ZoneOffset.UTC));
        when(orderRepository.findResponse(ORDER_ID))
                .thenReturn(Optional.of(new OrderResponse(OTHER_USER_ID, jsonMapper.writeValueAsString(foreignOrder))));

        Order actual = service.getOrder(ORDER_ID);

        assertThat(actual).isEqualTo(foreignOrder);
    }

    @Test
    @DisplayName("getOrder возвращает 404, если заказ не существует")
    void getOrderReturns404WhenMissing() {
        when(orderRepository.findResponse(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("RP-01/RP-02/BL-01..04: vulnerable checkout пропускает блокировки, идемпотентность, проверку revision/quantity/coupon, доверяет quotedTotal и не очищает корзину")
    void checkoutSkipsProtectionsAndTrustsClientTotal() {
        Coupon expiredAndExhausted = new Coupon(7L, "OLD50", 50, T0.minusSeconds(60), 1, 5);
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 10L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", -1, 1000, false)), expiredAndExhausted);

        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(couponRepository.tryConsume(7L, T0)).thenReturn(1);
        when(orderRepository.nextId()).thenReturn(ORDER_ID);

        // Запрос с устаревшей cartRevision=0 и заниженным quotedTotal=1
        CheckoutResult result = service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(0L, 1L));

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().getId()).isEqualTo(ORDER_ID);
        assertThat(result.order().getCartRevision()).isZero();
        assertThat(result.order().getTotal()).isEqualTo(1L);
        assertThat(result.order().getCoupon()).isEqualTo(new OrderCoupon("OLD50", 50));

        verify(couponRepository).tryConsume(7L, T0);
        verify(orderRepository).insert(any(NewOrder.class));
        verify(orderRepository, never()).lockIdempotencyKey(anyLong(), any());
        verify(orderRepository, never()).findByIdempotencyKey(anyLong(), any());
        verify(orderRepository, never()).existsForRevision(anyLong(), anyLong());
        verify(cartRepository, never()).lockByUserId(anyLong());
        verify(cartRepository, never()).clear(anyLong(), any());
    }

    @Test
    @DisplayName("Некорректный Idempotency-Key или отрицательные поля отклоняются 400 до обращения к БД")
    void checkoutRejectsInvalidInputsBeforeDb() {
        assertThatThrownBy(() -> service.checkout("short", new CheckoutRequest(1L, 100L)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.checkout("bad key with spaces!", new CheckoutRequest(1L, 100L)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(-1L, 100L)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(1L, -1L)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(orderRepository, cartRepository, couponRepository);
    }

    @Test
    @DisplayName("Пустая корзина в vulnerable всё равно отклоняется с 409 CONFLICT")
    void checkoutEmptyCartThrowsConflict() {
        CartSnapshot empty = new CartSnapshot(CART_ID, USER_ID, 0L, T0, List.of(), null);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(empty));

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(0L, 0L)))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).insert(any());
    }

    private static void authenticate(long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(Long.toString(userId))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
