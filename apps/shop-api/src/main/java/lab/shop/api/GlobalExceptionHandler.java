package lab.shop.api;

import lab.contracts.common.model.Problem;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleGlobalException(Exception ex, WebRequest request) {
        UUID requestId = getRequestId(request);

        Problem problem = new Problem(
                Problem.TypeEnum.URN_LAB_PROBLEM_HTTP_ERROR,
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                Problem.CodeEnum.INTERNAL_ERROR,
                requestId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private UUID getRequestId(WebRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);

        try {
            return requestId != null ? UUID.fromString(requestId) : null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}