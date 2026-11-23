package lab.shop.api;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

final class ProblemResponseWriter {

    static void write(HttpServletResponse response, String requestId, int status, String code, String title)
            throws IOException {
        response.setStatus(status);
        response.setHeader("X-Request-Id", requestId);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"urn:lab:problem:http-error","title":"%s","status":%d,"code":"%s","requestId":"%s"}
                """.formatted(title, status, code, requestId));
    }

    private ProblemResponseWriter() {
    }
}
