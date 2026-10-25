package lab.shop;

import java.util.Arrays;

import lab.shop.application.ShopImplementation;
import lab.shop.application.ShopMode;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Composition is fixed at startup and cannot be selected by an HTTP request. */
@Configuration(proxyBeanMethods = false)
public class ShopProfileConfiguration {
    @Bean
    InitializingBean validateShopProfile(Environment environment, ObjectProvider<ShopImplementation> provider) {
        return () -> {
            var profiles = Arrays.asList(environment.getActiveProfiles());
            boolean secured = profiles.contains("protected");
            boolean vulnerable = profiles.contains("vulnerable");
            if (secured == vulnerable) {
                throw new IllegalStateException("Activate exactly one shop profile: protected or vulnerable");
            }
            var expected = secured ? ShopMode.PROTECTED : ShopMode.VULNERABLE;
            var implementations = provider.stream().toList();
            if (implementations.size() != 1 || implementations.getFirst().mode() != expected) {
                throw new IllegalStateException("Shop profile must select exactly one matching implementation");
            }
        };
    }
}
