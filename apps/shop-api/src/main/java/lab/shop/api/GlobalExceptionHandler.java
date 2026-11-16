package lab.shop.api;

import jakarta.servlet.http.HttpServletRequest;
import lab.contracts.common.model.Problem;
import lab.contracts.shop.model.Problem;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.context.request.WebRequest;

import java.util.UUID;
import java.util.function.Consumer;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger("lab.api.exception");
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleInvalidContract(WebRequest request) {
        Problem problem = new Problem(
                Problem.TypeEnum.URN_LAB_PROBLEM_HTTP_ERROR,
                "Invalid JSON contract",
                HttpStatus.BAD_REQUEST.value(),
                Problem.CodeEnum.INVALID_REQUEST,
                getRequestId(request));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler({AuthenticationException.class, UnauthorizedException.class})
    public ResponseEntity<lab.contracts.shop.model.Problem> handleUnauthorized(Exception ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("401 requestId={}: {}", requestId, ex.getMessage());

        return respond(HttpStatus.UNAUTHORIZED, lab.contracts.shop.model.Problem.CodeEnum.UNAUTHORIZED, requestId,
                headers -> headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<lab.contracts.shop.model.Problem> handleConflict(ConflictException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("409 requestId={}: {}", requestId, ex.getMessage());

        return respond(HttpStatus.CONFLICT, lab.contracts.shop.model.Problem.CodeEnum.CONFLICT, requestId, _ -> {
        });
    }

    @ExceptionHandler({BadRequestException.class, HttpClientErrorException.class})
    public ResponseEntity<lab.contracts.shop.model.Problem> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("400 requestId={}: {}", requestId, ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, lab.contracts.shop.model.Problem.CodeEnum.INVALID_REQUEST, requestId, _ -> {
        });
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<lab.contracts.shop.model.Problem> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("404 requestId={}: {}", requestId, ex.getMessage());
        return respond(HttpStatus.NOT_FOUND, lab.contracts.shop.model.Problem.CodeEnum.NOT_FOUND, requestId, _ -> {
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
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                Problem.CodeEnum.INTERNAL_ERROR,
                requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
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
