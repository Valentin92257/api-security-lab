package lab.shop.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lab.contracts.common.model.Problem;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.exception.UnauthorizedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger("lab.api.exception");
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler({AuthenticationException.class, UnauthorizedException.class})
    public ResponseEntity<Problem> handleUnauthorized(RuntimeException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("401 requestId={}: {}", requestId, ex.getMessage());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return respond(HttpStatus.UNAUTHORIZED, headers, requestId);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Problem> handleConflict(ConflictException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("409 requestId={}: {}", requestId, ex.getMessage());

        return respond(HttpStatus.CONFLICT, HttpHeaders.EMPTY, requestId);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Problem> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("400 requestId={}: {}", requestId, ex.getMessage());

        return respond(HttpStatus.BAD_REQUEST, HttpHeaders.EMPTY, requestId);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Problem> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.info("404 requestId={}: {}", requestId, ex.getMessage());

        return respond(HttpStatus.NOT_FOUND, HttpHeaders.EMPTY, requestId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleGlobalException(Exception ex, HttpServletRequest request) {
        UUID requestId = getRequestId(request);
        log.error("500 requestId={}", requestId, ex);

        return respond(HttpStatus.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY, requestId);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                                       @Nullable Object body,
                                                                       HttpHeaders headers,
                                                                       HttpStatusCode statusCode,
                                                                       WebRequest request) {
        HttpServletRequest servletRequest = null;
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletResponse response = servletWebRequest.getResponse();
            if (response != null && response.isCommitted()) {
                return null;
            }
            servletRequest = servletWebRequest.getRequest();
        }

        UUID requestId = servletRequest != null ? getRequestId(servletRequest) : null;
        if (statusCode.is5xxServerError()) {
            log.error("{} requestId={}", statusCode.value(), requestId, ex);
        } else {
            log.info("{} requestId={}: {}", statusCode.value(), requestId, ex.getMessage());
        }

        ResponseEntity<Problem> response = respond(statusCode, headers, requestId);
        return new ResponseEntity<>(response.getBody(), response.getHeaders(), response.getStatusCode());
    }

    private ResponseEntity<Problem> respond(HttpStatusCode statusCode, HttpHeaders extraHeaders, @Nullable UUID requestId) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        String title = resolved != null ? resolved.getReasonPhrase() : "HTTP " + statusCode.value();
        Problem problem = new Problem(
                Problem.TypeEnum.URN_LAB_PROBLEM_HTTP_ERROR,
                title,
                statusCode.value(),
                codeFor(statusCode),
                requestId
        );

        HttpHeaders headers = new HttpHeaders();
        headers.putAll(extraHeaders);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, headers, statusCode);
    }

    private static Problem.CodeEnum codeFor(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400, 405, 406, 413, 415 -> Problem.CodeEnum.INVALID_REQUEST;
            case 401 -> Problem.CodeEnum.UNAUTHORIZED;
            case 403 -> Problem.CodeEnum.FORBIDDEN;
            case 404 -> Problem.CodeEnum.NOT_FOUND;
            case 409 -> Problem.CodeEnum.CONFLICT;
            case 429 -> Problem.CodeEnum.RATE_LIMITED;
            case 502, 503, 504 -> Problem.CodeEnum.UNAVAILABLE;
            default -> Problem.CodeEnum.INTERNAL_ERROR;
        };
    }

    private static @Nullable UUID getRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        try {
            return requestId != null ? UUID.fromString(requestId) : null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
