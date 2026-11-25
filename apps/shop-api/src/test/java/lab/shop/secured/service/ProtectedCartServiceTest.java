package lab.shop.secured.service;

import lab.contracts.shop.model.*;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.application.BusinessClock;
import lab.shop.application.model.CartItemSnapshot;
import lab.shop.application.model.CartSnapshot;
import lab.shop.application.model.Coupon;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.CouponRepository;
import lab.shop.application.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProtectedCartServiceTest {

    private static final long USER_ID = 10L;
    private static final long CART_ID = 100L;
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private CartRepository cartRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CouponRepository couponRepository;

    private ProtectedCartService service;

    @BeforeEach
    void setUp() {
        service = new ProtectedCartService(cartRepository, productRepository, couponRepository,
                new BusinessClock(Clock.fixed(T0, ZoneOffset.UTC)));
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getCart возвращает корзину текущего пользователя")
    void getCartReturnsCurrentUserCart() {
        CartSnapshot snapshot = new CartSnapshot(CART_ID, USER_ID, 2L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 2, 1500, true)), null);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(snapshot));

        Cart cart = service.getCart();

        assertThat(cart.getId()).isEqualTo(CART_ID);
        assertThat(cart.getUserId()).isEqualTo(USER_ID);
        assertThat(cart.getRevision()).isEqualTo(2L);
        assertThat(cart.getSubtotal()).isEqualTo(3000L);
        assertThat(cart.getTotal()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("getCart бросает NotFoundException, если корзины нет")
    void getCartThrowsNotFoundWhenMissing() {
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCart()).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("setCartQuantity берёт блокировку корзины, обновляет позицию и повышает revision")
    void setCartQuantityLocksCartAndBumpsRevision() {
        CartSnapshot before = new CartSnapshot(CART_ID, USER_ID, 0L, T0, List.of(), null);
        CartSnapshot after = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 3, 1000, true)), null);
        when(productRepository.existsPublished(5L)).thenReturn(true);
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(before));
        when(cartRepository.setItemQuantity(CART_ID, 5L, 3)).thenReturn(true);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(after));

        Cart result = service.setCartQuantity(5L, new SetQuantityRequest(3));

        assertThat(result.getRevision()).isEqualTo(1L);
        assertThat(result.getSubtotal()).isEqualTo(3000L);
        verify(cartRepository).lockByUserId(USER_ID);
        verify(cartRepository).bumpRevision(CART_ID, T0);
    }

    @Test
    @DisplayName("setCartQuantity при неизменном количестве не повышает revision (no-op)")
    void setCartQuantityNoOpKeepsRevision() {
        CartSnapshot existing = new CartSnapshot(CART_ID, USER_ID, 4L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 3, 1000, true)), null);
        when(productRepository.existsPublished(5L)).thenReturn(true);
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(cartRepository.setItemQuantity(CART_ID, 5L, 3)).thenReturn(false);

        Cart result = service.setCartQuantity(5L, new SetQuantityRequest(3));

        assertThat(result.getRevision()).isEqualTo(4L);
        verify(cartRepository, never()).bumpRevision(anyLong(), any());
        verify(cartRepository, never()).findByUserId(anyLong());
    }

    @Test
    @DisplayName("setCartQuantity для draft или несуществующего товара возвращает 404")
    void setCartQuantityRejectsMissingOrDraftProduct() {
        when(productRepository.existsPublished(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.setCartQuantity(99L, new SetQuantityRequest(1)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(cartRepository);
    }

    @Test
    @DisplayName("setCartQuantity без quantity отклоняется 400 до обращения к хранилищу")
    void setCartQuantityRejectsMissingQuantity() {
        assertThatThrownBy(() -> service.setCartQuantity(5L, new SetQuantityRequest(null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(cartRepository, productRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100})
    @DisplayName("quantity вне 1..99 отклоняется 400 до обращения к хранилищу")
    void setCartQuantityRejectsOutOfRangeQuantity(int quantity) {
        assertThatThrownBy(() -> service.setCartQuantity(5L, new SetQuantityRequest(quantity)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(cartRepository, productRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99})
    @DisplayName("Граничные quantity 1 и 99 принимаются и повышают revision")
    void setCartQuantityAcceptsBoundaryQuantity(int quantity) {
        CartSnapshot before = new CartSnapshot(CART_ID, USER_ID, 0L, T0, List.of(), null);
        CartSnapshot after = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", quantity, 1000, true)), null);
        when(productRepository.existsPublished(5L)).thenReturn(true);
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(before));
        when(cartRepository.setItemQuantity(CART_ID, 5L, quantity)).thenReturn(true);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(after));

        Cart result = service.setCartQuantity(5L, new SetQuantityRequest(quantity));

        assertThat(result.getItems()).singleElement()
                .extracting(LineItem::getQuantity)
                .isEqualTo(quantity);
        verify(cartRepository).bumpRevision(CART_ID, T0);
    }

    @Test
    @DisplayName("101-й различный товар отклоняется 400, но обновление существующего из 100 допускается")
    void setCartQuantityEnforces100DistinctItemsLimit() {
        List<CartItemSnapshot> hundredItems = LongStream.rangeClosed(1, 100)
                .mapToObj(id -> new CartItemSnapshot(id, "Item " + id, 1, 10, true))
                .toList();
        CartSnapshot fullCart = new CartSnapshot(CART_ID, USER_ID, 10L, T0, hundredItems, null);

        when(productRepository.existsPublished(101L)).thenReturn(true);
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(fullCart));

        assertThatThrownBy(() -> service.setCartQuantity(101L, new SetQuantityRequest(1)))
                .isInstanceOf(BadRequestException.class);
        verify(cartRepository, never()).setItemQuantity(anyLong(), anyLong(), anyInt());

        when(productRepository.existsPublished(1L)).thenReturn(true);
        when(cartRepository.setItemQuantity(CART_ID, 1L, 2)).thenReturn(true);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(fullCart));

        assertThat(service.setCartQuantity(1L, new SetQuantityRequest(2))).isNotNull();
    }

    @Test
    @DisplayName("selectCoupon нормализует код, блокирует корзину, сохраняет купон и повышает revision")
    void selectCouponNormalizesCodeAndUpdatesCart() {
        Coupon coupon = new Coupon(7L, "SAVE10", 10, T0.plusSeconds(3600), 5, 0);
        CartSnapshot before = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), null);
        CartSnapshot after = new CartSnapshot(CART_ID, USER_ID, 2L, T0, before.items(), coupon);

        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(before));
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(after));

        Cart result = service.selectCoupon(new SelectCouponRequest("  save10  "));

        assertThat(result.getRevision()).isEqualTo(2L);
        assertThat(result.getCoupon()).isEqualTo(new CartCoupon("SAVE10", 10, CartCoupon.EligibilityEnum.VALID));
        assertThat(result.getDiscount()).isEqualTo(100L);
        assertThat(result.getTotal()).isEqualTo(900L);
        verify(cartRepository).lockByUserId(USER_ID);
        verify(cartRepository).setCoupon(CART_ID, 7L, T0);
    }

    @Test
    @DisplayName("selectCoupon с тем же купоном не меняет revision (no-op)")
    void selectSameCouponIsNoOp() {
        Coupon coupon = new Coupon(7L, "SAVE10", 10, T0.plusSeconds(3600), 5, 0);
        CartSnapshot existing = new CartSnapshot(CART_ID, USER_ID, 3L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), coupon);

        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(existing));

        Cart result = service.selectCoupon(new SelectCouponRequest("SAVE10"));

        assertThat(result.getRevision()).isEqualTo(3L);
        verify(cartRepository, never()).setCoupon(anyLong(), any(), any());
    }

    @Test
    @DisplayName("selectCoupon принимает истёкший купон и возвращает eligibility EXPIRED с discount = 0")
    void selectExpiredCouponReturnsCartWithZeroDiscount() {
        Coupon expired = new Coupon(8L, "OLD20", 20, T0, 5, 0);
        CartSnapshot before = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), null);
        CartSnapshot after = new CartSnapshot(CART_ID, USER_ID, 2L, T0, before.items(), expired);

        when(couponRepository.findByCode("OLD20")).thenReturn(Optional.of(expired));
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(before));
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(after));

        Cart result = service.selectCoupon(new SelectCouponRequest("OLD20"));

        assertThat(result.getCoupon()).isEqualTo(new CartCoupon("OLD20", 20, CartCoupon.EligibilityEnum.EXPIRED));
        assertThat(result.getDiscount()).isZero();
        assertThat(result.getTotal()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("selectCoupon принимает исчерпанный купон и возвращает eligibility EXHAUSTED с discount = 0")
    void selectExhaustedCouponReturnsCartWithZeroDiscount() {
        Coupon exhausted = new Coupon(9L, "USED20", 20, T0.plusSeconds(3600), 5, 5);
        CartSnapshot before = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), null);
        CartSnapshot after = new CartSnapshot(CART_ID, USER_ID, 2L, T0, before.items(), exhausted);

        when(couponRepository.findByCode("USED20")).thenReturn(Optional.of(exhausted));
        when(cartRepository.lockByUserId(USER_ID)).thenReturn(Optional.of(before));
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(after));

        Cart result = service.selectCoupon(new SelectCouponRequest("USED20"));

        assertThat(result.getCoupon()).isEqualTo(new CartCoupon("USED20", 20, CartCoupon.EligibilityEnum.EXHAUSTED));
        assertThat(result.getDiscount()).isZero();
        assertThat(result.getTotal()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Скидка в корзине округляется вниз, а 100% купон обнуляет total")
    void getCartAppliesFloorDiscountAndFullPercentCoupon() {
        Coupon tenPercent = new Coupon(10L, "TEN", 10, T0.plusSeconds(3600), 5, 0);
        CartSnapshot withFloor = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Товар", 1, 999, true)), tenPercent);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(withFloor));

        Cart floored = service.getCart();
        assertThat(floored.getSubtotal()).isEqualTo(999L);
        assertThat(floored.getDiscount()).isEqualTo(99L);
        assertThat(floored.getTotal()).isEqualTo(900L);

        Coupon fullDiscount = new Coupon(11L, "FREE100", 100, T0.plusSeconds(3600), 5, 0);
        CartSnapshot withFull = new CartSnapshot(CART_ID, USER_ID, 2L, T0,
                List.of(new CartItemSnapshot(1L, "Товар", 1, 999, true)), fullDiscount);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(withFull));

        Cart free = service.getCart();
        assertThat(free.getDiscount()).isEqualTo(999L);
        assertThat(free.getTotal()).isZero();
    }

    @Test
    @DisplayName("selectCoupon отклоняет неизвестный код (404) и невалидный формат (400)")
    void selectCouponRejectsUnknownAndInvalidCode() {
        when(couponRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectCoupon(new SelectCouponRequest("UNKNOWN")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.selectCoupon(new SelectCouponRequest("  ")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.selectCoupon(new SelectCouponRequest("BAD CODE!")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(cartRepository);
    }

    private static void authenticate(long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(Long.toString(userId))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
