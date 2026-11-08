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
        @NotBlank(message = "app.jwt.secret не задан")
        @Size(min = 32, message = "app.jwt.secret должен быть не короче 32 символов")
        String secret,

        @DefaultValue("1h")
        Duration ttl
) {
}
