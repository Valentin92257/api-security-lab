package lab.gateway.http;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProtectedProxyController {
    private final GatewayProxyProperties properties;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines;
    private final TomcatServletWebServerFactory factory;

    ProtectedProxyController(GatewayProxyProperties properties, CloseableHttpClient client,
                             ScheduledExecutorService deadlines, TomcatServletWebServerFactory factory) {
        this.properties = properties;
        this.client = client;
        this.deadlines = deadlines;
        this.factory = factory;
    }

    @RequestMapping({"/api/v1", "/api/v1/**"})
    void proxy(HttpServletRequest incoming, HttpServletResponse outgoing) throws IOException {
        String requestId = (String) incoming.getAttribute(RequestLoggingFilter.REQUEST_ID);
        boolean protectedListener = factory.getAdditionalConnectors().stream()
                .anyMatch(connector -> connector.getLocalPort() == incoming.getLocalPort());
        if (!protectedListener) {
            ProblemResponseWriter.write(outgoing, requestId, 404, "NOT_FOUND", "Route is not configured on this listener");
            return;
        }
        byte[] body = incoming.getInputStream().readNBytes(properties.maxBodyBytes() + 1);
        if (body.length > properties.maxBodyBytes()) {
            ProblemResponseWriter.write(outgoing, requestId, 413, "INVALID_REQUEST", "Request body exceeds transport limit");
            return;
        }
        ProxyResponse result;
        try {
            result = exchange(createRequest(incoming, requestId, body));
        } catch (SocketTimeoutException timeout) {
            ProblemResponseWriter.write(outgoing, requestId, 504, "UNAVAILABLE", "Upstream timed out");
            return;
        } catch (IOException failure) {
            ProblemResponseWriter.write(outgoing, requestId, 502, "UNAVAILABLE", "Upstream is unavailable");
            return;
        }
        writeResponse(incoming, outgoing, result);
    }

    @RequestMapping(value = {"/api/v1", "/api/v1/**"}, method = RequestMethod.OPTIONS)
    void proxyOptions(HttpServletRequest incoming, HttpServletResponse outgoing) throws IOException {
        proxy(incoming, outgoing);
    }

    private HttpUriRequestBase createRequest(HttpServletRequest incoming, String requestId, byte[] body) {
        String origin = properties.upstream().toString().replaceAll("/$", "");
        String query = incoming.getQueryString();
        URI target = URI.create(origin + incoming.getRequestURI() + (query == null ? "" : "?" + query));
        var request = new HttpUriRequestBase(incoming.getMethod(), target);
        ProxyHeaders.copyRequest(incoming, request);
        request.setHeader("X-Request-Id", requestId);
        if (body.length > 0 || incoming.getContentLengthLong() >= 0) {
            request.setEntity(new ByteArrayEntity(body, null));
        }
        return request;
    }

    private ProxyResponse exchange(HttpUriRequestBase request) throws IOException {
        var expired = new AtomicBoolean();
        var deadline = deadlines.schedule(() -> {
            expired.set(true);
            request.cancel();
        }, properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        try {
            var result = client.execute(request, response -> {
                byte[] body = response.getEntity() == null ? new byte[0]
                        : response.getEntity().getContent().readNBytes(properties.maxBodyBytes() + 1);
                if (body.length > properties.maxBodyBytes()) {
                    request.cancel();
                    throw new IOException("Upstream response exceeds transport limit");
                }
                return new ProxyResponse(response.getCode(), response.getHeaders(), body);
            });
            if (expired.get()) {
                throw new SocketTimeoutException("Upstream timed out");
            }
            return result;
        } catch (IOException failure) {
            if (expired.get() || failure instanceof ConnectionRequestTimeoutException) {
                var timeout = new SocketTimeoutException("Upstream timed out");
                timeout.initCause(failure);
                throw timeout;
            }
            throw failure;
        } finally {
            deadline.cancel(false);
        }
    }

    private void writeResponse(HttpServletRequest incoming, HttpServletResponse outgoing, ProxyResponse result)
            throws IOException {
        boolean head = incoming.getMethod().equals("HEAD");
        outgoing.setStatus(result.status());
        ProxyHeaders.copyResponse(result.headers(), outgoing, head || result.status() == 304);
        if (!head && result.status() != 204 && result.status() != 304) {
            outgoing.getOutputStream().write(result.body());
        }
    }

    private record ProxyResponse(int status, Header[] headers, byte[] body) {
    }
}
