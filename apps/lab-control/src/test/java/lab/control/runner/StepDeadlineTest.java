package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;

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
    @Timeout(10)
    void stopsReadingWhenTheResponseExceedsTheBodyLimit() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/products", exchange -> {
            try (exchange) {
                byte[] body = new byte[2 * 1024 * 1024 + 1];
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        try (var client = new RunnerConfiguration().runnerClient()) {
            server.start();
            try {
                var transport = mock(HttpClient.class);
                var results = mock(StepResultSink.class);
                when(transport.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/products");
                    return client.sendAsync(HttpRequest.newBuilder(uri).GET().build(), handler);
                });
                var executor = new StepExecutor(transport, results, new ShopContractValidator(new ContractJsonCodec()));

                var run = new RunContext(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(1));
                var response = executor.execute(run,
                        new StepRequest(GatewayTarget.PROTECTED, "GET", "/api/v1/products", Map.of(), null));

                assertThat(response.result().outcome()).isEqualTo(StepOutcome.IO_ERROR);
                assertThat(response.result().status()).isNull();
                assertThat(response.body()).isNull();
                verify(results).save(response.result());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @Timeout(15)
    void timesOutAndSavesTheResultWhenTheResponseBodyStalls() throws Exception {
        var bodyStarted = new CountDownLatch(1);
        var releaseBody = new CountDownLatch(1);
        var pending = new AtomicReference<CompletableFuture<HttpResponse<byte[]>>>();
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
                when(transport.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                    HttpRequest original = invocation.getArgument(0);
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + original.uri().getRawPath());
                    var request = HttpRequest.newBuilder(uri).timeout(original.timeout().orElseThrow()).GET().build();
                    var future = client.sendAsync(request, handler);
                    pending.set(future);
                    return future;
                });
                var executor = new StepExecutor(transport, results, new ShopContractValidator(new ContractJsonCodec()));
                var step = new StepRequest(GatewayTarget.VULNERABLE, "GET", "/api/v1/products", Map.of(), null);
                var run = new RunContext(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(1));
                long started = System.nanoTime();

                var result = executor.execute(run, step).result();

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
