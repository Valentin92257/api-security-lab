package lab.gateway.http;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.gateway.proxy")
public record GatewayProxyProperties(URI upstream, Duration connectTimeout, Duration timeout, int maxBodyBytes) {
    public GatewayProxyProperties {
        if (upstream == null || !"http".equals(upstream.getScheme()) || upstream.getHost() == null
                || upstream.getRawUserInfo() != null || upstream.getRawQuery() != null
                || upstream.getRawFragment() != null
                || !(upstream.getRawPath().isEmpty() || "/".equals(upstream.getRawPath()))) {
            throw new IllegalArgumentException("Proxy upstream must be an HTTP origin without credentials or path");
        }
        if (connectTimeout == null || timeout == null || connectTimeout.toMillis() < 1
                || timeout.toMillis() < 1 || connectTimeout.compareTo(timeout) > 0) {
            throw new IllegalArgumentException("Proxy requires positive timeouts and connectTimeout <= timeout");
        }
        if (maxBodyBytes < 1 || maxBodyBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Proxy maxBodyBytes must be between 1 and 16 MiB");
        }
    }
}
