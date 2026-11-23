package lab.shop;

import lab.shop.application.CheckoutProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckoutProperties.class)
public class CheckoutConfiguration {
}
