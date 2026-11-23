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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    private static final String VALID_SECRET = "test-secret-at-least-32-bytes-long-0123456789";
    private static final String ISSUER = "urn:lab:shop:test";
    private static final String AUDIENCE = "shop-test";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("Корректные настройки проходят валидацию")
    void validPropertiesHaveNoViolations() {
        var props = new JwtProperties(VALID_SECRET, ISSUER, AUDIENCE, TTL);

        assertThat(validator.validate(props)).isEmpty();
    }

    @ParameterizedTest(name = "Секрет \"{0}\" отклоняется")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "short"})
    @DisplayName("Пустой или короткий секрет не проходит валидацию")
    void blankOrShortSecretIsRejected(String secret) {
        var props = new JwtProperties(secret, ISSUER, AUDIENCE, TTL);

        assertThat(validator.validate(props)).isNotEmpty();
    }

    @Test
    @DisplayName("Граница длины секрета: 31 символ отклоняется, 32 принимается")
    void secretLengthBoundary() {
        var tooShort = new JwtProperties("x".repeat(31), ISSUER, AUDIENCE, TTL);
        var minimal = new JwtProperties("x".repeat(32), ISSUER, AUDIENCE, TTL);

        assertThat(validator.validate(tooShort)).isNotEmpty();
        assertThat(validator.validate(minimal)).isEmpty();
    }

    @ParameterizedTest(name = "issuer \"{0}\" отклоняется")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("Пустой issuer не проходит валидацию")
    void blankIssuerIsRejected(String issuer) {
        var props = new JwtProperties(VALID_SECRET, issuer, AUDIENCE, TTL);

        assertThat(validator.validate(props)).isNotEmpty();
    }

    @ParameterizedTest(name = "audience \"{0}\" отклоняется")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("Пустой audience не проходит валидацию")
    void blankAudienceIsRejected(String audience) {
        var props = new JwtProperties(VALID_SECRET, ISSUER, audience, TTL);

        assertThat(validator.validate(props)).isNotEmpty();
    }

    @ParameterizedTest(name = "ttl {0} с отклоняется")
    @ValueSource(longs = {0, -1, 901})
    @DisplayName("Срок жизни вне 1…900 секунд отклоняется (ограничение expiresIn в контракте)")
    void ttlOutsideContractBoundsIsRejected(long seconds) {
        assertThatThrownBy(() -> new JwtProperties(VALID_SECRET, ISSUER, AUDIENCE, Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "ttl {0} с принимается")
    @ValueSource(longs = {1, 900})
    @DisplayName("Границы срока жизни 1 и 900 секунд принимаются")
    void ttlBoundsAreAccepted(long seconds) {
        var props = new JwtProperties(VALID_SECRET, ISSUER, AUDIENCE, Duration.ofSeconds(seconds));

        assertThat(props.ttl()).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    @DisplayName("Срок жизни токена по умолчанию равен 15 минутам")
    void ttlDefaultsToFifteenMinutes() {
        JwtProperties props = bind(Map.of(
                "app.jwt.secret", VALID_SECRET,
                "app.jwt.issuer", ISSUER,
                "app.jwt.audience", AUDIENCE));

        assertThat(props.secret()).isEqualTo(VALID_SECRET);
        assertThat(props.issuer()).isEqualTo(ISSUER);
        assertThat(props.audience()).isEqualTo(AUDIENCE);
        assertThat(props.ttl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Срок жизни токена задаётся из конфигурации")
    void ttlIsBoundFromConfiguration() {
        JwtProperties props = bind(Map.of(
                "app.jwt.secret", VALID_SECRET,
                "app.jwt.issuer", ISSUER,
                "app.jwt.audience", AUDIENCE,
                "app.jwt.ttl", "10m"));

        assertThat(props.ttl()).isEqualTo(Duration.ofMinutes(10));
    }

    private static JwtProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.jwt", JwtProperties.class)
                .get();
    }
}