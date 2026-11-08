package lab.shop.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {
    @Test
    void restoresPreviousMdcAfterRequest() throws Exception {
        String id = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.addHeader("X-Request-Id", id);
        MDC.put("trace_id", "outer");
        try {
            new RequestLoggingFilter().doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) ->
                    assertThat(MDC.get("trace_id")).isEqualTo(id));
            assertThat(MDC.get("trace_id")).isEqualTo("outer");
        } finally {
            MDC.remove("trace_id");
        }
    }
}
