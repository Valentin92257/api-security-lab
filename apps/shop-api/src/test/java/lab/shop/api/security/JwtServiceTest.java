package lab.shop.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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

    private final JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(SECRET)).build();

    @Test
    @DisplayName("В sub попадает id пользователя")
    void issuePutsUserIdIntoSubject() {
        String token = service(SECRET, Duration.ofHours(1)).issue(42L);

        assertThat(decoder.decode(token).getSubject()).isEqualTo("42");
    }

    @Test
    @DisplayName("Токен подписан алгоритмом HS256")
    void issueSignsWithHs256() {
        String token = service(SECRET, Duration.ofHours(1)).issue(1L);

        assertThat(decoder.decode(token).getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    @DisplayName("Токен, подписанный другим секретом, отклоняется")
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = service(OTHER_SECRET, Duration.ofHours(1)).issue(1L);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Просроченный токен отклоняется")
    void expiredTokenIsRejected() {
        String token = service(SECRET, Duration.ofHours(1)).issue(1L);

        JwtDecoder decoderInTheFuture = decoderAt(Instant.now().plus(Duration.ofHours(2)));

        assertThatThrownBy(() -> decoderInTheFuture.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("До истечения срока токен принимается")
    void tokenIsAcceptedBeforeExpiration() {
        String token = service(SECRET, Duration.ofHours(1)).issue(1L);

        JwtDecoder decoderInTheFuture = decoderAt(Instant.now().plus(Duration.ofMinutes(30)));

        assertThat(decoderInTheFuture.decode(token).getSubject()).isEqualTo("1");
    }

    @Test
    @DisplayName("Токен с подменённым payload отклоняется (подпись не совпадает)")
    void tamperedPayloadIsRejected() {
        String token = service(SECRET, Duration.ofHours(1)).issue(42L);
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

    private static JwtService service(String secret, Duration ttl) {
        return new JwtService(encoder(secret), new JwtProperties(secret, ttl));
    }

    private static JwtEncoder encoder(String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }

    private static JwtDecoder decoderAt(Instant now) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(SECRET)).build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(Clock.fixed(now, ZoneOffset.UTC));
        decoder.setJwtValidator(timestampValidator);
        return decoder;
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
