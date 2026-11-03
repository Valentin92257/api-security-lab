package lab.shop.api;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    static final String REQUEST_ID = RequestLoggingFilter.class.getName() + ".requestId";
    private static final Logger ACCESS = LoggerFactory.getLogger("lab.access");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        return !path.equals("/api/v1") && !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = Collections.list(request.getHeaders("X-Request-Id"));
        String supplied = values.size() == 1 ? values.getFirst() : null;
        boolean valid = validId(supplied);
        String requestId = valid ? supplied : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        String previous = MDC.get("trace_id");
        MDC.put("trace_id", requestId);
        long started = System.nanoTime();
        try {
            if (!valid) {
                ProblemResponseWriter.write(response, requestId, 400, "INVALID_REQUEST", "X-Request-Id must be one UUID");
                return;
            }
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            if (response.isCommitted()) {
                throw failure;
            }
            response.reset();
            ProblemResponseWriter.write(response, requestId, 500, "INTERNAL_ERROR", "Request processing failed");
        } finally {
            ACCESS.atInfo()
                    .addKeyValue("service", "shop-api")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0)
                    .log("http_request");
            if (previous == null) {
                MDC.remove("trace_id");
            } else {
                MDC.put("trace_id", previous);
            }
        }
    }

    private static boolean validId(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
