package lab.control.fixture;

import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Детерминированный генератор полных фикстур магазина (Fixture v1) без обращения к БД.
 */
@Component
public class FixtureGenerator {
    public static final String VERSION = "v1";
    public static final int PUBLISHED_PRODUCT_COUNT = FixtureCatalog.PRODUCTS.size();
    public static final long MISSING_USER_ID = 999_001L;
    public static final long MISSING_ORDER_ID = 999_001L;

    private static final String CHECKOUT_CONTRACT_VERSION = "checkout-v1";
    private static final long CS_SEED_SALT = 0x517CC1B727220A95L;

    private final JsonMapper json;
    private final int userCount;
    private final int csAttempts;
    private final int csValidAccounts;

    @Autowired
    public FixtureGenerator(ContractJsonCodec codec, ShopFixtureProperties properties) {
        this.json = codec.jsonMapper();
        this.userCount = properties.userCount();
        this.csAttempts = properties.csAttempts();
        this.csValidAccounts = properties.csValidAccounts();
    }

    public FixtureGenerator(ContractJsonCodec codec) {
        this(codec, ShopFixtureProperties.DEFAULT_USER_COUNT,
                ShopFixtureProperties.DEFAULT_CS_ATTEMPTS,
                ShopFixtureProperties.DEFAULT_CS_VALID_ACCOUNTS);
    }

    public FixtureGenerator(ContractJsonCodec codec, int userCount, int csAttempts, int csValidAccounts) {
        this(codec, new ShopFixtureProperties("", "", "", "", userCount, csAttempts, csValidAccounts));
    }

    public FixtureManifest generate(int seed, Instant businessTimeBase) {
        if (seed < 0) {
            throw new IllegalArgumentException("Seed must be non-negative: " + seed);
        }
        Instant base = businessTimeBase.truncatedTo(ChronoUnit.MICROS);
        Random rnd = new Random(seed);

        List<FixtureUser> users = generateUsers(seed, rnd);
        List<FixtureProduct> products = generateProducts(seed, rnd);
        List<FixtureCoupon> coupons = generateCoupons(base, rnd);
        List<FixtureOrder> orders = generateOrders(seed, base, rnd, products, coupons);
        List<FixtureCart> carts = generateCarts(base, rnd, users, orders);

        BolaFixture bola = buildBolaFixture(users, orders);
        CredentialStuffingFixture credentialStuffing = generateCredentialStuffing(seed, users);
        OracleBaseline baseline = buildOracleBaseline(products, coupons, orders);
        String manifestHash = computeManifestHash(seed, base, users, carts, orders, credentialStuffing, baseline);

        return new FixtureManifest(VERSION, seed, base, manifestHash, users, products, coupons, carts, orders,
                bola, credentialStuffing, products.getLast(), baseline);
    }

    private List<FixtureUser> generateUsers(int seed, Random rnd) {
        List<FixtureUser> users = new ArrayList<>(userCount);
        for (FixtureCatalog.UserSpec spec : FixtureCatalog.USERS) {
            users.add(new FixtureUser(spec.id(), spec.login(), spec.name(),
                    spec.login() + "@example.com", generatePassword(seed, spec.id(), rnd), spec.role()));
        }
        for (long id = FixtureCatalog.USERS.size() + 1L; id <= userCount; ++id) {
            String login = "extra_user_" + id;
            users.add(new FixtureUser(id, login, "Extra User " + id,
                    login + "@example.com", generatePassword(seed, id, rnd), FixtureUserRole.CS_BENIGN));
        }
        return users;
    }

    private static String generatePassword(int seed, long userId, Random rnd) {
        String password = "fx" + seed + "_u" + userId + "_" + Long.toUnsignedString(rnd.nextLong(), 36);
        return password.length() > 48 ? password.substring(0, 48) : password;
    }

    private List<FixtureProduct> generateProducts(int seed, Random rnd) {
        List<FixtureCatalog.ProductSpec> shuffled = new ArrayList<>(FixtureCatalog.PRODUCTS);
        Collections.shuffle(shuffled, rnd);

        List<FixtureProduct> products = new ArrayList<>(PUBLISHED_PRODUCT_COUNT + 1);
        for (int i = 0; i < shuffled.size(); ++i) {
            FixtureCatalog.ProductSpec spec = shuffled.get(i);
            long maxStep = Math.max(1L, (spec.basePrice() / 5L) / 100L);
            long delta = (rnd.nextLong(maxStep * 2 + 1) - maxStep) * 100L;
            long price = Math.max(500L, spec.basePrice() + delta);
            products.add(new FixtureProduct(i + 1L, spec.name(), spec.description(), price, true, false));
        }
        String canaryToken = Long.toUnsignedString(rnd.nextLong(), 36);
        long canaryPrice = 50_000L + rnd.nextInt(500) * 100L;
        products.add(new FixtureProduct(PUBLISHED_PRODUCT_COUNT + 1L, "Draft Canary Item " + seed,
                "Internal unpublished canary marker canary_" + seed + "_" + canaryToken, canaryPrice, false, true));
        return products;
    }

    private List<FixtureCoupon> generateCoupons(Instant base, Random rnd) {
        int validDiscount = 10 + rnd.nextInt(16);
        int expiredDiscount = 10 + rnd.nextInt(21);
        int exhaustedDiscount = 15 + rnd.nextInt(21);
        int lastUseDiscount = 20 + rnd.nextInt(21);
        return List.of(
                new FixtureCoupon(1L, "VALID" + validDiscount, validDiscount, base.plus(Duration.ofHours(24)),
                        15 + rnd.nextInt(16), 1, FixtureCoupon.Kind.VALID),
                new FixtureCoupon(2L, "EXPIRED" + expiredDiscount, expiredDiscount,
                        base.minus(Duration.ofHours(1 + rnd.nextInt(4))), 10, 1, FixtureCoupon.Kind.EXPIRED),
                new FixtureCoupon(3L, "EXHAUSTED" + exhaustedDiscount, exhaustedDiscount, base.plus(Duration.ofHours(24)),
                        2, 2, FixtureCoupon.Kind.EXHAUSTED),
                new FixtureCoupon(4L, "LASTUSE" + lastUseDiscount, lastUseDiscount, base.plus(Duration.ofHours(24)),
                        2, 1, FixtureCoupon.Kind.LAST_USE),
                new FixtureCoupon(5L, "FREE100", 100, base.plus(Duration.ofHours(24)),
                        3 + rnd.nextInt(6), 0, FixtureCoupon.Kind.FULL_DISCOUNT)
        );
    }

    private List<FixtureOrder> generateOrders(
            int seed, Instant base, Random rnd, List<FixtureProduct> products, List<FixtureCoupon> coupons
    ) {
        List<FixtureProduct> published = products.stream().filter(FixtureProduct::published).toList();
        Map<Long, FixtureCoupon> couponsById = coupons.stream()
                .collect(Collectors.toMap(FixtureCoupon::id, Function.identity()));

        return FixtureCatalog.HISTORICAL_ORDERS.stream().map(plan -> {
            List<FixtureProduct> chosen = pickDistinctProducts(published, 1 + rnd.nextInt(3), rnd);
            List<FixtureOrder.Item> items = chosen.stream()
                    .map(p -> new FixtureOrder.Item(p.id(), p.name(), 1 + rnd.nextInt(4), p.price()))
                    .toList();

            long subtotal = items.stream().mapToLong(FixtureOrder.Item::lineTotal).reduce(0L, Math::addExact);
            FixtureCoupon coupon = plan.couponId() == null ? null : couponsById.get(plan.couponId());
            long discount = coupon == null ? 0L : Math.floorDiv(Math.multiplyExact(subtotal, coupon.discountPercent()), 100L);
            long total = Math.subtractExact(subtotal, discount);
            Instant latestAllowed = coupon != null && coupon.expiresAt().isBefore(base) ? coupon.expiresAt() : base;
            Instant createdAt = latestAllowed.minus(Duration.ofMinutes(60L + rnd.nextInt(300)));

            String keyHash = sha256Hex("fixture-key-v1:" + seed + ":" + plan.orderId());
            String fingerprint = sha256Hex("[\"%s\",\"POST\",\"/api/v1/orders\",%d,%d]"
                    .formatted(CHECKOUT_CONTRACT_VERSION, plan.cartRevision(), total));
            String responseBody = json.writeValueAsString(new OrderPayload(
                    plan.orderId(), plan.userId(), plan.userId(), plan.cartRevision(),
                    items.stream().map(i -> new LineItemPayload(i.productId(), i.name(), i.quantity(), i.unitPrice(), i.lineTotal())).toList(),
                    coupon == null ? null : new OrderCouponPayload(coupon.code(), coupon.discountPercent()),
                    subtotal, discount, total, "RUB", createdAt
            ));

            return new FixtureOrder(plan.orderId(), plan.userId(), plan.userId(), plan.cartRevision(),
                    subtotal, discount, total, plan.couponId(), createdAt, keyHash, fingerprint, responseBody, items);
        }).toList();
    }

    private List<FixtureCart> generateCarts(
            Instant base, Random rnd, List<FixtureUser> users, List<FixtureOrder> orders
    ) {
        Map<Long, Long> maxOrderRevisionByUser = orders.stream()
                .collect(Collectors.toMap(FixtureOrder::userId, FixtureOrder::cartRevision, Math::max));
        List<Long> publishedIds = new ArrayList<>(PUBLISHED_PRODUCT_COUNT);
        for (long id = 1; id <= PUBLISHED_PRODUCT_COUNT; ++id) {
            publishedIds.add(id);
        }

        return users.stream().map(user -> {
            long id = user.id();
            if (maxOrderRevisionByUser.containsKey(id)) {
                return new FixtureCart(id, id, null, maxOrderRevisionByUser.get(id) + 1L,
                        base.minus(Duration.ofMinutes(30)), List.of());
            }
            if (needsPrepopulatedCart(user.role())) {
                List<Long> picked = pickDistinctIds(publishedIds, 1 + rnd.nextInt(4), rnd);
                List<FixtureCart.Item> items = picked.stream()
                        .map(productId -> new FixtureCart.Item(productId, 1 + rnd.nextInt(5)))
                        .toList();
                return new FixtureCart(id, id, null, 1L, base.minus(Duration.ofMinutes(15)), items);
            }
            return new FixtureCart(id, id, null, 0L, base.minus(Duration.ofMinutes(45)), List.of());
        }).toList();
    }

    private boolean needsPrepopulatedCart(FixtureUserRole role) {
        return switch (role) {
            case REPLAY_USER, BL_COUPON_USER, BL_CART_RACE_USER, BL_COUPON_RACE_USER, BL_PRICE_USER,
                 BENIGN_CHECKOUT_USER -> true;
            default -> false;
        };
    }

    private static List<FixtureProduct> pickDistinctProducts(List<FixtureProduct> pool, int count, Random rnd) {
        List<FixtureProduct> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, rnd);
        return copy.subList(0, count);
    }

    private static List<Long> pickDistinctIds(List<Long> pool, int count, Random rnd) {
        List<Long> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, rnd);
        return copy.subList(0, count);
    }

    private BolaFixture buildBolaFixture(List<FixtureUser> users, List<FixtureOrder> orders) {
        FixtureUser attacker = firstByRole(users, FixtureUserRole.BOLA_ATTACKER);
        List<FixtureUser> victims = users.stream().filter(u -> u.role() == FixtureUserRole.BOLA_VICTIM).toList();
        FixtureUser benign = firstByRole(users, FixtureUserRole.BOLA_BENIGN);
        Set<Long> victimIds = victims.stream().map(FixtureUser::id).collect(Collectors.toSet());

        return new BolaFixture(
                attacker,
                victims,
                benign,
                orders.stream().filter(o -> o.userId() == attacker.id()).findFirst().orElseThrow(),
                orders.stream().filter(o -> victimIds.contains(o.userId())).toList(),
                orders.stream().filter(o -> o.userId() == benign.id()).findFirst().orElseThrow(),
                MISSING_USER_ID,
                MISSING_ORDER_ID
        );
    }

    private CredentialStuffingFixture generateCredentialStuffing(int seed, List<FixtureUser> users) {
        List<FixtureUser> victims = new ArrayList<>(csValidAccounts);
        users.stream().filter(u -> u.role() == FixtureUserRole.CS_VICTIM).limit(csValidAccounts).forEach(victims::add);
        if (victims.size() < csValidAccounts) {
            users.stream()
                    .filter(u -> u.role() != FixtureUserRole.CS_VICTIM)
                    .limit(csValidAccounts - victims.size())
                    .forEach(victims::add);
        }
        Random csrnd = new Random(Integer.toUnsignedLong(seed) ^ CS_SEED_SALT);

        List<CredentialPair> attempts = new ArrayList<>(csAttempts);
        victims.forEach(v -> attempts.add(new CredentialPair(v.id(), v.login(), v.password(), true)));
        while (attempts.size() < csAttempts) {
            FixtureUser target = victims.get(csrnd.nextInt(victims.size()));
            String invalidPassword = "invalid_" + Long.toUnsignedString(csrnd.nextLong(), 36);
            if (invalidPassword.equals(target.password())) {
                invalidPassword += "_wrong";
            }
            attempts.add(new CredentialPair(target.id(), target.login(), invalidPassword, false));
        }
        Collections.shuffle(attempts, csrnd);
        return new CredentialStuffingFixture(victims, attempts);
    }

    private OracleBaseline buildOracleBaseline(
            List<FixtureProduct> products, List<FixtureCoupon> coupons, List<FixtureOrder> orders
    ) {
        return new OracleBaseline(
                products.stream().map(p -> new OracleBaseline.CatalogEntry(p.id(), p.name(), p.description(), p.price(), p.published())).toList(),
                coupons.stream().map(c -> new OracleBaseline.CouponEntry(c.id(), c.code(), c.discountPercent(), c.expiresAt(), c.maxUses())).toList(),
                coupons.stream().map(c -> new OracleBaseline.CouponCounter(c.id(), c.usedCount())).toList(),
                orders.stream().map(FixtureOrder::id).toList()
        );
    }

    private String computeManifestHash(
            int seed, Instant base, List<FixtureUser> users, List<FixtureCart> carts,
            List<FixtureOrder> orders, CredentialStuffingFixture cs, OracleBaseline baseline
    ) {
        ManifestDigest digest = new ManifestDigest(
                VERSION,
                seed,
                base,
                baseline,
                users.stream().map(u -> new UserDigest(u.id(), u.login(), u.email(), u.role().name())).toList(),
                carts.stream().map(c -> new CartDigest(c.id(), c.revision(), c.items())).toList(),
                orders.stream().map(o -> new OrderDigest(o.id(), o.userId(), o.cartRevision(), o.total())).toList(),
                cs.attempts().stream().map(a -> new AttemptDigest(a.userId(), a.valid())).toList()
        );
        return sha256Hex(json.writeValueAsString(digest));
    }

    private static FixtureUser firstByRole(List<FixtureUser> users, FixtureUserRole role) {
        return users.stream().filter(u -> u.role() == role).findFirst().orElseThrow();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", error);
        }
    }

    private record OrderPayload(long id, long userId, long cartId, long cartRevision, List<LineItemPayload> items,
                                OrderCouponPayload coupon, long subtotal, long discount, long total,
                                String currency, Instant createdAt) {
    }

    private record LineItemPayload(long productId, String name, int quantity, long unitPrice, long lineTotal) {
    }

    private record OrderCouponPayload(String code, int discountPercent) {
    }

    private record ManifestDigest(String version, int seed, Instant businessTimeBase, OracleBaseline baseline,
                                  List<UserDigest> users, List<CartDigest> carts, List<OrderDigest> orders,
                                  List<AttemptDigest> credentialStuffingPattern) {
    }

    private record UserDigest(long id, String login, String email, String role) {
    }

    private record CartDigest(long id, long revision, List<FixtureCart.Item> items) {
    }

    private record OrderDigest(long id, long userId, long cartRevision, long total) {
    }

    private record AttemptDigest(long userId, boolean valid) {
    }
}
