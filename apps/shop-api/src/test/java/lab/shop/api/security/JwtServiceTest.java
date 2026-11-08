package lab.shop.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-0123456789";
    private static final String OTHER_SECRET = "another-secret-at-least-32-bytes-long-9876543210";
    private static final String ISSUER = "urn:lab:shop:protected";
    private static final String AUDIENCE = "shop-protected";
    private static final Duration TTL = Duration.ofMinutes(10);

    private static final JwtProperties PROPS = props(SECRET, ISSUER, AUDIENCE);

    private final JwtDecoder decoder = JwtSupport.decoder(PROPS);

    @Test
    @DisplayName("Контроль: выданный токен принимается и содержит sub, iss, aud, jti, nbf и срок из настроек")
    void validTokenIsAccepted() {
        String token = service(PROPS).issue(42L);

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getNotBefore()).isNotNull();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(TTL);
    }

    @Test
    @DisplayName("У каждого выданного токена свой jti")
    void eachTokenHasUniqueId() {
        JwtService service = service(PROPS);

        String first = decoder.decode(service.issue(1L)).getId();
        String second = decoder.decode(service.issue(1L)).getId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("Токен, подписанный другим секретом, отклоняется")
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = service(props(OTHER_SECRET, ISSUER, AUDIENCE)).issue(1L);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Токен другого target с тем же секретом, но другим issuer, отклоняется")
    void tokenWithAnotherIssuerIsRejected() {
        String token = service(props(SECRET, "urn:lab:shop:vulnerable", AUDIENCE)).issue(1L);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Токен другого target с тем же секретом, но другим audience, отклоняется")
    void tokenWithAnotherAudienceIsRejected() {
        String token = service(props(SECRET, ISSUER, "shop-vulnerable")).issue(1L);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Просроченный токен отклоняется, до истечения срока принимается")
    void expiredTokenIsRejected() {
        String token = service(PROPS).issue(1L);

        assertThat(decoderAt(Instant.now().plus(Duration.ofMinutes(5))).decode(token).getSubject()).isEqualTo("1");

        JwtDecoder decoderInTheFuture = decoderAt(Instant.now().plus(Duration.ofHours(2)));
        assertThatThrownBy(() -> decoderInTheFuture.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Токен с подменённым payload отклоняется (подпись не совпадает)")
    void tamperedPayloadIsRejected() {
        String token = service(PROPS).issue(42L);
        String[] parts = token.split("\\.");
        String forgedPayload = base64Url("{\"sub\":\"1\",\"exp\":9999999999}");

        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Токен без подписи (alg=none) отклоняется")
    void unsignedTokenIsRejected() {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"1\",\"exp\":9999999999}");

        String unsigned = header + "." + payload + ".";

        assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(JwtException.class);
    }

    private static JwtProperties props(String secret, String issuer, String audience) {
        return new JwtProperties(secret, issuer, audience, TTL);
    }

    private static JwtService service(JwtProperties props) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(JwtSupport.key(props.secret())));
        return new JwtService(encoder, props);
    }

    private static JwtDecoder decoderAt(Instant now) {
        return JwtSupport.decoder(PROPS, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}