package lab.shop.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.checkout")
public record CheckoutProperties(
        @NotBlank @Size(min = 32) String idempotencySecret,
        @DefaultValue("2s") Duration keyLockTimeout
) {

    public CheckoutProperties {
        if (keyLockTimeout.isZero() || keyLockTimeout.isNegative()) {
            throw new IllegalArgumentException("app.checkout.key-lock-timeout должен быть положительным");
        }
    }
}
