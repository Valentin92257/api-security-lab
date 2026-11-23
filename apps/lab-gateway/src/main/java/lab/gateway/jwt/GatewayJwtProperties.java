package lab.gateway.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.gateway.jwt")
public record GatewayJwtProperties(String secret, String issuer, String audience, Duration clockSkew,
                                   Set<String> publicPaths) {
    public GatewayJwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT issuer and audience are required");
        }
        if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("JWT clockSkew must be between 0 and 1 minute");
        }
        publicPaths = publicPaths == null ? Set.of() : Set.copyOf(publicPaths);
        if (!publicPaths.stream().allMatch(path -> path.startsWith("/api/v1/"))) {
            throw new IllegalArgumentException("JWT publicPaths must be exact /api/v1/ paths");
        }
    }

    @Override
    public String toString() {
        return "GatewayJwtProperties[issuer=%s, audience=%s, clockSkew=%s, publicPaths=%s]"
                .formatted(issuer, audience, clockSkew, publicPaths);
    }
}
