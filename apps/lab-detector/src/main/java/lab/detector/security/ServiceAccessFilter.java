package lab.detector.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lab.contracts.json.ContractJsonCodec;
import lab.detector.http.DetectorProblems;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServiceAccessFilter extends OncePerRequestFilter {
    public static final String ROLE = ServiceAccessFilter.class.getName() + ".role";
    private static final List<String> ROLES = List.of("CONTROL", "GATEWAY", "SHOP");
    private final List<byte[]> tokens;
    private final ContractJsonCodec codec;

    public ServiceAccessFilter(@Value("${lab.detector.control-token}") String control,
                               @Value("${lab.detector.gateway-token}") String gateway,
                               @Value("${lab.detector.shop-token}") String shop, ContractJsonCodec codec) {
        var values = List.of(control, gateway, shop);
        if (values.stream().anyMatch(token -> token.isBlank() || token.chars().anyMatch(Character::isWhitespace))
                || values.stream().distinct().count() != 3) {
            throw new IllegalArgumentException("Detector requires three distinct nonblank bearer tokens without whitespace");
        }
        tokens = values.stream().map(token -> token.getBytes(StandardCharsets.UTF_8)).toList();
        this.codec = codec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(DetectorProblems.REQUEST_ID, UUID.randomUUID());
        response.setHeader("Cache-Control", "no-store");
        if (request.getServletPath().equals("/health")) {
            chain.doFilter(request, response);
            return;
        }
        String role = authenticate(request);
        if (role == null) {
            response.setHeader("WWW-Authenticate", "Bearer");
            reject(HttpStatus.UNAUTHORIZED, request, response);
            return;
        }
        boolean batch = request.getMethod().equals("POST")
                && request.getServletPath().matches("/internal/v1/runs/[^/]+/log-batches");
        if (role.equals("CONTROL") == batch) {
            reject(HttpStatus.FORBIDDEN, request, response);
            return;
        }
        request.setAttribute(ROLE, role);
        chain.doFilter(request, response);
    }

    private String authenticate(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1) {
            return null;
        }
        String[] parts = headers.getFirst().split(" +", 2);
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) {
            return null;
        }
        byte[] candidate = parts[1].getBytes(StandardCharsets.UTF_8);
        int matched = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (MessageDigest.isEqual(tokens.get(i), candidate)) {
                matched = i;
            }
        }
        return matched < 0 ? null : ROLES.get(matched);
    }

    private void reject(HttpStatus status, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getOutputStream().write(codec.writeAsBytes(DetectorProblems.create(status,
                status == HttpStatus.UNAUTHORIZED ? "Требуется Service Bearer" : "Операция недоступна этой роли", request)));
    }
}
