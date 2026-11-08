package lab.shop.api;

import jakarta.servlet.http.HttpServletRequest;
import lab.contracts.shop.model.Problem;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.function.Consumer;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger("lab.api.exception");
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler({AuthenticationException.class, UnauthorizedException.class})
    public ResponseEntity<Problem> handleUnauthorized(Exception ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("401 requestId={}: {}", requestId, ex.getMessage());

        return respond(HttpStatus.UNAUTHORIZED, Problem.CodeEnum.UNAUTHORIZED, requestId,
                headers -> headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Problem> handleConflict(ConflictException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("409 requestId={}: {}", requestId, ex.getMessage());

        return respond(HttpStatus.CONFLICT, Problem.CodeEnum.CONFLICT, requestId, _ -> {
        });
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleGlobalException(Exception ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.error("500 requestId={}", requestId, ex);

        return respond(HttpStatus.INTERNAL_SERVER_ERROR, Problem.CodeEnum.INTERNAL_ERROR, requestId, _ -> {
        });
    }

    private ResponseEntity<Problem> respond(HttpStatus status,
                                            Problem.CodeEnum code,
                                            UUID requestId,
                                            Consumer<HttpHeaders> extraHeaders) {
        Problem problem = new Problem(
                Problem.TypeEnum.URN_LAB_PROBLEM_HTTP_ERROR,
                status.getReasonPhrase(),
                status.value(),
                code,
                requestId
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        extraHeaders.accept(headers);

        return new ResponseEntity<>(problem, headers, status);
    }

    private UUID getRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);

        try {
            return requestId != null ? UUID.fromString(requestId) : null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}