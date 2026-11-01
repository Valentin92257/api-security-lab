package lab.control.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class OperatorAccessFilter extends OncePerRequestFilter {
    private final byte[] expectedToken;
    private final JsonMapper json;

    OperatorAccessFilter(@Value("${lab.control.operator-token}") String token, JsonMapper json) {
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("LAB_OPERATOR_TOKEN must contain a nonblank bearer token without whitespace");
        }
        expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ControlProblems.REQUEST_ID, UUID.randomUUID());
        response.setHeader("Cache-Control", "no-store");
        if (!request.getServletPath().equals("/health")) {
            var values = Collections.list(request.getHeaders("Authorization"));
            if (values.size() != 1 || !matches(values.getFirst())) {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer");
                response.setContentType("application/problem+json");
                response.getOutputStream().write(json.writeValueAsBytes(
                        ControlProblems.create(HttpStatus.UNAUTHORIZED, "Требуется Operator Bearer", request)));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String authorization) {
        int separator = authorization.indexOf(' ');
        if (separator != 6 || !authorization.regionMatches(true, 0, "Bearer", 0, 6)) {
            return false;
        }
        int start = separator + 1;
        while (start < authorization.length() && authorization.charAt(start) == ' ') {
            start++;
        }
        return MessageDigest.isEqual(expectedToken, authorization.substring(start).getBytes(StandardCharsets.UTF_8));
    }
}
