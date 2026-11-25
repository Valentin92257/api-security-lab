package lab.control.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.FixtureGenerator;
import lab.control.fixture.model.FixtureManifest;
import lab.control.fixture.model.FixtureUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CredentialStuffingHttpTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-25T12:00:00Z");

    private final ContractJsonCodec codec = new ContractJsonCodec();
    private final FixtureManifest manifest = new FixtureGenerator(codec).generate(42, BASE_TIME);
    private final List<FixtureUser> victims = manifest.credentialStuffing().victims();
    private final List<ObservedRequest> observed = Collections.synchronizedList(new ArrayList<>());

    @Test
    @Timeout(60)
    void repeatsTheSeededHttpSequenceAndConfirmsEachSuccessfulLoginWithoutRetainingTokens() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::respond);
        server.start();
        try (var client = new RunnerConfiguration().runnerClient()) {
            var transport = mock(HttpClient.class);
            when(transport.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(invocation -> {
                HttpRequest original = invocation.getArgument(0);
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + original.uri().getRawPath()))
                        .timeout(original.timeout().orElseThrow())
                        .method(original.method(), original.bodyPublisher().orElseThrow());
                original.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                return client.sendAsync(builder.build(), handler);
            });
            var contracts = new ShopContractValidator(codec);
            List<StepResult> saved = new ArrayList<>();
            var scenario = new CredentialStuffingScenario(new StepExecutor(transport, saved::add, contracts),
                    contracts, codec);
            UUID trialId = UUID.randomUUID();
            List<CredentialStuffingScenario.LoginAttempt> firstAttempts = toAttempts(manifest);
            List<CredentialStuffingScenario.LoginAttempt> secondAttempts = toAttempts(
                    new FixtureGenerator(codec).generate(42, BASE_TIME));

            var firstRun = new RunContext(trialId, UUID.randomUUID(), Duration.ofMinutes(1));
            scenario.run(firstRun, GatewayTarget.PROTECTED, firstAttempts);
            var first = firstRun.finish().steps();
            var secondRun = new RunContext(trialId, UUID.randomUUID(), Duration.ofMinutes(1));
            scenario.run(secondRun, GatewayTarget.PROTECTED, secondAttempts);
            var second = secondRun.finish().steps();

            assertThat(first).hasSize(203);
            assertThat(second).hasSize(203);
            assertThat(saved).containsExactlyElementsOf(concat(first, second));
            for (List<StepResult> run : List.of(first, second)) {
                assertThat(run).allMatch(result -> result.outcome() == StepOutcome.RESPONSE);
                assertThat(run).filteredOn(result -> result.status() == 401).hasSize(197);
                assertThat(run).filteredOn(result -> result.status() == 200).hasSize(6);
                assertThat(run).filteredOn(result -> result.method().equals("GET"))
                        .extracting(StepResult::route).containsExactlyInAnyOrder(
                                "/api/v1/users/" + victims.get(0).id(),
                                "/api/v1/users/" + victims.get(1).id(),
                                "/api/v1/users/" + victims.get(2).id());
            }
            assertThat(saved).extracting(StepResult::requestId).doesNotHaveDuplicates();
            assertThat(observed).hasSize(406);
            assertThat(observed.subList(0, 203)).extracting(ObservedRequest::logicalStep)
                    .containsExactlyElementsOf(observed.subList(203, 406).stream()
                            .map(ObservedRequest::logicalStep).toList());
            assertThat(observed).allMatch(request -> request.sourceIp().equals("192.0.2.1"));
            assertThat(observed).extracting(ObservedRequest::requestId)
                    .containsExactlyElementsOf(saved.stream().map(result -> result.requestId().toString()).toList());
            for (FixtureUser victim : victims) {
                assertThat(saved.toString()).doesNotContain(victim.password(), token(victim));
            }
        } finally {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getRawPath();
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            observed.add(new ObservedRequest(exchange.getRequestMethod(), path, body, authorization,
                    exchange.getRequestHeaders().getFirst("X-Forwarded-For"), requestId));
            exchange.getResponseHeaders().set("X-Request-Id", requestId);
            if (path.equals("/api/v1/auth/login")) {
                JsonNode credentials = codec.jsonMapper().readTree(body);
                var victim = victims.stream().filter(candidate -> candidate.login().equals(credentials.path("login").asString())
                        && candidate.password().equals(credentials.path("password").asString())).findFirst();
                if (victim.isPresent()) {
                    write(exchange, 200, "application/json", codec.jsonMapper().createObjectNode()
                            .put("accessToken", token(victim.get())).put("tokenType", "Bearer").put("expiresIn", 900));
                    return;
                }
            } else {
                var victim = victims.stream().filter(candidate -> path.equals("/api/v1/users/" + candidate.id())
                        && ("Bearer " + token(candidate)).equals(authorization)).findFirst();
                if (victim.isPresent()) {
                    write(exchange, 200, "application/json", codec.jsonMapper().createObjectNode()
                            .put("id", victim.get().id()).put("login", victim.get().login())
                            .put("name", victim.get().name()).put("email", victim.get().email()));
                    return;
                }
            }
            write(exchange, 401, "application/problem+json", codec.jsonMapper().createObjectNode()
                    .put("type", "urn:lab:problem:http-error").put("title", "Unauthorized")
                    .put("status", 401).put("code", "UNAUTHORIZED").put("requestId", requestId));
        }
    }

    private void write(HttpExchange exchange, int status, String contentType, JsonNode body) throws IOException {
        byte[] bytes = codec.jsonMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static List<CredentialStuffingScenario.LoginAttempt> toAttempts(FixtureManifest manifest) {
        return manifest.credentialStuffing().attempts().stream()
                .map(pair -> new CredentialStuffingScenario.LoginAttempt(pair.userId(), pair.login(), pair.password()))
                .toList();
    }

    private static String token(FixtureUser victim) {
        return "fixture-token-" + victim.id();
    }

    private static List<StepResult> concat(List<StepResult> first, List<StepResult> second) {
        List<StepResult> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private record ObservedRequest(String method, String path, String body, String authorization,
                                   String sourceIp, String requestId) {
        String logicalStep() {
            return method + " " + path + " " + body + " " + authorization + " " + sourceIp;
        }
    }
}
