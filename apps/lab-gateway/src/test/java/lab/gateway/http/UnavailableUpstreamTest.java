package lab.gateway.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"lab.gateway.additional-port=0", "lab.gateway.proxy.timeout=2s",
                "lab.gateway.proxy.connect-timeout=500ms"})
class UnavailableUpstreamTest {
    private static final int CLOSED_PORT = closedPort();

    @Autowired
    private TomcatServletWebServerFactory factory;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("lab.gateway.proxy.upstream", () -> "http://127.0.0.1:" + CLOSED_PORT);
    }

    @Test
    void returnsCorrelatedProblemWhenConnectionIsRefused() throws Exception {
        String id = UUID.randomUUID().toString();
        int port = factory.getAdditionalConnectors().getFirst().getLocalPort();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/orders"))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Request-Id", id)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("application/problem+json"));
            assertThat(response.body()).contains(id, "UNAVAILABLE").doesNotContain("127.0.0.1", "Exception");
        }
    }

    private static int closedPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
