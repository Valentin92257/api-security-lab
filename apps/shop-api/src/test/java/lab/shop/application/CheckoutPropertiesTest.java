package lab.shop.application;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutPropertiesTest {

    private static final String VALID_SECRET = "test-idempotency-secret-at-least-32-bytes";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("Корректные настройки проходят валидацию")
    void validPropertiesHaveNoViolations() {
        var props = new CheckoutProperties(VALID_SECRET, Duration.ofSeconds(2));

        assertThat(validator.validate(props)).isEmpty();
    }

    @ParameterizedTest(name = "Секрет \"{0}\" отклоняется")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "short"})
    @DisplayName("Пустой или короткий секрет не проходит валидацию")
    void blankOrShortSecretIsRejected(String secret) {
        var props = new CheckoutProperties(secret, Duration.ofSeconds(2));

        assertThat(validator.validate(props)).isNotEmpty();
    }

    @ParameterizedTest(name = "Таймаут {0} отклоняется")
    @ValueSource(longs = {0, -1})
    @DisplayName("Неположительный таймаут ожидания advisory lock отклоняется")
    void nonPositiveLockTimeoutIsRejected(long millis) {
        assertThatThrownBy(() -> new CheckoutProperties(VALID_SECRET, Duration.ofMillis(millis)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Таймаут ожидания блокировки по умолчанию равен 2 секундам")
    void lockTimeoutDefaultsToTwoSeconds() {
        CheckoutProperties props = bind(Map.of("app.checkout.idempotency-secret", VALID_SECRET));

        assertThat(props.idempotencySecret()).isEqualTo(VALID_SECRET);
        assertThat(props.keyLockTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Таймаут ожидания блокировки задаётся из конфигурации")
    void lockTimeoutIsBoundFromConfiguration() {
        CheckoutProperties props = bind(Map.of(
                "app.checkout.idempotency-secret", VALID_SECRET,
                "app.checkout.key-lock-timeout", "500ms"));

        assertThat(props.keyLockTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    private static CheckoutProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.checkout", CheckoutProperties.class)
                .get();
    }
}
