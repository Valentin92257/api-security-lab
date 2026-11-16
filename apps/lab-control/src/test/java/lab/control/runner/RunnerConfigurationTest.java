package lab.control.runner;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RunnerConfigurationTest {
    @Test
    void ignoresTheSystemProxyAndDoesNotFollowRedirects() throws Exception {
        var followedRequests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/followed");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/followed", exchange -> {
            followedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        var previousProxy = ProxySelector.getDefault();
        var systemProxy = mock(ProxySelector.class);
        when(systemProxy.select(any())).thenThrow(new AssertionError("System proxy must not be used"));

        try {
            ProxySelector.setDefault(systemProxy);
            try (var client = new RunnerConfiguration().runnerClient()) {
                var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();

                var response = client.send(request, HttpResponse.BodyHandlers.discarding());

                assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
                assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
                assertThat(client.proxy().orElseThrow().select(uri)).containsExactly(Proxy.NO_PROXY);
                assertThat(response.statusCode()).isEqualTo(302);
                assertThat(followedRequests).hasValue(0);
                verifyNoInteractions(systemProxy);
            }
        } finally {
            ProxySelector.setDefault(previousProxy);
            server.stop(0);
        }
    }
}
