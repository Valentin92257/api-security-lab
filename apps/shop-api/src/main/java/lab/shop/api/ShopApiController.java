package lab.shop.api;

import lab.contracts.shop.api.DefaultApi;
import lab.contracts.shop.model.*;
import lab.shop.application.service.*;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ShopApiController implements DefaultApi {

    private final AuthService authService;
    private final UserService userService;
    private final ProductService productService;
    private final CartService cartService;
    private final OrderService orderService;

    @Override
    public ResponseEntity<LoginResult> loginUser(UUID xRequestId, LoginRequest loginRequest, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(authService.login(loginRequest));
    }

    @Override
    public ResponseEntity<User> registerUser(UUID xRequestId, RegisterRequest registerRequest, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(authService.register(registerRequest));
    }

    @Override
    public ResponseEntity<User> getUser(UUID xRequestId, Long userId, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(userService.getUser(userId));
    }

    @Override
    public ResponseEntity<List<Product>> searchProducts(UUID xRequestId, String q, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(productService.searchProducts(q));
    }

    @Override
    public ResponseEntity<Cart> getCart(UUID xRequestId, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(cartService.getCart());
    }

    @Override
    public ResponseEntity<Cart> setCartQuantity(UUID xRequestId, Long productId, SetQuantityRequest setQuantityRequest, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(cartService.setCartQuantity(productId, setQuantityRequest));
    }

    @Override
    public ResponseEntity<Cart> selectCartCoupon(UUID xRequestId, SelectCouponRequest selectCouponRequest, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(cartService.selectCoupon(selectCouponRequest));
    }

    @Override
    public ResponseEntity<Order> checkoutOrder(String idempotencyKey, UUID xRequestId, CheckoutRequest checkoutRequest, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(orderService.checkout(idempotencyKey, checkoutRequest));
    }

    @Override
    public ResponseEntity<Order> getOrder(UUID xRequestId, Long orderId, @Nullable String xForwardedFor) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }
}