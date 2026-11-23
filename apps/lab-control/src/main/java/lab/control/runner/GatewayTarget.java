package lab.control.runner;

import java.net.URI;

public enum GatewayTarget {
    VULNERABLE("http://lab-gateway:8081"),
    PROTECTED("http://lab-gateway:8082");

    private final URI origin;

    GatewayTarget(String origin) {
        this.origin = URI.create(origin);
    }

    URI resolve(URI route) {
        return origin.resolve(route);
    }
}
