package lab.control.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.FixtureGenerator;
import lab.control.fixture.model.FixtureManifest;
import lab.control.fixture.model.FixtureProduct;
import lab.control.fixture.model.FixtureUser;
import lab.control.fixture.model.FixtureUserRole;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BenignUserHttpTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-25T12:00:00Z");

    private final ContractJsonCodec codec = new ContractJsonCodec();
    private final FixtureManifest manifest = new FixtureGenerator(codec, 26, 200, 3).generate(42, BASE_TIME);
    private final List<FixtureUser> benignUsers = manifest.usersByRole(FixtureUserRole.CS_BENIGN);
    private final List<FixtureProduct> publishedProducts = manifest.products().stream()
            .filter(FixtureProduct::published).toList();
    private final List<ObservedRequest> observed = Collections.synchronizedList(new ArrayList<>());

    @ParameterizedTest
    @EnumSource(GatewayTarget.class)
    @Timeout(30)
    void executesBenignSessionsFromFixturesThroughScenarioRunnerAndSelectedGateway(GatewayTarget target) throws Exception {
        int expectedPort = target == GatewayTarget.VULNERABLE ? 8081 : 8082;
        List<BenignSession> sessions = buildSessionsFromManifest();
        assertThat(sessions).hasSize(3);

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::respond);
        server.start();
        try (var client = new RunnerConfiguration().runnerClient();
             var runner = new ScenarioRunner(Duration.ofSeconds(30))) {
            var observedPorts = Collections.synchronizedList(new ArrayList<Integer>());
            var transport = mock(HttpClient.class);
            when(transport.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(invocation -> {
                HttpRequest original = invocation.getArgument(0);
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                observedPorts.add(original.uri().getPort());
                String pathAndQuery = original.uri().getRawQuery() == null
                        ? original.uri().getRawPath()
                        : original.uri().getRawPath() + "?" + original.uri().getRawQuery();
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                + server.getAddress().getPort() + pathAndQuery))
                        .timeout(original.timeout().orElseThrow())
                        .method(original.method(), original.bodyPublisher().orElseThrow());
                original.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                return client.sendAsync(builder.build(), handler);
            });

            var validator = new ShopContractValidator(codec);
            List<StepResult> saved = Collections.synchronizedList(new ArrayList<>());
            var scenario = new BenignUserScenario(
                    new StepExecutor(transport, saved::add, validator),
                    new ShopRequests(validator, codec));

            UUID trialId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            var evidenceRef = new AtomicReference<List<BenignUserScenario.SessionEvidence>>();

            var handle = runner.start(trialId, runId,
                    run -> evidenceRef.set(scenario.run(run, target, sessions)));
            RunResult completed = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertThat(completed.outcome()).isEqualTo(RunOutcome.COMPLETED);
            assertThat(completed.trialId()).isEqualTo(trialId);
            assertThat(completed.runId()).isEqualTo(runId);
            assertThat(completed.startedSteps()).isEqualTo(9);
            assertThat(completed.steps()).hasSize(9).containsExactlyElementsOf(saved);
            assertThat(saved).allSatisfy(step -> {
                assertThat(step.trialId()).isEqualTo(trialId);
                assertThat(step.runId()).isEqualTo(runId);
                assertThat(step.target()).isEqualTo(target);
                assertThat(step.outcome()).isEqualTo(StepOutcome.RESPONSE);
                assertThat(step.status()).isEqualTo(200);
            });
            assertThat(saved).extracting(StepResult::requestId).doesNotHaveDuplicates();
            assertThat(observedPorts).hasSize(9).containsOnly(expectedPort);

            List<BenignUserScenario.SessionEvidence> evidence = evidenceRef.get();
            assertThat(evidence).hasSize(3);
            for (int i = 0; i < sessions.size(); i++) {
                assertThat(evidence.get(i).session()).isEqualTo(sessions.get(i));
                assertThat(evidence.get(i).steps()).hasSize(3);
            }

            assertThat(observed).hasSize(9);
            assertThat(observed).extracting(ObservedRequest::requestId)
                    .containsExactlyElementsOf(saved.stream().map(step -> step.requestId().toString()).toList());
            assertThat(observed).extracting(ObservedRequest::sourceIp)
                    .containsExactly(
                            "198.51.100.10", "198.51.100.10", "198.51.100.10",
                            "198.51.100.11", "198.51.100.11", "198.51.100.11",
                            "198.51.100.12", "198.51.100.12", "198.51.100.12")
                    .doesNotContain("192.0.2.1");
            assertThat(observed.get(1).rawQuery()).isNotNull().startsWith("q=");
            assertThat(observed.get(4).rawQuery()).isNotNull().startsWith("q=");
            assertThat(observed.get(7).rawQuery()).isNull();

            for (FixtureUser user : benignUsers) {
                assertThat(saved.toString()).doesNotContain(user.password(), token(user));
                assertThat(completed.toString()).doesNotContain(user.password(), token(user));
                assertThat(evidence.toString()).doesNotContain(user.password(), token(user));
            }
        } finally {
            server.stop(0);
        }
    }

    private List<BenignSession> buildSessionsFromManifest() {
        List<BenignSession> sessions = new ArrayList<>(benignUsers.size());
        for (int i = 0; i < benignUsers.size(); i++) {
            FixtureUser user = benignUsers.get(i);
            FixtureProduct product = publishedProducts.get(i % publishedProducts.size());
            String query = switch (i) {
                case 0 -> product.name().split("\\s+")[0];
                case 1 -> product.name();
                default -> null;
            };
            sessions.add(BenignSession.shopper(
                    "198.51.100." + (10 + i), user.login(), user.password(), query, product.id(), i + 1));
        }
        return sessions;
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            observed.add(new ObservedRequest(exchange.getRequestMethod(), path, rawQuery, body, authorization,
                    exchange.getRequestHeaders().getFirst("X-Forwarded-For"), requestId));
            exchange.getResponseHeaders().set("X-Request-Id", requestId);

            if (path.equals("/api/v1/auth/login") && exchange.getRequestMethod().equals("POST")) {
                handleLogin(exchange, body, requestId);
                return;
            }
            var user = benignUsers.stream()
                    .filter(candidate -> ("Bearer " + token(candidate)).equals(authorization))
                    .findFirst();
            if (user.isEmpty()) {
                writeProblem(exchange, 401, "UNAUTHORIZED", requestId);
                return;
            }
            if (path.equals("/api/v1/products") && exchange.getRequestMethod().equals("GET")) {
                handleProducts(exchange, rawQuery);
                return;
            }
            if (path.startsWith("/api/v1/cart/items/") && exchange.getRequestMethod().equals("PUT")) {
                handleCartItem(exchange, user.get(), path, body, requestId);
                return;
            }
            writeProblem(exchange, 404, "NOT_FOUND", requestId);
        }
    }

    private void handleLogin(HttpExchange exchange, String body, String requestId) throws IOException {
        JsonNode credentials = codec.jsonMapper().readTree(body);
        var user = benignUsers.stream()
                .filter(candidate -> candidate.login().equals(credentials.path("login").asString())
                        && candidate.password().equals(credentials.path("password").asString()))
                .findFirst();
        if (user.isPresent()) {
            write(exchange, 200, "application/json", codec.jsonMapper().createObjectNode()
                    .put("accessToken", token(user.get()))
                    .put("tokenType", "Bearer")
                    .put("expiresIn", 900));
            return;
        }
        writeProblem(exchange, 401, "UNAUTHORIZED", requestId);
    }

    private void handleProducts(HttpExchange exchange, String rawQuery) throws IOException {
        String filter = rawQuery != null && rawQuery.startsWith("q=")
                ? URLDecoder.decode(rawQuery.substring(2), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)
                : "";
        ArrayNode items = codec.jsonMapper().createArrayNode();
        for (FixtureProduct product : publishedProducts) {
            if (filter.isEmpty()
                    || product.name().toLowerCase(Locale.ROOT).contains(filter)
                    || product.description().toLowerCase(Locale.ROOT).contains(filter)) {
                items.add(codec.jsonMapper().createObjectNode()
                        .put("id", product.id())
                        .put("name", product.name())
                        .put("description", product.description())
                        .put("price", product.price())
                        .put("currency", "RUB"));
            }
        }
        write(exchange, 200, "application/json", items);
    }

    private void handleCartItem(HttpExchange exchange, FixtureUser user, String path,
                                String body, String requestId) throws IOException {
        long productId = Long.parseLong(path.substring("/api/v1/cart/items/".length()));
        var product = publishedProducts.stream().filter(p -> p.id() == productId).findFirst();
        if (product.isEmpty()) {
            writeProblem(exchange, 404, "NOT_FOUND", requestId);
            return;
        }
        int quantity = codec.jsonMapper().readTree(body).get("quantity").intValue();
        long lineTotal = product.get().price() * quantity;
        var item = codec.jsonMapper().createObjectNode()
                .put("productId", product.get().id())
                .put("name", product.get().name())
                .put("quantity", quantity)
                .put("unitPrice", product.get().price())
                .put("lineTotal", lineTotal);
        var cart = codec.jsonMapper().createObjectNode()
                .put("id", user.id())
                .put("userId", user.id())
                .put("revision", 1)
                .putNull("coupon")
                .put("subtotal", lineTotal)
                .put("discount", 0)
                .put("total", lineTotal)
                .put("currency", "RUB")
                .put("updatedAt", BASE_TIME.toString());
        cart.putArray("items").add(item);
        write(exchange, 200, "application/json", cart);
    }

    private void writeProblem(HttpExchange exchange, int status, String code, String requestId) throws IOException {
        write(exchange, status, "application/problem+json", codec.jsonMapper().createObjectNode()
                .put("type", "urn:lab:problem:http-error")
                .put("title", code)
                .put("status", status)
                .put("code", code)
                .put("requestId", requestId));
    }

    private void write(HttpExchange exchange, int status, String contentType, JsonNode body) throws IOException {
        byte[] bytes = codec.jsonMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String token(FixtureUser user) {
        return "fixture-benign-token-" + user.id();
    }

    private record ObservedRequest(String method, String path, String rawQuery, String body,
                                   String authorization, String sourceIp, String requestId) {
    }
}
