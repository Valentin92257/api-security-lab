package lab.gateway.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyHeadersTest {
    @Test
    void excludesConnectionScopedHeadersButPreservesApplicationHeaders() {
        assertThat(ProxyHeaders.excluded(List.of("X-First, Keep-Alive", "x-SECOND")))
                .contains("x-first", "x-second", "connection", "keep-alive", "transfer-encoding", "content-length")
                .doesNotContain("authorization", "content-type", "idempotency-key", "retry-after", "location");
    }
}
