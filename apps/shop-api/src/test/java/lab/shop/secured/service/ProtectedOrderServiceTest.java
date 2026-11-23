package lab.shop.secured.service;

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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
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
class ProtectedOrderServiceTest {

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

    private CheckoutProperties properties;
    private CheckoutCrypto crypto;
    private JsonMapper jsonMapper;
    private ProtectedOrderService service;

    @BeforeEach
    void setUp() {
        properties = new CheckoutProperties("test-idempotency-secret-at-least-32-bytes", Duration.ofSeconds(2));
        crypto = new CheckoutCrypto(properties);
        jsonMapper = JsonMapper.builder().build();
        service = new ProtectedOrderService(
                orderRepository,
                cartRepository,
                couponRepository,
                crypto,
                properties,
                jsonMapper,
                Clock.fixed(T0, ZoneOffset.UTC));
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getOrder возвращает собственный заказ")
    void getOrderReturnsOwnOrder() {
        Order expected = sampleOrder(USER_ID);
        when(orderRepository.findResponse(ORDER_ID))
                .thenReturn(Optional.of(new OrderResponse(USER_ID, jsonMapper.writeValueAsString(expected))));

        Order actual = service.getOrder(ORDER_ID);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("BO-01: чужой и отсутствующий заказ возвращают одинаковый 404")
    void getOrderReturns404ForForeignAndMissingOrder() {
        when(orderRepository.findResponse(ORDER_ID))
                .thenReturn(Optional.of(new OrderResponse(OTHER_USER_ID, jsonMapper.writeValueAsString(sampleOrder(OTHER_USER_ID)))));
        when(orderRepository.findResponse(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(ORDER_ID)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getOrder(999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Успешный checkout с купоном соблюдает порядок блокировок key -> cart -> coupon, сохраняет заказ и очищает корзину")
    void checkoutWithCouponFollowsLockOrderAndClearsCart() {
        Coupon coupon = new Coupon(7L, "SAVE10", 10, T0.plusSeconds(3600), 5, 0);
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 2L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 2, 1000, true)), coupon);
        byte[] keyHash = crypto.idempotencyKeyHash(IDEMPOTENCY_KEY);
        long lockId = crypto.advisoryLockId(USER_ID, keyHash);

        when(orderRepository.findByIdempotencyKey(USER_ID, keyHash)).thenReturn(Optional.empty());
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(orderRepository.existsForRevision(CART_ID, 2L)).thenReturn(false);
        when(couponRepository.tryConsume(7L, T0)).thenReturn(1);
        when(orderRepository.nextId()).thenReturn(ORDER_ID);

        CheckoutResult result = service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(2L, 1800L));

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().getId()).isEqualTo(ORDER_ID);
        assertThat(result.order().getSubtotal()).isEqualTo(2000L);
        assertThat(result.order().getDiscount()).isEqualTo(200L);
        assertThat(result.order().getTotal()).isEqualTo(1800L);
        assertThat(result.order().getCoupon()).isEqualTo(new OrderCoupon("SAVE10", 10));

        InOrder inOrder = inOrder(orderRepository, cartRepository, couponRepository);
        inOrder.verify(orderRepository).lockIdempotencyKey(lockId, properties.keyLockTimeout());
        inOrder.verify(orderRepository).findByIdempotencyKey(USER_ID, keyHash);
        inOrder.verify(cartRepository).lockByUserId(USER_ID);
        inOrder.verify(orderRepository).existsForRevision(CART_ID, 2L);
        inOrder.verify(couponRepository).tryConsume(7L, T0);
        inOrder.verify(orderRepository).nextId();
        inOrder.verify(orderRepository).insert(any(NewOrder.class));
        inOrder.verify(cartRepository).clear(CART_ID, T0);

        ArgumentCaptor<NewOrder> captor = ArgumentCaptor.forClass(NewOrder.class);
        verify(orderRepository).insert(captor.capture());
        NewOrder inserted = captor.getValue();
        assertThat(inserted.idempotencyKeyHash()).containsExactly(keyHash);
        assertThat(inserted.requestFingerprint()).containsExactly(crypto.requestFingerprint(2L, 1800L));
        assertThat(jsonMapper.readValue(inserted.responseBody(), Order.class)).isEqualTo(result.order());
    }

    @Test
    @DisplayName("RP-01: повтор того же key и тела возвращает сохранённый Order с replayed=true без обращения к корзине и купону")
    void checkoutReplaysCachedOrderBeforeCartCheck() {
        Order cached = sampleOrder(USER_ID);
        byte[] keyHash = crypto.idempotencyKeyHash(IDEMPOTENCY_KEY);
        byte[] fingerprint = crypto.requestFingerprint(2L, 1800L);

        when(orderRepository.findByIdempotencyKey(USER_ID, keyHash))
                .thenReturn(Optional.of(new StoredOrder(ORDER_ID, fingerprint, jsonMapper.writeValueAsString(cached))));

        CheckoutResult result = service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(2L, 1800L));

        assertThat(result.replayed()).isTrue();
        assertThat(result.order()).isEqualTo(cached);
        verifyNoInteractions(cartRepository, couponRepository);
    }

    @Test
    @DisplayName("RP-02: тот же key с другим телом возвращает 409 CONFLICT")
    void checkoutWithSameKeyDifferentBodyThrowsConflict() {
        byte[] keyHash = crypto.idempotencyKeyHash(IDEMPOTENCY_KEY);
        byte[] otherFingerprint = crypto.requestFingerprint(2L, 1500L);

        when(orderRepository.findByIdempotencyKey(USER_ID, keyHash))
                .thenReturn(Optional.of(new StoredOrder(ORDER_ID, otherFingerprint, "{}")));

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(2L, 1800L)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(cartRepository, couponRepository);
    }

    @Test
    @DisplayName("Таймаут ожидания advisory lock возвращает 409 CONFLICT")
    void checkoutLockTimeoutThrowsConflict() {
        doThrow(new CannotAcquireLockException("locked"))
                .when(orderRepository).lockIdempotencyKey(anyLong(), any());

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(2L, 1800L)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(cartRepository, couponRepository);
    }

    @Test
    @DisplayName("RP-02: уже оформленная cartRevision с новым ключом возвращает 409 CONFLICT")
    void checkoutWithUsedRevisionThrowsConflict() {
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 2L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), null);
        when(orderRepository.findByIdempotencyKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(orderRepository.existsForRevision(CART_ID, 2L)).thenReturn(true);

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(2L, 1000L)))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).insert(any());
    }

    @Test
    @DisplayName("Устаревшая revision или пустая корзина возвращают 409 CONFLICT")
    void checkoutWithStaleRevisionOrEmptyCartThrowsConflict() {
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 3L, T0, List.of(), null);
        when(orderRepository.findByIdempotencyKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(orderRepository.existsForRevision(eq(CART_ID), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(2L, 0L)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(3L, 0L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("BL-04: несовпадающий quotedTotal возвращает 409 CONFLICT без списания купона")
    void checkoutWithWrongQuotedTotalThrowsConflict() {
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1500, true)), null);
        when(orderRepository.findByIdempotencyKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(orderRepository.existsForRevision(CART_ID, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(1L, 1L)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(couponRepository);
        verify(orderRepository, never()).insert(any());
    }

    @Test
    @DisplayName("BL-01: истёкший (включая границу now == expiresAt) или исчерпанный купон возвращает 409 CONFLICT")
    void checkoutWithUnavailableCouponThrowsConflict() {
        Coupon expired = new Coupon(7L, "OLD", 10, T0, 5, 0);
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), expired);
        when(orderRepository.findByIdempotencyKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(orderRepository.existsForRevision(CART_ID, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(1L, 900L)))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(couponRepository);
    }

    @Test
    @DisplayName("BL-03: если tryConsume вернул 0 под конкуренцией, checkout откатывается с 409 CONFLICT")
    void checkoutWhenCouponTryConsumeReturnsZeroThrowsConflict() {
        Coupon coupon = new Coupon(7L, "LAST1", 10, T0.plusSeconds(60), 1, 0);
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), coupon);
        when(orderRepository.findByIdempotencyKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(orderRepository.existsForRevision(CART_ID, 1L)).thenReturn(false);
        when(couponRepository.tryConsume(7L, T0)).thenReturn(0);

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(1L, 900L)))
                .isInstanceOf(ConflictException.class);
        verify(orderRepository, never()).insert(any());
        verify(cartRepository, never()).clear(anyLong(), any());
    }

    @Test
    @DisplayName("DataIntegrityViolationException при insert маппится в 409 CONFLICT без очистки корзины")
    void checkoutMapsInsertConstraintViolationToConflict() {
        CartSnapshot cart = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), null);
        when(orderRepository.findByIdempotencyKey(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(cart));
        when(orderRepository.existsForRevision(CART_ID, 1L)).thenReturn(false);
        when(orderRepository.nextId()).thenReturn(ORDER_ID);
        doThrow(new DataIntegrityViolationException("orders_cart_revision_once"))
                .when(orderRepository).insert(any(NewOrder.class));

        assertThatThrownBy(() -> service.checkout(IDEMPOTENCY_KEY, new CheckoutRequest(1L, 1000L)))
                .isInstanceOf(ConflictException.class);
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

    private static Order sampleOrder(long ownerId) {
        return new Order(
                ORDER_ID,
                ownerId,
                CART_ID,
                2L,
                List.of(new LineItem(1L, "Лампа", 2, 1000, 2000L)),
                new OrderCoupon("SAVE10", 10),
                2000L,
                200L,
                1800L,
                Order.CurrencyEnum.RUB,
                T0.atOffset(ZoneOffset.UTC));
    }

    private static void authenticate(long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(Long.toString(userId))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
