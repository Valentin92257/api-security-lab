package lab.control.http;

import java.beans.PropertyEditorSupport;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lab.control.trial.TrialException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ControlExceptionHandler extends ResponseEntityExceptionHandler {
    @ModelAttribute
    void validateQuery(HttpServletRequest request) {
        Set<String> allowed = request.getMethod().equals("GET") && request.getServletPath().endsWith("/trials")
                ? Set.of("limit", "offset") : Set.of();
        request.getParameterMap().forEach((name, values) -> {
            if (!allowed.contains(name) || values.length != 1 || !values[0].matches("[0-9]+")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректные query-параметры");
            }
        });
    }

    @InitBinder
    void strictUuid(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String input) {
                UUID id = UUID.fromString(input);
                if (!id.toString().equalsIgnoreCase(input)) {
                    throw new IllegalArgumentException("Expected a full UUID");
                }
                setValue(id);
            }
        });
    }

    @ExceptionHandler(TrialException.class)
    ResponseEntity<Object> trialError(TrialException error, HttpServletRequest request) {
        HttpStatus status = switch (error.reason()) {
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return problem(status, error.getMessage(), HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> validationError(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректные параметры запроса", HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Object> storageError(HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Хранилище Control недоступно", HttpHeaders.EMPTY, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpectedError(Exception error, HttpServletRequest request) {
        logger.error("Control request failed; requestId=" + request.getAttribute(ControlProblems.REQUEST_ID), error);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка Control", HttpHeaders.EMPTY, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved == HttpStatus.NOT_ACCEPTABLE) {
            resolved = HttpStatus.BAD_REQUEST;
        }
        String title = error instanceof ResponseStatusException response && response.getReason() != null
                ? response.getReason() : "HTTP-запрос не может быть обработан";
        return problem(resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR,
                title, headers, ((ServletWebRequest) request).getRequest());
    }

    private ResponseEntity<Object> problem(HttpStatus status, String title, HttpHeaders headers,
                                            HttpServletRequest request) {
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(ControlProblems.create(status, title, request));
    }
}
