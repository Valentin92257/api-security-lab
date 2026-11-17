package lab.control.runner;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepDeadlineTest {
    @Test
    @Timeout(15)
    void timesOutAndSavesTheResultWhenTheResponseBodyStalls() throws Exception {
        var bodyStarted = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<HttpResponse<Void>>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/products", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (var body = exchange.getResponseBody()) {
                body.write('a');
                body.flush();
                bodyStarted.countDown();
                try {
                    releaseBody.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
                body.write('b');
            } finally {
                exchange.close();
            }
        });
        try (var client = new RunnerConfiguration().runnerClient()) {
            server.start();
            try {
                var transport = mock(HttpClient.class);
                var results = mock(StepResultSink.class);
                when(transport.<Void>sendAsync(any(), any())).thenAnswer(invocation -> {
                    HttpRequest original = invocation.getArgument(0);
                    HttpResponse.BodyHandler<Void> handler = invocation.getArgument(1);
                    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + original.uri().getRawPath());
                    var request = HttpRequest.newBuilder(uri).timeout(original.timeout().orElseThrow()).GET().build();
                    var future = client.sendAsync(request, handler);
                    pending.set(future);
                    return future;
                });
                var executor = new StepExecutor(transport, results);
                var step = new StepRequest(GatewayTarget.VULNERABLE, "GET", "/api/v1/products", Map.of(), null);
                long started = System.nanoTime();

                var result = executor.execute(UUID.randomUUID(), UUID.randomUUID(), step);

                assertThat(bodyStarted.getCount()).isZero();
                assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(4900L, 10000L);
                assertThat(result.outcome()).isEqualTo(StepOutcome.TIMEOUT);
                assertThat(result.status()).isNull();
                assertThat(result.durationMillis()).isBetween(4900L, 10000L);
                assertThat(pending.get()).failsWithin(Duration.ofSeconds(1));
                verify(results).save(result);
            } finally {
                releaseBody.countDown();
                server.stop(0);
            }
        }
    }
}
