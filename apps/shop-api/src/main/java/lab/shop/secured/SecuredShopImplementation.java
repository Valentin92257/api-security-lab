package lab.shop.secured;

import lab.shop.application.ShopImplementation;
import lab.shop.application.ShopMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Startup marker for this variant. Business operations are not implemented yet. */
@Component
@Profile("protected")
public final class SecuredShopImplementation implements ShopImplementation {
    @Override
    public ShopMode mode() {
        return ShopMode.PROTECTED;
    }
}
