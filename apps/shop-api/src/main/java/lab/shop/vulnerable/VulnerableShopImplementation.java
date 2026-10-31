package lab.shop.vulnerable;

import lab.shop.application.ShopImplementation;
import lab.shop.application.ShopMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("vulnerable")
public final class VulnerableShopImplementation implements ShopImplementation {

    @Override
    public ShopMode mode() {
        return ShopMode.VULNERABLE;
    }
}
