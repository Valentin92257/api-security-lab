package lab.control.fixture.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FixtureManifest(
        String version,
        int seed,
        Instant businessTimeBase,
        String manifestHash,
        List<FixtureUser> users,
        List<FixtureProduct> products,
        List<FixtureCoupon> coupons,
        List<FixtureCart> carts,
        List<FixtureOrder> orders,
        BolaFixture bola,
        CredentialStuffingFixture credentialStuffing,
        FixtureProduct draftCanary,
        OracleBaseline oracleBaseline
) {
    public FixtureManifest {
        users = List.copyOf(users);
        products = List.copyOf(products);
        coupons = List.copyOf(coupons);
        carts = List.copyOf(carts);
        orders = List.copyOf(orders);
    }

    public FixtureCoupon couponByKind(FixtureCoupon.Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return coupons.stream()
                .filter(coupon -> coupon.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing coupon kind: " + kind));
    }

    public List<FixtureUser> usersByRole(FixtureUserRole role) {
        Objects.requireNonNull(role, "role");
        return users.stream().filter(user -> user.role() == role).toList();
    }

    @Override
    public String toString() {
        return "FixtureManifest[version=" + version
                + ", seed=" + seed
                + ", businessTimeBase=" + businessTimeBase
                + ", manifestHash=" + manifestHash
                + ", users=" + users.size()
                + ", products=" + products.size()
                + ", coupons=" + coupons.size()
                + ", orders=" + orders.size()
                + ", csAttempts=" + credentialStuffing.attempts().size()
                + "]";
    }
}
