package lab.shop.secured.service;

import lab.contracts.shop.model.Cart;
import lab.contracts.shop.model.SelectCouponRequest;
import lab.contracts.shop.model.SetQuantityRequest;
import lab.shop.application.service.CartService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("protected")
public class ProtectedCartService implements CartService {

    @Override
    public Cart getCart() {
        return new Cart();
    }

    @Override
    public Cart setCartQuantity(Long productId, SetQuantityRequest request) {
        return new Cart();
    }

    @Override
    public Cart selectCoupon(SelectCouponRequest request) {
        return new Cart();
    }
}