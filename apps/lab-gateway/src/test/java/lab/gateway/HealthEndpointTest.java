package lab.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "lab.gateway.additional-port=0")
class HealthEndpointTest {
    @LocalServerPort
    private int port;

    @Autowired
    private TomcatServletWebServerFactory factory;

    @Test
    void exposesHealthOnRootPath() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
    }
    @Test
    void exposesHealthOnAdditionalListener() throws Exception {
        assertThat(factory.getAdditionalConnectors()).hasSize(1);
        for (var connector : factory.getAdditionalConnectors()) {
            assertThat(connector.getLocalPort()).isPositive().isNotEqualTo(port);
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"status\":\"UP\"");
            }
        }
    }
}
