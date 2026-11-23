package lab.gateway.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

public final class TestJwt {
    /** Same value as lab.gateway.jwt.secret in src/test/resources/config/application.yaml. */
    public static final String SECRET = "test-gateway-jwt-secret-0123456789abcdef";
    public static final String ISSUER = "urn:lab:shop:protected";
    public static final String AUDIENCE = "shop-protected";

    public static JWTClaimsSet.Builder claims(Instant now) {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("42")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))));
    }

    public static String valid() {
        return sign(claims(Instant.now()).build());
    }

    public static String sign(JWTClaimsSet claims) {
        return sign(claims, JWSAlgorithm.HS256, SECRET);
    }

    public static String sign(JWTClaimsSet claims, JWSAlgorithm algorithm, String secret) {
        var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        try {
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException failure) {
            throw new IllegalStateException(failure);
        }
        return jwt.serialize();
    }

    private TestJwt() {
    }
}
