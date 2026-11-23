package lab.detector.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lab.contracts.json.ContractPayloadTooLargeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class DetectorExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> validationError(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректные параметры запроса", HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Object> storageError(HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Хранилище Detector недоступно", HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpectedError(Exception error, HttpServletRequest request) {
        // JDBC/Jackson exception messages can contain payload; do not put them into operational logs.
        logger.error("Detector request failed; requestId=" + request.getAttribute(DetectorProblems.REQUEST_ID)
                + "; error=" + error.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка Detector", HttpHeaders.EMPTY, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        HttpStatus result = HttpStatus.valueOf(status.value());
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ContractPayloadTooLargeException) {
                result = HttpStatus.PAYLOAD_TOO_LARGE;
                break;
            }
        }
        String title = error instanceof ResponseStatusException response && response.getReason() != null
                ? response.getReason() : "HTTP-запрос не может быть обработан";
        return problem(result, title, headers, ((ServletWebRequest) request).getRequest());
    }

    private ResponseEntity<Object> problem(HttpStatus status, String title, HttpHeaders headers,
                                            HttpServletRequest request) {
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(DetectorProblems.create(status, title, request));
    }
}
