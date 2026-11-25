package lab.shop.vulnerable.service;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VulnerableCartServiceTest {

    private static final long USER_ID = 10L;
    private static final long CART_ID = 100L;
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private CartRepository cartRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CouponRepository couponRepository;

    private VulnerableCartService service;

    @BeforeEach
    void setUp() {
        service = new VulnerableCartService(cartRepository, productRepository, couponRepository,
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
        assertThat(cart.getSubtotal()).isEqualTo(3000L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100})
    @DisplayName("BL-02: vulnerable принимает quantity = 0, отрицательные и > 99 без блокировки корзины")
    void setCartQuantityAllowsOutOfRangeWithoutLock(int quantity) {
        CartSnapshot before = new CartSnapshot(CART_ID, USER_ID, 0L, T0, List.of(), null);
        CartSnapshot after = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", quantity, 1000, true)), null);
        when(productRepository.existsPublished(5L)).thenReturn(true);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(before), Optional.of(after));
        when(cartRepository.setItemQuantity(CART_ID, 5L, quantity)).thenReturn(true);

        Cart result = service.setCartQuantity(5L, new SetQuantityRequest(quantity));

        assertThat(result.getItems()).singleElement()
                .extracting(LineItem::getQuantity)
                .isEqualTo(quantity);
        verify(cartRepository).bumpRevision(CART_ID, T0);
        verify(cartRepository, never()).lockByUserId(anyLong());
    }

    @Test
    @DisplayName("BL-02: vulnerable не ограничивает число различных позиций в корзине 100 товарами")
    void setCartQuantityDoesNotEnforce100ItemLimit() {
        List<CartItemSnapshot> hundredItems = LongStream.rangeClosed(1, 100)
                .mapToObj(id -> new CartItemSnapshot(id, "Item " + id, 1, 10, true))
                .toList();
        CartSnapshot fullCart = new CartSnapshot(CART_ID, USER_ID, 10L, T0, hundredItems, null);

        when(productRepository.existsPublished(101L)).thenReturn(true);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(fullCart));
        when(cartRepository.setItemQuantity(CART_ID, 101L, 1)).thenReturn(true);

        assertThat(service.setCartQuantity(101L, new SetQuantityRequest(1))).isNotNull();
        verify(cartRepository).setItemQuantity(CART_ID, 101L, 1);
        verify(cartRepository, never()).lockByUserId(anyLong());
    }

    @Test
    @DisplayName("setCartQuantity при неизменном количестве не повышает revision (no-op)")
    void setCartQuantityNoOpKeepsRevision() {
        CartSnapshot existing = new CartSnapshot(CART_ID, USER_ID, 4L, T0,
                List.of(new CartItemSnapshot(5L, "Лампа", 3, 1000, true)), null);
        when(productRepository.existsPublished(5L)).thenReturn(true);
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(cartRepository.setItemQuantity(CART_ID, 5L, 3)).thenReturn(false);

        Cart result = service.setCartQuantity(5L, new SetQuantityRequest(3));

        assertThat(result.getRevision()).isEqualTo(4L);
        verify(cartRepository, never()).bumpRevision(anyLong(), any());
        verify(cartRepository, never()).lockByUserId(anyLong());
    }

    @Test
    @DisplayName("setCartQuantity для отсутствующего или draft товара возвращает 404")
    void setCartQuantityRejectsMissingOrDraftProduct() {
        when(productRepository.existsPublished(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.setCartQuantity(99L, new SetQuantityRequest(1)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(cartRepository);
    }

    @Test
    @DisplayName("setCartQuantity без quantity возвращает 400, а не 500")
    void setCartQuantityRejectsMissingQuantity() {
        assertThatThrownBy(() -> service.setCartQuantity(5L, new SetQuantityRequest(null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(cartRepository, productRepository);
    }

    @Test
    @DisplayName("selectCoupon нормализует код, выбирает купон без блокировки корзины и повышает revision")
    void selectCouponUpdatesCartWithoutLock() {
        Coupon coupon = new Coupon(7L, "SAVE10", 10, T0.plusSeconds(3600), 5, 0);
        CartSnapshot before = new CartSnapshot(CART_ID, USER_ID, 1L, T0,
                List.of(new CartItemSnapshot(1L, "Лампа", 1, 1000, true)), null);
        CartSnapshot after = new CartSnapshot(CART_ID, USER_ID, 2L, T0, before.items(), coupon);

        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));
        when(cartRepository.findByUserId(USER_ID)).thenReturn(Optional.of(before), Optional.of(after));

        Cart result = service.selectCoupon(new SelectCouponRequest(" save10 "));

        assertThat(result.getRevision()).isEqualTo(2L);
        assertThat(result.getCoupon()).isEqualTo(new CartCoupon("SAVE10", 10, CartCoupon.EligibilityEnum.VALID));
        verify(cartRepository).setCoupon(CART_ID, 7L, T0);
        verify(cartRepository, never()).lockByUserId(anyLong());
    }

    private static void authenticate(long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(Long.toString(userId))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
