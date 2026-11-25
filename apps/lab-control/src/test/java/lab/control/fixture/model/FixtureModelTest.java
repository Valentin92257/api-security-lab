package lab.control.fixture.model;

import lab.contracts.json.ContractJsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureModelTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Test
    void hidesPasswordsInToStringRepresentations() {
        FixtureUser user = new FixtureUser(1L, "cs_victim_01", "James Hetfield", "cs_victim_01@fixture.lab.invalid",
                "secret_password_1", FixtureUserRole.CS_VICTIM);
        CredentialPair pair = new CredentialPair(1L, "cs_victim_01", "secret_password_2", false);
        CredentialStuffingFixture cs = new CredentialStuffingFixture(List.of(user), List.of(pair));

        assertThat(user.toString()).doesNotContain("secret_password_1").contains("cs_victim_01");
        assertThat(pair.toString()).doesNotContain("secret_password_2").contains("cs_victim_01");
        assertThat(cs.toString()).doesNotContain("secret_password_1", "secret_password_2");
    }

    @Test
    void keepsCollectionsImmutableAndComputesLineTotal() {
        var mutable = new ArrayList<>(List.of(new FixtureCart.Item(1L, 2)));
        FixtureCart cart = new FixtureCart(1L, 1L, null, 1L, NOW, mutable);
        mutable.clear();

        assertThat(cart.items()).hasSize(1);
        assertThat(new FixtureOrder.Item(1L, "Keyboard", 3, 1_500L).lineTotal()).isEqualTo(4_500L);
    }

    @Test
    void serializesOracleBaselineInExactSqlFunctionFormat() {
        ContractJsonCodec codec = new ContractJsonCodec();
        OracleBaseline baseline = new OracleBaseline(
                List.of(new OracleBaseline.CatalogEntry(1L, "Item", "Desc", 1_000L, true)),
                List.of(new OracleBaseline.CouponEntry(2L, "VALID15", 15, NOW, 20L)),
                List.of(new OracleBaseline.CouponCounter(2L, 1L)),
                List.of(10L, 11L)
        );

        JsonNode root = codec.jsonMapper().readTree(baseline.toJson(codec.jsonMapper()));
        assertThat(root.get("catalog").get(0).get("product_id").asLong()).isEqualTo(1L);
        assertThat(root.get("catalog").get(0).get("published").asBoolean()).isTrue();
        assertThat(root.get("coupons").get(0).get("coupon_id").asLong()).isEqualTo(2L);
        assertThat(root.get("coupons").get(0).get("discount_percent").asInt()).isEqualTo(15);
        assertThat(root.get("coupons").get(0).get("expires_at").asString()).isEqualTo("2026-10-25T12:00:00Z");
        assertThat(root.get("coupons").get(0).get("max_uses").asLong()).isEqualTo(20L);
        assertThat(root.get("couponCounters").get(0).get("coupon_id").asLong()).isEqualTo(2L);
        assertThat(root.get("couponCounters").get(0).get("used_count").asLong()).isEqualTo(1L);
        assertThat(root.get("orderIds")).hasSize(2);
    }

    @Test
    void normalizesNullViolationDetailsToEmptyJsonObject() {
        assertThat(new InvariantViolation("INV-ORDER-TOTAL", "orders", "1", null).details()).isEqualTo("{}");
    }
}
