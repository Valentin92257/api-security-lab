package lab.shop.api.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32) String secret,
        @NotBlank String issuer,
        @NotBlank String audience,
        @DefaultValue("15m") Duration ttl
) {

    public JwtProperties {
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofSeconds(900)) > 0) {
            throw new IllegalArgumentException("app.jwt.ttl должен быть от 1 до 900 секунд");
        }
    }
}
