package lab.shop.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("protected")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RequestLoggingTest.ProbeController.class)
class RequestLoggingTest {
    @LocalServerPort
    private int port;
    private final BlockingQueue<ILoggingEvent> logs = new LinkedBlockingQueue<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger("lab.access");
    private AppenderBase<ILoggingEvent> appender;
    private HttpClient client;

    @BeforeEach
    void prepare() {
        client = HttpClient.newHttpClient();
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                logs.add(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        client.close();
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsAndReturnsTheIdForwardedByGatewayWithoutSecrets() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = client.send(request("/api/v1/probe?password=query-secret")
                .header("X-Request-Id", id)
                .header("Authorization", "Bearer token-secret")
                .POST(HttpRequest.BodyPublishers.ofString("password=body-secret"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
        assertLog(id, "/api/v1/probe", 201);
    }

    @Test
    void logsTheFinalStatusOfUnhandledApplicationFailures() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = client.send(request("/api/v1/failure").header("X-Request-Id", id).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
        assertThat(response.body()).contains(id, "INTERNAL_ERROR").doesNotContain("password", "Exception");
        assertLog(id, "/api/v1/failure", 500);
    }

    @Test
    void logsMissingIdAs400WithOneServerGeneratedId() throws Exception {
        var response = client.send(request("/api/v1/probe").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(response.body()).contains(id, "INVALID_REQUEST");
        assertLog(id, "/api/v1/probe", 400);
    }

    @Test
    void healthDoesNotRequireIdOrEmitBusinessAccessLogs() throws Exception {
        var response = client.send(request("/health").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(logs).isEmpty();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
    }

    private void assertLog(String id, String path, int status) throws Exception {
        var event = logs.poll(2, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.getMDCPropertyMap()).containsEntry("trace_id", id);
        assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("service");
            assertThat(pair.value).isEqualTo("shop-api");
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("path");
            assertThat(pair.value).isEqualTo(path);
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("status");
            assertThat(pair.value).isEqualTo(status);
        });
        assertThat(event.getFormattedMessage() + event.getKeyValuePairs() + event.getMDCPropertyMap())
                .doesNotContain("query-secret", "token-secret", "body-secret", "password-secret");
        assertThat(logs).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class ProbeController {
        @PostMapping("/api/v1/probe")
        ResponseEntity<String> probe(@RequestBody byte[] body) {
            return ResponseEntity.status(201).body("ok");
        }

        @GetMapping("/api/v1/failure")
        String failure() {
            throw new IllegalStateException("password-secret");
        }
    }
}
