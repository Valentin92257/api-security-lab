package lab.shop.api.security;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesTest {

    private static final String VALID_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("Корректный секрет проходит валидацию")
    void validSecretHasNoViolations() {
        var props = new JwtProperties(VALID_SECRET, Duration.ofMinutes(15));

        assertThat(validator.validate(props)).isEmpty();
    }

    @ParameterizedTest(name = "Секрет \"{0}\" отклоняется")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "short"})
    @DisplayName("Пустой или короткий секрет не проходит валидацию")
    void blankOrShortSecretIsRejected(String secret) {
        var props = new JwtProperties(secret, Duration.ofMinutes(15));

        assertThat(validator.validate(props)).isNotEmpty();
    }

    @Test
    @DisplayName("Граница длины секрета: 31 символ отклоняется, 32 принимается")
    void secretLengthBoundary() {
        var tooShort = new JwtProperties("x".repeat(31), Duration.ofMinutes(15));
        var minimal = new JwtProperties("x".repeat(32), Duration.ofMinutes(15));

        assertThat(validator.validate(tooShort)).isNotEmpty();
        assertThat(validator.validate(minimal)).isEmpty();
    }

    @Test
    @DisplayName("Срок жизни токена по умолчанию равен одному часу")
    void ttlDefaultsToOneHour() {
        JwtProperties props = bind(Map.of("app.jwt.secret", VALID_SECRET));

        assertThat(props.secret()).isEqualTo(VALID_SECRET);
        assertThat(props.ttl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Срок жизни токена задаётся из конфигурации")
    void ttlIsBoundFromConfiguration() {
        JwtProperties props = bind(Map.of(
                "app.jwt.secret", VALID_SECRET,
                "app.jwt.ttl", "10m"));

        assertThat(props.ttl()).isEqualTo(Duration.ofMinutes(10));
    }

    private static JwtProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.jwt", JwtProperties.class)
                .get();
    }
}
