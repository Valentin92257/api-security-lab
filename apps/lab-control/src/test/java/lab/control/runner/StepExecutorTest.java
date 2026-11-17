package lab.control.runner;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StepExecutorTest {
    private final HttpClient client = mock(HttpClient.class);
    private final StepResultSink results = mock(StepResultSink.class);
    private final StepExecutor executor = new StepExecutor(client, results);
    private final UUID trialId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({"VULNERABLE,8081", "PROTECTED,8082"})
    void sendsThroughTheSelectedGatewayAndSavesTheResult(GatewayTarget target, int port) throws Exception {
        responds(200);
        var before = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

        var result = executor.execute(trialId, runId, step(target, "GET", "/api/v1/products"));

        var request = request();
        assertThat(request.uri().toString()).isEqualTo("http://lab-gateway:" + port + "/api/v1/products");
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.timeout()).contains(Duration.ofSeconds(5));
        assertThat(request.headers().firstValue("Accept")).contains("application/json");
        assertThat(request.headers().firstValue("X-Request-Id")).contains(result.requestId().toString());
        assertThat(result.trialId()).isEqualTo(trialId);
        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.target()).isEqualTo(target);
        assertThat(result.method()).isEqualTo("GET");
        assertThat(result.route()).isEqualTo("/api/v1/products");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.outcome()).isEqualTo(StepOutcome.RESPONSE);
        assertThat(result.startedAt()).isBetween(before, OffsetDateTime.now());
        assertThat(result.durationMillis()).isNotNegative();
        verify(results).save(result);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "POST|/api/v1/auth/register",
            "POST|/api/v1/auth/login",
            "GET|/api/v1/users/42",
            "GET|/api/v1/products",
            "GET|/api/v1/products?q=%27%20OR%201%3D1--",
            "GET|/api/v1/cart",
            "PUT|/api/v1/cart/items/42",
            "PUT|/api/v1/cart/coupon",
            "POST|/api/v1/orders",
            "GET|/api/v1/orders/42"
    })
    void acceptsCatalogRoutes(String method, String route) throws Exception {
        responds(200);

        var result = executor.execute(trialId, runId, step(GatewayTarget.VULNERABLE, method, route));

        assertThat(request().uri().getRawPath()).isEqualTo(route.split("\\?", 2)[0]);
        assertThat(result.route()).isEqualTo(route.split("\\?", 2)[0]);
        verify(results).save(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/api/v1/products",
            "http://lab-gateway:8081/api/v1/products",
            "https://lab-gateway:8081/api/v1/products",
            "//example.com/api/v1/products",
            "/internal/v1/status",
            "/api/v1/../internal/v1/status",
            "/api/v1/%2e%2e/internal/v1/status",
            "/api/v1/products/../../internal/v1/status",
            "/api/v1/users/1/../../internal/v1/status",
            "/api/v1/%2finternal/v1/status",
            "/api/v1/products#fragment",
            "/api/v1/products?redirect=http://example.com",
            "/api/v1/unknown"
    })
    void rejectsAddressesAndRoutesOutsideTheCatalogBeforeSending(String route) {
        assertThatThrownBy(() -> executor.execute(trialId, runId,
                step(GatewayTarget.VULNERABLE, "GET", route)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(client, results);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PUT|/api/v1/products",
            "GET|/api/v1/auth/login",
            "POST|/api/v1/cart",
            "GET|/api/v1/cart?q=secret"
    })
    void rejectsMethodsAndQueriesOutsideTheCatalog(String method, String route) {
        assertThatThrownBy(() -> executor.execute(trialId, runId,
                step(GatewayTarget.VULNERABLE, method, route)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(client, results);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Lab-Run-Id", "x-lab-mode", "X-Request-Id", "x-request-id", "Host",
            "Proxy-Authorization", "Connection", "Accept"})
    void rejectsCallerHeadersOutsideTheAllowlist(String name) {
        assertThatThrownBy(() -> executor.execute(trialId, runId,
                new StepRequest(GatewayTarget.PROTECTED, "GET", "/api/v1/products",
                        Map.of(name, "forbidden"), null)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(client, results);
    }

    @Test
    void sendsAllowedHeadersWithoutSavingCredentialsOrBody() throws Exception {
        responds(401);
        String authorization = "Bearer private-token";
        String body = "{\"login\":\"alice\",\"password\":\"private-password\"}";
        String key = UUID.randomUUID().toString();

        var result = executor.execute(trialId, runId,
                new StepRequest(GatewayTarget.PROTECTED, "POST", "/api/v1/auth/login",
                        Map.of("authorization", authorization, "content-type", "application/json",
                                "idempotency-key", key, "x-forwarded-for", "192.0.2.1"), body));

        var request = request();
        assertThat(request.headers().firstValue("Authorization")).contains(authorization);
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(request.headers().firstValue("Idempotency-Key")).contains(key);
        assertThat(request.headers().firstValue("X-Forwarded-For")).contains("192.0.2.1");
        assertThat(request.headers().map().keySet()).noneMatch(name -> name.toLowerCase().startsWith("x-lab"));
        assertThat(result.status()).isEqualTo(401);
        assertThat(result.outcome()).isEqualTo(StepOutcome.RESPONSE);
        assertThat(result.toString()).doesNotContain("private-token", "private-password", body, authorization);
        verify(results).save(result);
    }

    @Test
    void excludesQueryValuesFromTheSavedRoute() throws Exception {
        responds(200);

        var result = executor.execute(trialId, runId,
                step(GatewayTarget.VULNERABLE, "GET", "/api/v1/products?q=private-search"));

        assertThat(request().uri().getRawQuery()).isEqualTo("q=private-search");
        assertThat(result.route()).isEqualTo("/api/v1/products");
        assertThat(result.toString()).doesNotContain("private-search");
        verify(results).save(result);
    }

    @Test
    void assignsANewRequestIdForEachAttempt() throws Exception {
        responds(200);
        var step = step(GatewayTarget.VULNERABLE, "GET", "/api/v1/products");

        var first = executor.execute(trialId, runId, step);
        var second = executor.execute(trialId, runId, step);

        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).sendAsync(requests.capture(), any());
        assertThat(first.requestId()).isNotEqualTo(second.requestId());
        assertThat(requests.getAllValues()).extracting(request -> request.headers().firstValue("X-Request-Id").orElseThrow())
                .containsExactly(first.requestId().toString(), second.requestId().toString());
        verify(results).save(first);
        verify(results).save(second);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void savesRedirectAsRejectedWithoutAnotherAttempt(int status) throws Exception {
        responds(status);

        var result = executor.execute(trialId, runId, step(GatewayTarget.VULNERABLE, "GET", "/api/v1/products"));

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.outcome()).isEqualTo(StepOutcome.REDIRECT_REJECTED);
        verify(client).sendAsync(any(HttpRequest.class), any());
        verify(results).save(result);
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void savesTransportFailureWithoutExceptionDetails(IOException failure, StepOutcome outcome) {
        when(client.<Void>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(CompletableFuture.failedFuture(failure));

        var result = executor.execute(trialId, runId, step(GatewayTarget.VULNERABLE, "GET", "/api/v1/products"));

        assertThat(result.status()).isNull();
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.toString()).doesNotContain("private-token");
        verify(results).save(result);
    }

    @Test
    void cancelsPendingRequestOnInterruptionAndRestoresTheInterruptFlagAfterSaving() {
        var pending = new CompletableFuture<HttpResponse<Void>>();
        when(client.<Void>sendAsync(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return pending;
        });
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(results).save(any(StepResult.class));

        try {
            var result = executor.execute(trialId, runId,
                    step(GatewayTarget.VULNERABLE, "GET", "/api/v1/products"));

            assertThat(pending.isCancelled()).isTrue();
            assertThat(result.status()).isNull();
            assertThat(result.outcome()).isEqualTo(StepOutcome.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(results).save(result);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void propagatesPersistenceFailure() throws Exception {
        responds(200);
        var failure = new IllegalStateException("Result storage unavailable");
        doThrow(failure).when(results).save(any(StepResult.class));

        assertThatThrownBy(() -> executor.execute(trialId, runId,
                step(GatewayTarget.VULNERABLE, "GET", "/api/v1/products")))
                .isSameAs(failure);

        verify(client).sendAsync(any(HttpRequest.class), any());
        verify(results).save(any(StepResult.class));
    }

    private static Stream<Arguments> transportFailures() {
        return Stream.of(
                Arguments.of(new HttpTimeoutException("private-token"), StepOutcome.TIMEOUT),
                Arguments.of(new IOException("private-token"), StepOutcome.IO_ERROR));
    }

    private StepRequest step(GatewayTarget target, String method, String route) {
        return new StepRequest(target, method, route, Map.of(), null);
    }

    private HttpRequest request() throws Exception {
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).sendAsync(request.capture(), any());
        return request.getValue();
    }

    @SuppressWarnings("unchecked")
    private void responds(int status) throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(client.<Void>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
}
