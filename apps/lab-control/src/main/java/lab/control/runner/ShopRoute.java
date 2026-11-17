package lab.control.runner;

import java.net.URI;
import java.util.regex.Pattern;

enum ShopRoute {
    REGISTER("POST", "/auth/register"),
    LOGIN("POST", "/auth/login"),
    USER("GET", "/users/([1-9][0-9]{0,15})"),
    PRODUCTS("GET", "/products"),
    CART("GET", "/cart"),
    CART_ITEM("PUT", "/cart/items/([1-9][0-9]{0,15})"),
    CART_COUPON("PUT", "/cart/coupon"),
    CHECKOUT("POST", "/orders"),
    ORDER("GET", "/orders/([1-9][0-9]{0,15})");

    private final String method;
    private final Pattern path;

    ShopRoute(String method, String path) {
        this.method = method;
        this.path = Pattern.compile("/api/v1" + path);
    }

    static URI validate(String method, String value) {
        URI route;
        try {
            route = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid step route");
        }
        if (route.isAbsolute() || route.getRawAuthority() != null || route.getRawFragment() != null) {
            throw new IllegalArgumentException("Step route must be a local catalog path");
        }
        for (ShopRoute entry : values()) {
            var match = entry.path.matcher(route.getRawPath());
            if (entry.method.equals(method) && match.matches()) {
                if (match.groupCount() > 0 && Long.parseLong(match.group(1)) > 9007199254740991L) {
                    break;
                }
                String query = route.getRawQuery();
                if (query != null && (entry != PRODUCTS || !query.startsWith("q=") || query.contains("&"))) {
                    break;
                }
                return route;
            }
        }
        throw new IllegalArgumentException("Step route is not in the catalog");
    }
}
