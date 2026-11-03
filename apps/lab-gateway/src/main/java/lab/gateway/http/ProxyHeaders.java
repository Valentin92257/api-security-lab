package lab.gateway.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.Header;

final class ProxyHeaders {
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection");

    static void copyRequest(HttpServletRequest incoming, HttpUriRequestBase outgoing) {
        var excluded = excluded(Collections.list(incoming.getHeaders("Connection")));
        excluded.addAll(Set.of("host", "expect", "x-request-id"));
        for (String name : Collections.list(incoming.getHeaderNames())) {
            if (!excluded.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : Collections.list(incoming.getHeaders(name))) {
                    outgoing.addHeader(name, value);
                }
            }
        }
    }

    static void copyResponse(Header[] headers, HttpServletResponse outgoing, boolean preserveContentLength) {
        var excluded = excluded(Arrays.stream(headers)
                .filter(header -> header.getName().equalsIgnoreCase("Connection"))
                .map(Header::getValue).toList());
        excluded.add("x-request-id");
        if (preserveContentLength) {
            excluded.remove("content-length");
        }
        for (Header header : headers) {
            if (!excluded.contains(header.getName().toLowerCase(Locale.ROOT))) {
                outgoing.addHeader(header.getName(), header.getValue());
            }
        }
    }

    static Set<String> excluded(Collection<String> connectionValues) {
        var excluded = new HashSet<>(HOP_BY_HOP);
        for (String value : connectionValues) {
            for (String token : value.split(",")) {
                excluded.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        excluded.add("content-length");
        return excluded;
    }

    private ProxyHeaders() {
    }
}
