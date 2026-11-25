package lab.control.fixture;

import com.networknt.schema.*;
import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureGeneratorTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-25T12:00:00.123456789Z");

    private final ContractJsonCodec codec = new ContractJsonCodec();
    private final FixtureGenerator generator = new FixtureGenerator(codec);
    private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .formatAssertionsEnabled(true).locale(Locale.ENGLISH).build()));

    @Test
    void generatesDeterministicFullFixtureV1ForTheSameSeed() {
        FixtureManifest first = generator.generate(42, BASE_TIME);
        FixtureManifest second = generator.generate(42, BASE_TIME);
        FixtureManifest differentSeed = generator.generate(43, BASE_TIME);

        assertThat(first).isEqualTo(second);
        assertThat(first.manifestHash()).hasSize(64).isEqualTo(second.manifestHash());
        assertThat(first.businessTimeBase()).isEqualTo(Instant.parse("2026-10-25T12:00:00.123456Z"));
        assertThat(first.manifestHash()).isNotEqualTo(differentSeed.manifestHash());
        assertThat(first.credentialStuffing().attempts()).isNotEqualTo(differentSeed.credentialStuffing().attempts());
        assertThat(first.users().stream().map(FixtureUser::password).toList())
                .isNotEqualTo(differentSeed.users().stream().map(FixtureUser::password).toList());
    }

    @Test
    void satisfiesFixtureV1CardinalityCatalogCouponsAndBolaBindings() {
        FixtureManifest manifest = generator.generate(42, BASE_TIME);

        assertThat(manifest.version()).isEqualTo("v1");
        assertThat(manifest.users()).hasSize(24);
        assertThat(manifest.carts()).hasSize(24);
        assertThat(manifest.users().stream().map(FixtureUser::id).distinct()).hasSize(24);
        assertThat(manifest.users().stream().map(FixtureUser::login).distinct()).hasSize(24);
        assertThat(manifest.users().stream().map(FixtureUser::email).distinct()).hasSize(24);

        assertThat(manifest.products()).hasSize(13);
        assertThat(manifest.products().stream().filter(FixtureProduct::published)).hasSize(12);
        assertThat(manifest.products().stream().filter(FixtureProduct::canary))
                .containsExactly(manifest.draftCanary());
        assertThat(manifest.draftCanary().published()).isFalse();

        assertThat(manifest.coupons()).hasSize(5);
        assertThat(manifest.coupons().stream().map(FixtureCoupon::kind))
                .containsExactlyInAnyOrder(FixtureCoupon.Kind.values());
        assertThat(manifest.couponByKind(FixtureCoupon.Kind.EXPIRED).expiresAt())
                .isBefore(manifest.businessTimeBase());
        assertThat(manifest.couponByKind(FixtureCoupon.Kind.EXHAUSTED).usedCount())
                .isEqualTo(manifest.couponByKind(FixtureCoupon.Kind.EXHAUSTED).maxUses());
        assertThat(manifest.couponByKind(FixtureCoupon.Kind.LAST_USE).maxUses()
                - manifest.couponByKind(FixtureCoupon.Kind.LAST_USE).usedCount()).isEqualTo(1);
        assertThat(manifest.couponByKind(FixtureCoupon.Kind.FULL_DISCOUNT).discountPercent()).isEqualTo(100);

        BolaFixture bola = manifest.bola();
        assertThat(bola.victims()).hasSize(3);
        assertThat(bola.victimOrders()).hasSize(4);
        assertThat(bola.attackerOwnOrder().userId()).isEqualTo(bola.attacker().id());
        assertThat(bola.benignOwnOrder().userId()).isEqualTo(bola.benignUser().id());
        Set<Long> allUserIds = manifest.users().stream().map(FixtureUser::id).collect(Collectors.toSet());
        Set<Long> allOrderIds = manifest.orders().stream().map(FixtureOrder::id).collect(Collectors.toSet());
        assertThat(allUserIds).doesNotContain(bola.missingUserId());
        assertThat(allOrderIds).doesNotContain(bola.missingOrderId());
    }

    @Test
    void generatesTwoHundredCredentialStuffingPairsWithThreeValidAccounts() {
        FixtureManifest manifest = generator.generate(42, BASE_TIME);
        CredentialStuffingFixture cs = manifest.credentialStuffing();

        assertThat(cs.victims()).hasSize(3);
        assertThat(cs.attempts()).hasSize(200);
        assertThat(cs.attempts().stream().filter(CredentialPair::valid)).hasSize(3);
        assertThat(cs.attempts().stream().filter(pair -> !pair.valid())).hasSize(197);

        Map<Long, FixtureUser> victimsById = cs.victims().stream()
                .collect(Collectors.toMap(FixtureUser::id, Function.identity()));
        for (CredentialPair pair : cs.attempts()) {
            FixtureUser victim = victimsById.get(pair.userId());
            assertThat(victim).isNotNull();
            assertThat(pair.login()).isEqualTo(victim.login());
            if (pair.valid()) {
                assertThat(pair.password()).isEqualTo(victim.password());
            } else {
                assertThat(pair.password()).isNotEqualTo(victim.password());
            }
        }
    }

    @Test
    void historicalOrdersSatisfyShopOrderJsonSchemaAndBusinessInvariants() {
        FixtureManifest manifest = generator.generate(42, BASE_TIME);
        Schema orderSchema = schemas.getSchema(SchemaLocation.of("classpath:contracts/schemas/shop.yaml#/$defs/Order"));
        Map<Long, FixtureCoupon> couponsById = manifest.coupons().stream()
                .collect(Collectors.toMap(FixtureCoupon::id, Function.identity()));
        Map<Long, FixtureCart> cartsByUserId = manifest.carts().stream()
                .collect(Collectors.toMap(FixtureCart::userId, Function.identity()));

        for (FixtureOrder order : manifest.orders()) {
            JsonNode body = codec.jsonMapper().readTree(order.responseBody());
            assertThat(orderSchema.validate(body)).isEmpty();
            assertThat(body.get("id").asLong()).isEqualTo(order.id());
            assertThat(body.get("userId").asLong()).isEqualTo(order.userId());
            assertThat(body.get("total").asLong()).isEqualTo(order.total());

            assertThat(order.cartRevision()).isLessThan(cartsByUserId.get(order.userId()).revision());
            assertThat(order.items()).allMatch(item -> item.productId() != manifest.draftCanary().id());
            if (order.couponId() != null) {
                FixtureCoupon coupon = couponsById.get(order.couponId());
                assertThat(order.createdAt()).isBefore(coupon.expiresAt());
            }
        }

        JsonNode baseline = codec.jsonMapper().readTree(manifest.oracleBaseline().toJson(codec.jsonMapper()));
        assertThat(baseline.get("catalog")).hasSize(13);
        assertThat(baseline.get("coupons")).hasSize(5);
        assertThat(baseline.get("couponCounters")).hasSize(5);
        assertThat(baseline.get("orderIds")).hasSize(manifest.orders().size());
    }

    @Test
    void usersProductsCouponsAndLoginPairsSatisfyShopJsonSchemas() {
        FixtureManifest manifest = generator.generate(42, BASE_TIME);
        Schema userSchema = schemas.getSchema(SchemaLocation.of("classpath:contracts/schemas/shop.yaml#/$defs/User"));
        Schema productSchema = schemas.getSchema(SchemaLocation.of("classpath:contracts/schemas/shop.yaml#/$defs/Product"));
        Schema couponSchema = schemas.getSchema(SchemaLocation.of("classpath:contracts/schemas/shop.yaml#/$defs/OrderCoupon"));
        Schema loginSchema = schemas.getSchema(SchemaLocation.of("classpath:contracts/schemas/shop.yaml#/$defs/LoginRequest"));

        for (FixtureUser user : manifest.users()) {
            JsonNode node = codec.jsonMapper().createObjectNode()
                    .put("id", user.id())
                    .put("login", user.login())
                    .put("name", user.name())
                    .put("email", user.email());
            assertThat(userSchema.validate(node)).as("User %s", user.login()).isEmpty();
        }
        for (FixtureProduct product : manifest.products()) {
            JsonNode node = codec.jsonMapper().createObjectNode()
                    .put("id", product.id())
                    .put("name", product.name())
                    .put("description", product.description())
                    .put("price", product.price())
                    .put("currency", "RUB");
            assertThat(productSchema.validate(node)).as("Product %d", product.id()).isEmpty();
        }
        for (FixtureCoupon coupon : manifest.coupons()) {
            JsonNode node = codec.jsonMapper().createObjectNode()
                    .put("code", coupon.code())
                    .put("discountPercent", coupon.discountPercent());
            assertThat(couponSchema.validate(node)).as("Coupon %s", coupon.code()).isEmpty();
        }
        for (CredentialPair pair : manifest.credentialStuffing().attempts()) {
            JsonNode node = codec.jsonMapper().createObjectNode()
                    .put("login", pair.login())
                    .put("password", pair.password());
            assertThat(loginSchema.validate(node)).as("Pair %s", pair).isEmpty();
        }
    }

    @Test
    void hidesPasswordsInAllStringRepresentationsAndRejectsInvalidInputs() {
        FixtureManifest manifest = generator.generate(42, BASE_TIME);

        String combinedText = manifest.toString()
                + manifest.users()
                + manifest.credentialStuffing()
                + manifest.credentialStuffing().attempts();
        for (FixtureUser user : manifest.users()) {
            assertThat(combinedText).doesNotContain(user.password());
        }
        for (CredentialPair pair : manifest.credentialStuffing().attempts()) {
            assertThat(pair.toString()).doesNotContain(pair.password());
        }

        assertThatThrownBy(() -> generator.generate(-1, BASE_TIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.generate(0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 42, Integer.MAX_VALUE})
    void supportsAllBoundarySeedsAndExposesUsersByRole(int seed) {
        FixtureManifest manifest = generator.generate(seed, BASE_TIME);

        assertThat(manifest.seed()).isEqualTo(seed);
        assertThat(manifest.usersByRole(FixtureUserRole.CS_VICTIM)).hasSize(3);
        assertThat(manifest.usersByRole(FixtureUserRole.BOLA_VICTIM)).hasSize(3);
        assertThat(manifest.usersByRole(FixtureUserRole.EMPTY_CART_USER)).hasSize(1);
        assertThat(manifest.credentialStuffing().attempts()).hasSize(200);
    }

    @Test
    void supportsConfigurableUserCountAttemptsAndValidAccounts() {
        var customGenerator = new FixtureGenerator(codec, 30, 50, 5);
        FixtureManifest manifest = customGenerator.generate(42, BASE_TIME);

        assertThat(manifest.users()).hasSize(30);
        assertThat(manifest.carts()).hasSize(30);
        assertThat(manifest.credentialStuffing().victims()).hasSize(5);
        assertThat(manifest.credentialStuffing().attempts()).hasSize(50);
        assertThat(manifest.credentialStuffing().attempts().stream().filter(CredentialPair::valid)).hasSize(5);

        Schema userSchema = schemas.getSchema(SchemaLocation.of("classpath:contracts/schemas/shop.yaml#/$defs/User"));
        for (FixtureUser user : manifest.users()) {
            JsonNode node = codec.jsonMapper().createObjectNode()
                    .put("id", user.id())
                    .put("login", user.login())
                    .put("name", user.name())
                    .put("email", user.email());
            assertThat(userSchema.validate(node)).isEmpty();
        }

        assertThatThrownBy(() -> new FixtureGenerator(codec, 23, 200, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixtureGenerator(codec, 24, 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixtureGenerator(codec, 24, 50, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
