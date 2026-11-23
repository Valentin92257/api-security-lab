package lab.gateway.jwt;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;

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

/**
 * Checks the protected Shop access token by the Shop rules: HS256 only, signature, required exp, nbf
 * with clock skew, exact issuer and audience. The token is never logged or returned.
 */
public final class JwtVerifier {
    private static final Pattern BEARER = Pattern.compile("^Bearer (?<token>[a-zA-Z0-9-._~+/]+=*)$",
            Pattern.CASE_INSENSITIVE);

    private final GatewayJwtProperties properties;
    private final JWSVerifier signature;
    private final Clock clock;

    public JwtVerifier(GatewayJwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        try {
            this.signature = new MACVerifier(properties.secret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException invalid) {
            throw new IllegalArgumentException("JWT secret is not a valid HS256 key", invalid);
        }
    }

    /** Public paths are compared with the raw request URI, so encoded or dot-segment variants still need a token. */
    public boolean requiresToken(String rawPath) {
        return !properties.publicPaths().contains(rawPath);
    }

    public Optional<JwtRejection> verify(List<String> authorization) {
        if (authorization.isEmpty()) {
            return Optional.of(MISSING_TOKEN);
        }
        var bearer = authorization.size() == 1 ? BEARER.matcher(authorization.getFirst()) : null;
        if (bearer == null || !bearer.matches()) {
            return Optional.of(INVALID_AUTHORIZATION_HEADER);
        }
        return Optional.ofNullable(check(bearer.group("token")));
    }

    private JwtRejection check(String token) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            if (!(JWTParser.parse(token) instanceof SignedJWT signed)) {
                return UNSUPPORTED_ALGORITHM;
            }
            jwt = signed;
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                return UNSUPPORTED_ALGORITHM;
            }
            if (!jwt.verify(signature)) {
                return INVALID_SIGNATURE;
            }
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException malformed) {
            return MALFORMED_TOKEN;
        } catch (JOSEException failure) {
            return INVALID_SIGNATURE;
        }
        Instant now = clock.instant();
        if (claims.getExpirationTime() == null) {
            return MISSING_EXPIRATION;
        }
        if (now.minus(properties.clockSkew()).isAfter(claims.getExpirationTime().toInstant())) {
            return TOKEN_EXPIRED;
        }
        if (claims.getNotBeforeTime() != null
                && now.plus(properties.clockSkew()).isBefore(claims.getNotBeforeTime().toInstant())) {
            return TOKEN_NOT_YET_VALID;
        }
        if (!properties.issuer().equals(claims.getIssuer())) {
            return INVALID_ISSUER;
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(properties.audience())) {
            return INVALID_AUDIENCE;
        }
        return null;
    }
}
