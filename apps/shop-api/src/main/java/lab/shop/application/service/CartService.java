package lab.shop.application.service;

import lab.contracts.shop.model.Cart;
import lab.contracts.shop.model.SelectCouponRequest;
import lab.contracts.shop.model.SetQuantityRequest;

public interface CartService {

    Cart getCart();

    Cart setCartQuantity(Long productId, SetQuantityRequest request);

    Cart selectCoupon(SelectCouponRequest request);
}
