package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import lab.contracts.json.ContractValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BenignUserScenarioTest {
    private final HttpClient client = mock(HttpClient.class);
    private final List<StepResult> saved = new ArrayList<>();
    private final StepResultSink sink = mock(StepResultSink.class, invocation -> {
        if (invocation.getMethod().getName().equals("save")) {
            saved.add(invocation.getArgument(0));
        }
        return null;
    });
    private final ShopContractValidator validator = mock(ShopContractValidator.class);
    private final ContractJsonCodec json = new ContractJsonCodec();
    private final StepExecutor executor = new StepExecutor(client, sink, validator);
    private final BenignUserScenario scenario = new BenignUserScenario(executor, new ShopRequests(validator, json));
    private final UUID trialId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(GatewayTarget.class)
    void executesShopperV0FlowThroughSelectedGateway(GatewayTarget target) {
        respondAllOk("benign-jwt-1");
        var session = BenignSession.shopper(
                "198.51.100.10", "cs_benign_01", "password-secret", "Keyboard", 4L, 2);

        var evidence = run(target, List.of(session));

        String expectedHost = target == GatewayTarget.VULNERABLE ? "http://lab-gateway:8081" : "http://lab-gateway:8082";
        var sent = capturedRequests(3);
        assertThat(evidence).singleElement().satisfies(entry -> {
            assertThat(entry.session()).isEqualTo(session);
            assertThat(entry.steps()).hasSize(3);
        });
        assertThat(saved).hasSize(3).allSatisfy(result -> {
            assertThat(result.trialId()).isEqualTo(trialId);
            assertThat(result.runId()).isEqualTo(runId);
            assertThat(result.target()).isEqualTo(target);
            assertThat(result.outcome()).isEqualTo(StepOutcome.RESPONSE);
            assertThat(result.status()).isEqualTo(200);
        });

        HttpRequest login = sent.get(0);
        assertThat(login.method()).isEqualTo("POST");
        assertThat(login.uri().toString()).isEqualTo(expectedHost + "/api/v1/auth/login");
        assertThat(login.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(login.headers().firstValue("X-Forwarded-For")).contains("198.51.100.10");
        assertThat(login.headers().firstValue("Authorization")).isEmpty();
        assertThat(login.headers().firstValue("X-Request-Id")).contains(saved.get(0).requestId().toString());
        JsonNode loginBody = json.jsonMapper().readTree(bodyOf(login));
        assertThat(loginBody.get("login").asString()).isEqualTo("cs_benign_01");
        assertThat(loginBody.get("password").asString()).isEqualTo("password-secret");

        HttpRequest products = sent.get(1);
        assertThat(products.method()).isEqualTo("GET");
        assertThat(products.uri().toString()).isEqualTo(expectedHost + "/api/v1/products?q=Keyboard");
        assertThat(products.headers().firstValue("Authorization")).contains("Bearer benign-jwt-1");
        assertThat(products.headers().firstValue("X-Forwarded-For")).contains("198.51.100.10");
        assertThat(products.headers().firstValue("Content-Type")).isEmpty();
        assertThat(products.headers().firstValue("X-Request-Id")).contains(saved.get(1).requestId().toString());
        assertThat(saved.get(1).route()).isEqualTo("/api/v1/products");

        HttpRequest cart = sent.get(2);
        assertThat(cart.method()).isEqualTo("PUT");
        assertThat(cart.uri().toString()).isEqualTo(expectedHost + "/api/v1/cart/items/4");
        assertThat(cart.headers().firstValue("Authorization")).contains("Bearer benign-jwt-1");
        assertThat(cart.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(cart.headers().firstValue("X-Forwarded-For")).contains("198.51.100.10");
        assertThat(cart.headers().firstValue("X-Request-Id")).contains(saved.get(2).requestId().toString());
        assertThat(bodyOf(cart)).isEqualTo("{\"quantity\":2}");

        verify(validator, times(1)).validateLoginRequest(anyString());
        assertThat(evidence.toString()).doesNotContain("password-secret", "benign-jwt-1");
        assertThat(saved.toString()).doesNotContain("password-secret", "benign-jwt-1");
    }

    @Test
    void encodesCatalogQueryCharactersAndOmitsEmptyQuery() {
        respondAllOk("benign-jwt");
        var session = new BenignSession("198.51.100.12", List.of(
                new BenignAction.Login("cs_benign_01", "password-secret"),
                new BenignAction.BrowseCatalog("developer's 50% _calibrated & +"),
                new BenignAction.BrowseCatalog(null),
                new BenignAction.BrowseCatalog("")));

        run(GatewayTarget.PROTECTED, List.of(session));

        var sent = capturedRequests(4);
        assertThat(sent.get(1).uri().getRawQuery()).isEqualTo("q=developer%27s%2050%25%20_calibrated%20%26%20%2B");
        assertThat(sent.get(2).uri().getRawQuery()).isNull();
        assertThat(sent.get(3).uri().getRawQuery()).isNull();
        assertThat(saved.subList(1, 4)).allSatisfy(result -> assertThat(result.route()).isEqualTo("/api/v1/products"));
    }

    @Test
    void isolatesTokensAndClientAddressesAcrossMultipleSessions() {
        var loginCounter = new AtomicInteger();
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(call -> CompletableFuture.completedFuture(httpResponse(200)));
        when(validator.validateResponse(any(), anyInt(), any(), any(), any())).thenAnswer(call -> {
            ShopRoute route = call.getArgument(0);
            if (route == ShopRoute.LOGIN) {
                return tokenBody("token-session-" + loginCounter.incrementAndGet());
            }
            return json.jsonMapper().createObjectNode();
        });
        var first = BenignSession.shopper("198.51.100.10", "cs_benign_01", "password-one", "Mouse", 1L, 1);
        var second = BenignSession.shopper("198.51.100.11", "bola_benign_01", "password-two", null, 2L, 3);

        var evidence = run(GatewayTarget.VULNERABLE, List.of(first, second));

        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).session()).isEqualTo(first);
        assertThat(evidence.get(0).steps()).hasSize(3);
        assertThat(evidence.get(1).session()).isEqualTo(second);
        assertThat(evidence.get(1).steps()).hasSize(3);

        var sent = capturedRequests(6);
        assertThat(sent.subList(0, 3)).allSatisfy(req ->
                assertThat(req.headers().firstValue("X-Forwarded-For")).contains("198.51.100.10"));
        assertThat(sent.subList(1, 3)).allSatisfy(req ->
                assertThat(req.headers().firstValue("Authorization")).contains("Bearer token-session-1"));
        assertThat(sent.subList(3, 6)).allSatisfy(req ->
                assertThat(req.headers().firstValue("X-Forwarded-For")).contains("198.51.100.11"));
        assertThat(sent.subList(4, 6)).allSatisfy(req ->
                assertThat(req.headers().firstValue("Authorization")).contains("Bearer token-session-2"));
    }

    @Test
    void abortsOnlyTheFailedSessionWhenLoginIsRejectedAndContinuesNext() {
        var loginAttempts = new AtomicInteger();
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(call -> {
            HttpRequest req = call.getArgument(0);
            if (req.uri().getRawPath().equals("/api/v1/auth/login") && loginAttempts.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(httpResponse(401));
            }
            return CompletableFuture.completedFuture(httpResponse(200));
        });
        when(validator.validateResponse(any(), anyInt(), any(), any(), any())).thenAnswer(call -> {
            ShopRoute route = call.getArgument(0);
            int status = call.getArgument(1);
            return route == ShopRoute.LOGIN && status == 200 ? tokenBody("second-jwt") : json.jsonMapper().createObjectNode();
        });
        var first = BenignSession.shopper("198.51.100.10", "cs_benign_01", "wrong-pass1", null, 1L, 1);
        var second = BenignSession.shopper("198.51.100.11", "bola_benign_01", "password-two", "Lamp", 2L, 1);

        var evidence = run(GatewayTarget.PROTECTED, List.of(first, second));

        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).steps()).singleElement()
                .satisfies(step -> assertThat(step.result().status()).isEqualTo(401));
        assertThat(evidence.get(1).steps()).hasSize(3);
        capturedRequests(4);
    }

    @Test
    void stopsSessionWhenCatalogStepFailsOrLoginHasNoValidatedBody() {
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(call -> {
            HttpRequest req = call.getArgument(0);
            int status = req.uri().getRawPath().equals("/api/v1/products") ? 500 : 200;
            return CompletableFuture.completedFuture(httpResponse(status));
        });
        when(validator.validateResponse(any(), anyInt(), any(), any(), any())).thenAnswer(call -> {
            ShopRoute route = call.getArgument(0);
            return route == ShopRoute.LOGIN ? tokenBody("jwt-1") : json.jsonMapper().createObjectNode();
        });
        var catalogFails = BenignSession.shopper("198.51.100.10", "cs_benign_01", "password-one", "Desk", 1L, 1);

        var evidence = run(GatewayTarget.VULNERABLE, List.of(catalogFails));

        assertThat(evidence).singleElement().satisfies(entry -> assertThat(entry.steps()).hasSize(2));
        capturedRequests(2);

        when(validator.validateResponse(any(), anyInt(), any(), any(), any())).thenReturn(null);
        var noBodyLogin = BenignSession.shopper("198.51.100.11", "cs_benign_01", "password-one", null, 1L, 1);

        var secondEvidence = run(GatewayTarget.VULNERABLE, List.of(noBodyLogin));

        assertThat(secondEvidence).singleElement().satisfies(entry -> assertThat(entry.steps()).hasSize(1));
    }

    @Test
    void validatesLoginRequestBeforeSending() {
        doThrow(new ContractValidationException("Request rejected"))
                .when(validator).validateLoginRequest(anyString());
        var session = BenignSession.shopper("198.51.100.10", "cs_benign_01", "password-secret", null, 1L, 1);

        assertThatThrownBy(() -> run(GatewayTarget.PROTECTED, List.of(session)))
                .isInstanceOf(ContractValidationException.class);

        verifyNoInteractions(client, sink);
    }

    @Test
    void rejectsEmptyNullOrOverBudgetPlansBeforeSending() {
        var session = BenignSession.shopper("198.51.100.10", "cs_benign_01", "password-secret", null, 1L, 1);
        List<BenignSession> withNull = new ArrayList<>();
        withNull.add(session);
        withNull.add(null);
        List<BenignSession> overBudget = new ArrayList<>();
        for (int i = 0; i < 134; i++) {
            overBudget.add(session);
        }

        assertThatThrownBy(() -> run(GatewayTarget.PROTECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(GatewayTarget.PROTECTED, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(GatewayTarget.PROTECTED, withNull))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(GatewayTarget.PROTECTED, overBudget))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(client, sink, validator);
    }

    @Test
    void acceptsPlanAtTheExactStepBudget() {
        respondAllOk("budget-jwt");
        List<BenignAction> actions = new ArrayList<>(RunContext.MAX_STEPS);
        actions.add(new BenignAction.Login("cs_benign_01", "password-secret"));
        for (int i = 1; i < RunContext.MAX_STEPS; i++) {
            actions.add(new BenignAction.BrowseCatalog(null));
        }

        var evidence = run(GatewayTarget.PROTECTED, List.of(new BenignSession("198.51.100.10", actions)));

        assertThat(evidence).singleElement().satisfies(entry ->
                assertThat(entry.steps()).hasSize(RunContext.MAX_STEPS));
        capturedRequests(RunContext.MAX_STEPS);
    }

    @Test
    void stopsWhenInterruptedBeforeOrDuringValidationOrExecution() {
        var session = BenignSession.shopper("198.51.100.10", "cs_benign_01", "password-secret", null, 1L, 1);
        try {
            Thread.currentThread().interrupt();
            assertThat(run(GatewayTarget.PROTECTED, List.of(session))).isEmpty();
            verifyNoInteractions(client, sink, validator);
        } finally {
            Thread.interrupted();
        }

        doAnswer(_ -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(validator).validateLoginRequest(anyString());
        try {
            var evidence = run(GatewayTarget.PROTECTED, List.of(session));
            assertThat(evidence).singleElement().satisfies(entry -> assertThat(entry.steps()).isEmpty());
            verifyNoInteractions(client, sink);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopsRunAfterAnInterruptedHttpStep() {
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            return pending;
        });
        var first = BenignSession.shopper("198.51.100.10", "cs_benign_01", "password-one", null, 1L, 1);
        var second = BenignSession.shopper("198.51.100.11", "bola_benign_01", "password-two", null, 2L, 1);

        try {
            var evidence = run(GatewayTarget.PROTECTED, List.of(first, second));
            assertThat(evidence).singleElement().satisfies(entry ->
                    assertThat(entry.steps()).singleElement().satisfies(step ->
                            assertThat(step.result().outcome()).isEqualTo(StepOutcome.INTERRUPTED)));
            capturedRequests(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void propagatesStorageFailureAndStopsRun() {
        respondAllOk("benign-jwt");
        var failure = new IllegalStateException("Result storage unavailable");
        doThrow(failure).when(sink).save(any());
        var session = BenignSession.shopper("198.51.100.10", "cs_benign_01", "password-secret", null, 1L, 1);

        assertThatThrownBy(() -> run(GatewayTarget.PROTECTED, List.of(session)))
                .isSameAs(failure);

        capturedRequests(1);
    }

    private List<BenignUserScenario.SessionEvidence> run(GatewayTarget target, List<BenignSession> sessions) {
        var run = new RunContext(trialId, runId, Duration.ofMinutes(1));
        var evidence = scenario.run(run, target, sessions);
        int executedCount = evidence.stream().mapToInt(entry -> entry.steps().size()).sum();
        assertThat(run.finish().steps()).hasSize(executedCount);
        return evidence;
    }

    private void respondAllOk(String token) {
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(call -> CompletableFuture.completedFuture(httpResponse(200)));
        when(validator.validateResponse(any(), anyInt(), any(), any(), any())).thenAnswer(call -> {
            ShopRoute route = call.getArgument(0);
            return route == ShopRoute.LOGIN ? tokenBody(token) : json.jsonMapper().createObjectNode();
        });
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> httpResponse(int status) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }

    private JsonNode tokenBody(String token) {
        return json.jsonMapper().createObjectNode()
                .put("accessToken", token).put("tokenType", "Bearer").put("expiresIn", 900);
    }

    private List<HttpRequest> capturedRequests(int count) {
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(count)).sendAsync(captor.capture(), any());
        return captor.getAllValues();
    }

    private static String bodyOf(HttpRequest request) {
        var publisher = request.bodyPublisher().orElse(null);
        if (publisher == null || publisher.contentLength() == 0) {
            return null;
        }
        var out = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        return out.toString(StandardCharsets.UTF_8);
    }
}
