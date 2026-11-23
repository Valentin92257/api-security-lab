package lab.control.runner;

import java.net.URI;
import java.util.regex.Pattern;

enum ShopRoute {
    REGISTER("POST", "/auth/register", "RegisterRequest", 201, "User"),
    LOGIN("POST", "/auth/login", "LoginRequest", 200, "LoginResult"),
    USER("GET", "/users/([1-9][0-9]{0,15})", null, 200, "User"),
    PRODUCTS("GET", "/products", null, 200, "Products"),
    CART("GET", "/cart", null, 200, "Cart"),
    CART_ITEM("PUT", "/cart/items/([1-9][0-9]{0,15})", "SetQuantityRequest", 200, "Cart"),
    CART_COUPON("PUT", "/cart/coupon", "SelectCouponRequest", 200, "Cart"),
    CHECKOUT("POST", "/orders", "CheckoutRequest", 201, "Order"),
    ORDER("GET", "/orders/([1-9][0-9]{0,15})", null, 200, "Order5");

    private final String method;
    private final Pattern path;
    private final String requestSchema;
    private final int successStatus;
    private final String responseSchema;

    ShopRoute(String method, String path, String requestSchema, int successStatus, String responseSchema) {
        this.method = method;
        this.path = Pattern.compile("/api/v1" + path);
        this.requestSchema = requestSchema;
        this.successStatus = successStatus;
        this.responseSchema = responseSchema;
    }

    String requestSchema() {
        return requestSchema;
    }

    int successStatus() {
        return successStatus;
    }

    String responseSchema() {
        return responseSchema;
    }

    static URI parse(String value) {
        URI route;
        try {
            route = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid step route");
        }
        if (route.isAbsolute() || route.getRawAuthority() != null || route.getRawFragment() != null) {
            throw new IllegalArgumentException("Step route must be a local catalog path");
        }
        return route;
    }

    static ShopRoute match(String method, URI route) {
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
                return entry;
            }
        }
        throw new IllegalArgumentException("Step route is not in the catalog");
    }
}
