package lab.detector.analysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Normalized filters shared by SQL queries and cursor binding. */
public record EventFilter(UUID runId, Instant from, Instant to, String producer, UUID requestId,
                          String method, List<Integer> status, String statusClass, String route,
                          String remoteAddr, Long userId) {
    public EventFilter {
        status = status == null ? List.of() : status;

        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("from должен быть меньше to");
        }
        if (producer != null && !List.of("GATEWAY", "SHOP").contains(producer)) {
            throw invalid("producer должен быть GATEWAY или SHOP");
        }
        if (method != null && !method.matches("[A-Z][A-Z0-9!#$%&'*+.^_`|~-]{0,31}")) {
            throw invalid("method должен соответствовать формату HTTP-метода и содержать от 1 до 32 символов");
        }
        if (status.size() > 500) {
            throw invalid("status должен содержать не более 500 кодов");
        }
        if (status.stream().anyMatch(code -> code == null || code < 100 || code > 599)) {
            throw invalid("status должен содержать коды от 100 до 599");
        }
        if (statusClass != null && !statusClass.matches("[1-5]xx")) {
            throw invalid("statusClass должен быть от 1xx до 5xx");
        }
        if (!status.isEmpty() && statusClass != null) {
            throw invalid("status и statusClass нельзя использовать одновременно");
        }
        if (route != null && (route.isEmpty() || route.length() > 200 || !route.startsWith("/"))) {
            throw invalid("route должен начинаться с / и содержать от 1 до 200 символов");
        }
        if (remoteAddr != null && (remoteAddr.length() < 2 || remoteAddr.length() > 45)) {
            throw invalid("remoteAddr должен содержать от 2 до 45 символов");
        }
        if (userId != null && (userId < 1 || userId > 9007199254740991L)) {
            throw invalid("userId должен быть от 1 до 9007199254740991");
        }
        status = status.stream().distinct().sorted().toList();
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
