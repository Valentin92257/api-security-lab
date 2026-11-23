package lab.gateway.jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static lab.gateway.jwt.JwtRejection.INVALID_AUDIENCE;
import static lab.gateway.jwt.JwtRejection.INVALID_AUTHORIZATION_HEADER;
import static lab.gateway.jwt.JwtRejection.INVALID_ISSUER;
import static lab.gateway.jwt.JwtRejection.INVALID_SIGNATURE;
import static lab.gateway.jwt.JwtRejection.MALFORMED_TOKEN;
import static lab.gateway.jwt.JwtRejection.MISSING_EXPIRATION;
import static lab.gateway.jwt.JwtRejection.MISSING_TOKEN;
import static lab.gateway.jwt.JwtRejection.TOKEN_EXPIRED;
import static lab.gateway.jwt.JwtRejection.TOKEN_NOT_YET_VALID;
import static lab.gateway.jwt.JwtRejection.UNSUPPORTED_ALGORITHM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final String OTHER_SECRET = "other-shop-jwt-secret-0123456789abcdef";
    private final JwtVerifier verifier = new JwtVerifier(properties(TestJwt.SECRET),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsTokenIssuedByProtectedShop() {
        String token = TestJwt.sign(TestJwt.claims(NOW).build());
        assertThat(verifier.verify(List.of("Bearer " + token))).isEmpty();
        assertThat(verifier.verify(List.of("bearer " + token))).isEmpty();
    }

    @Test
    void acceptsAudienceListAndTimestampsWithinClockSkew() {
        String token = TestJwt.sign(TestJwt.claims(NOW)
                .audience(List.of("other-service", TestJwt.AUDIENCE))
                .notBeforeTime(Date.from(NOW.plusSeconds(5)))
                .expirationTime(Date.from(NOW.minusSeconds(5)))
                .build());
        assertThat(verifier.verify(List.of("Bearer " + token))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAuthorization")
    void rejectsWithFixedReason(String description, List<String> authorization, JwtRejection expected) {
        assertThat(verifier.verify(authorization)).contains(expected);
    }

    static Stream<Arguments> invalidAuthorization() {
        String valid = TestJwt.sign(TestJwt.claims(NOW).build());
        String[] parts = valid.split("\\.");
        String foreignPayload = TestJwt.sign(TestJwt.claims(NOW).subject("1").build()).split("\\.")[1];
        return Stream.of(
                Arguments.of("no header", List.of(), MISSING_TOKEN),
                Arguments.of("two headers", List.of("Bearer " + valid, "Bearer " + valid), INVALID_AUTHORIZATION_HEADER),
                Arguments.of("basic scheme", List.of("Basic dXNlcjpwYXNz"), INVALID_AUTHORIZATION_HEADER),
                Arguments.of("empty bearer", List.of("Bearer "), INVALID_AUTHORIZATION_HEADER),
                Arguments.of("bearer with spaces", List.of("Bearer " + valid + " extra"), INVALID_AUTHORIZATION_HEADER),
                Arguments.of("not a jwt", List.of("Bearer not-a-jwt"), MALFORMED_TOKEN),
                Arguments.of("broken base64 json", List.of("Bearer e30.e30x.c2ln"), MALFORMED_TOKEN),
                Arguments.of("alg none", List.of("Bearer " + new PlainJWT(TestJwt.claims(NOW).build()).serialize()),
                        UNSUPPORTED_ALGORITHM),
                Arguments.of("hs512", List.of("Bearer " + TestJwt.sign(TestJwt.claims(NOW).build(),
                        JWSAlgorithm.HS512, TestJwt.SECRET + TestJwt.SECRET)), UNSUPPORTED_ALGORITHM),
                Arguments.of("foreign secret", List.of("Bearer " + TestJwt.sign(TestJwt.claims(NOW).build(),
                        JWSAlgorithm.HS256, OTHER_SECRET)), INVALID_SIGNATURE),
                Arguments.of("tampered payload", List.of("Bearer " + parts[0] + "." + foreignPayload + "." + parts[2]),
                        INVALID_SIGNATURE),
                Arguments.of("vulnerable shop token", List.of("Bearer " + TestJwt.sign(TestJwt.claims(NOW)
                        .issuer("urn:lab:shop:vulnerable").audience("shop-vulnerable").build(),
                        JWSAlgorithm.HS256, OTHER_SECRET)), INVALID_SIGNATURE),
                Arguments.of("no exp", bearer(TestJwt.claims(NOW).expirationTime(null)), MISSING_EXPIRATION),
                Arguments.of("expired", bearer(TestJwt.claims(NOW.minus(Duration.ofMinutes(16)))), TOKEN_EXPIRED),
                Arguments.of("expired after skew", bearer(TestJwt.claims(NOW)
                        .expirationTime(Date.from(NOW.minusSeconds(6)))), TOKEN_EXPIRED),
                Arguments.of("not yet valid", bearer(TestJwt.claims(NOW)
                        .notBeforeTime(Date.from(NOW.plusSeconds(6)))), TOKEN_NOT_YET_VALID),
                Arguments.of("foreign issuer", bearer(TestJwt.claims(NOW).issuer("urn:lab:shop:vulnerable")),
                        INVALID_ISSUER),
                Arguments.of("no issuer", bearer(TestJwt.claims(NOW).issuer(null)), INVALID_ISSUER),
                Arguments.of("foreign audience", bearer(TestJwt.claims(NOW).audience("shop-vulnerable")),
                        INVALID_AUDIENCE),
                Arguments.of("no audience", bearer(TestJwt.claims(NOW).audience((String) null)), INVALID_AUDIENCE));
    }

    @Test
    void requiresTokenEverywhereExceptExactPublicPaths() {
        assertThat(verifier.requiresToken("/api/v1/auth/login")).isFalse();
        assertThat(verifier.requiresToken("/api/v1/auth/register")).isFalse();
        assertThat(Stream.of("/api/v1", "/api/v1/users/me", "/api/v1/auth/login/", "/api/v1/auth/login;a=b",
                "/api/v1/auth/%6Cogin", "/api/v1/auth/login/../../users/me", "/api/v1/auth/register/../../orders"))
                .allMatch(verifier::requiresToken);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short-secret", "0123456789012345678901234567890"})
    void rejectsSecretsShorterThan256Bits(String secret) {
        assertThatThrownBy(() -> properties(secret)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesPropertiesWithoutExposingSecret() {
        assertThat(properties(TestJwt.SECRET).toString()).doesNotContain(TestJwt.SECRET);
        assertThatThrownBy(() -> new GatewayJwtProperties(TestJwt.SECRET, TestJwt.ISSUER, TestJwt.AUDIENCE,
                Duration.ofSeconds(5), Set.of("/internal/v1/runs"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayJwtProperties(TestJwt.SECRET, TestJwt.ISSUER, " ",
                Duration.ofSeconds(5), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayJwtProperties(TestJwt.SECRET, TestJwt.ISSUER, TestJwt.AUDIENCE,
                Duration.ofMinutes(2), Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> bearer(JWTClaimsSet.Builder claims) {
        return List.of("Bearer " + TestJwt.sign(claims.build()));
    }

    private static GatewayJwtProperties properties(String secret) {
        return new GatewayJwtProperties(secret, TestJwt.ISSUER, TestJwt.AUDIENCE, Duration.ofSeconds(5),
                Set.of("/api/v1/auth/login", "/api/v1/auth/register"));
    }
}
