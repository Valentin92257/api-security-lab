package lab.gateway.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"lab.gateway.additional-port=0", "lab.gateway.proxy.timeout=1s",
                "lab.gateway.proxy.connect-timeout=500ms", "lab.gateway.proxy.max-body-bytes=1024"})
class ProtectedProxyTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<Received> RECEIVED = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(0));
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer UPSTREAM = startUpstream();
    private final BlockingQueue<ILoggingEvent> logs = new LinkedBlockingQueue<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger("lab.access");
    private AppenderBase<ILoggingEvent> appender;
    private HttpClient client;

    @Autowired
    private TomcatServletWebServerFactory factory;

    @LocalServerPort
    private int mainPort;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("lab.gateway.proxy.upstream", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    @BeforeEach
    void prepare() {
        CALLS.set(0);
        RECEIVED.set(null);
        RELEASE.set(new CountDownLatch(0));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
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
        RELEASE.get().countDown();
        client.close();
        logger.detachAppender(appender);
        appender.stop();
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
        EXECUTOR.close();
    }

    @ParameterizedTest(name = "{0}: request reaches upstream and returns its status")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void forwardsMethodBodyQueryAndHeadersWithoutReinterpretingThem(String method) throws Exception {
        String id = UUID.randomUUID().toString();
        byte[] body = "{\"password\":\"body-secret\",\"quantity\":-1}".getBytes(StandardCharsets.UTF_8);
        var response = send(request("/api/v1/echo?q=a%2Bb&q=c&token=query-secret", id)
                .header("Authorization", "Bearer token-secret")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "checkout-key-12345")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(body);
        assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(response.headers().allValues("Set-Cookie")).hasSize(2);
        assertThat(response.headers().firstValue("X-Upstream-Hop")).isEmpty();
        Received received = RECEIVED.get();
        assertThat(received.method()).isEqualTo(method);
        assertThat(received.uri()).isEqualTo("/api/v1/echo?q=a%2Bb&q=c&token=query-secret");
        assertThat(received.body()).isEqualTo(body);
        assertThat(received.requestId()).isEqualTo(id);
        assertThat(received.authorization()).isEqualTo("Bearer token-secret");
        assertThat(received.idempotencyKey()).isEqualTo("checkout-key-12345");
        assertThat(CALLS.get()).isEqualTo(1);
        assertLog(id, method, "/api/v1/echo", 201);
    }

    @Test
    void forwardsFormBodiesWithoutServletFormParsing() throws Exception {
        byte[] body = "password=test%2Bvalue&quantity=-1".getBytes(StandardCharsets.UTF_8);
        var response = send(request("/api/v1/echo", UUID.randomUUID().toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(RECEIVED.get().body()).isEqualTo(body);
        assertThat(response.body()).isEqualTo(body);
    }

    @Test
    void returnsShopErrorsAndSignificantHeadersUnchanged() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(request("/api/v1/limited", id).GET().build());
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("shop-rejection");
        assertThat(response.headers().firstValue("Retry-After")).contains("7");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
        assertLog(id, "GET", "/api/v1/limited", 429);
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        var response = send(request("/api/v1/redirect", UUID.randomUUID().toString()).GET().build());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/echo");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void leavesHeadBodyEmpty() throws Exception {
        var response = send(request("/api/v1/echo", UUID.randomUUID().toString())
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Length")).contains("42");
        assertThat(RECEIVED.get().method()).isEqualTo("HEAD");
    }

    @Test
    void requiresOneCanonicalUuidBeforeCallingUpstream() throws Exception {
        for (String invalid : List.of("", "not-a-uuid", "1-1-1-1-1")) {
            var builder = request("/api/v1/echo", null);
            if (!invalid.isEmpty()) {
                builder.header("X-Request-Id", invalid);
            }
            var response = send(builder.GET().build());
            assertThat(response.statusCode()).isEqualTo(400);
            String id = response.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains(id, "INVALID_REQUEST");
            assertLog(id, "GET", "/api/v1/echo", 400);
        }
        var duplicate = send(request("/api/v1/echo", UUID.randomUUID().toString())
                .header("X-Request-Id", UUID.randomUUID().toString()).GET().build());
        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void doesNotRouteProtectedRequestsOnTheVulnerableListener() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mainPort + "/api/v1/echo"))
                .header("X-Request-Id", id).GET().build());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(CALLS.get()).isZero();
        assertLog(id, "GET", "/api/v1/echo", 404);
    }

    @Test
    void doesNotProxyManagementPaths() throws Exception {
        var response = send(request("/internal/v1/runs", UUID.randomUUID().toString()).GET().build());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void doesNotRetryPostWhenUpstreamDisconnects() throws Exception {
        String id = UUID.randomUUID().toString();
        var response = send(request("/api/v1/drop", id)
                .header("Idempotency-Key", "checkout-key-12345")
                .POST(HttpRequest.BodyPublishers.ofString("checkout")).build());
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains(id, "UNAVAILABLE");
        assertThat(CALLS.get()).isEqualTo(1);
        assertLog(id, "POST", "/api/v1/drop", 502);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/slow", "/api/v1/slow-body"})
    void boundsWaitingForHeadersAndResponseBody(String path) throws Exception {
        RELEASE.set(new CountDownLatch(1));
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();
        var response = send(request(path, id).POST(HttpRequest.BodyPublishers.ofString("checkout")).build());
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(response.headers().firstValue("X-Request-Id")).contains(id);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains(id, "UNAVAILABLE");
        assertThat(CALLS.get()).isEqualTo(1);
        assertLog(id, "POST", path, 504);
    }

    @Test
    void boundsBufferedBodies() throws Exception {
        var requestTooLarge = send(request("/api/v1/echo", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1025])).build());
        assertThat(requestTooLarge.statusCode()).isEqualTo(413);
        assertThat(CALLS.get()).isZero();
        var responseTooLarge = send(request("/api/v1/large", UUID.randomUUID().toString()).GET().build());
        assertThat(responseTooLarge.statusCode()).isEqualTo(502);
    }

    private HttpRequest.Builder request(String path, String id) {
        int port = factory.getAdditionalConnectors().getFirst().getLocalPort();
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        return id == null ? builder : builder.header("X-Request-Id", id);
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private void assertLog(String id, String method, String path, int status) throws Exception {
        ILoggingEvent event = logs.poll(2, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.getMDCPropertyMap()).containsEntry("trace_id", id);
        assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("service");
            assertThat(pair.value).isEqualTo("lab-gateway");
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("status");
            assertThat(pair.value).isEqualTo(status);
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("method");
            assertThat(pair.value).isEqualTo(method);
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("path");
            assertThat(pair.value).isEqualTo(path);
        }).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("duration_ms");
            assertThat((Double) pair.value).isGreaterThanOrEqualTo(0);
        });
        assertThat(event.getFormattedMessage() + event.getKeyValuePairs() + event.getMDCPropertyMap())
                .doesNotContain("body-secret", "token-secret", "query-secret");
    }

    private static HttpServer startUpstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> {
                CALLS.incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                var headers = exchange.getRequestHeaders();
                RECEIVED.set(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body,
                        headers.getFirst("X-Request-Id"), headers.getFirst("Authorization"),
                        headers.getFirst("Idempotency-Key")));
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/drop")) {
                        return;
                    }
                    if (path.endsWith("/slow") || path.endsWith("/slow-body")) {
                        if (path.endsWith("/slow-body")) {
                            exchange.sendResponseHeaders(200, 100);
                            exchange.getResponseBody().write(1);
                            exchange.getResponseBody().flush();
                        }
                        try {
                            RELEASE.get().await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return;
                    }
                    int status = 201;
                    var response = exchange.getResponseHeaders();
                    response.set("Content-Type", "application/json");
                    response.set("X-Request-Id", "upstream-cannot-replace-id");
                    response.set("Idempotency-Replayed", "true");
                    response.set("Connection", "X-Upstream-Hop");
                    response.set("X-Upstream-Hop", "must-not-be-forwarded");
                    response.add("Set-Cookie", "one=1");
                    response.add("Set-Cookie", "two=2");
                    if (path.endsWith("/limited")) {
                        status = 429;
                        body = "shop-rejection".getBytes(StandardCharsets.UTF_8);
                        response.set("Retry-After", "7");
                        response.set("WWW-Authenticate", "Bearer");
                    } else if (path.endsWith("/redirect")) {
                        status = 302;
                        response.set("Location", "/api/v1/echo");
                    } else if (path.endsWith("/large")) {
                        body = new byte[1025];
                    }
                    if (exchange.getRequestMethod().equals("HEAD")) {
                        response.set("Content-Length", "42");
                        exchange.sendResponseHeaders(status, -1);
                    } else {
                        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                        exchange.getResponseBody().write(body);
                    }
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Received(String method, String uri, byte[] body, String requestId,
                            String authorization, String idempotencyKey) {
    }
}
