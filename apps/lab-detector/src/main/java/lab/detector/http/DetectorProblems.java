package lab.detector.http;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import lab.contracts.common.model.Problem;
import org.springframework.http.HttpStatus;

public final class DetectorProblems {
    public static final String REQUEST_ID = DetectorProblems.class.getName() + ".requestId";

    private DetectorProblems() {
    }

    public static Problem create(HttpStatus status, String title, HttpServletRequest request) {
        var code = switch (status) {
            case BAD_REQUEST, METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE -> Problem.CodeEnum.INVALID_REQUEST;
            case UNAUTHORIZED -> Problem.CodeEnum.UNAUTHORIZED;
            case FORBIDDEN -> Problem.CodeEnum.FORBIDDEN;
            case NOT_FOUND -> Problem.CodeEnum.NOT_FOUND;
            case CONFLICT -> Problem.CodeEnum.CONFLICT;
            case SERVICE_UNAVAILABLE -> Problem.CodeEnum.UNAVAILABLE;
            default -> Problem.CodeEnum.INTERNAL_ERROR;
        };
        var id = request.getAttribute(REQUEST_ID);
        return new Problem(Problem.TypeEnum.URN_LAB_PROBLEM_HTTP_ERROR, title, status.value(), code,
                id instanceof UUID uuid ? uuid : UUID.randomUUID());
    }
}
