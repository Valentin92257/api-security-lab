package lab.control.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenignSessionTest {
    @Test
    void shopperFactoryBuildsDefaultV0Sequence() {
        BenignSession session = BenignSession.shopper(
                "198.51.100.10", "cs_benign_01", "password-secret", "Keyboard", 4L, 2);

        assertThat(session.clientIp()).isEqualTo("198.51.100.10");
        assertThat(session.actions()).containsExactly(
                new BenignAction.Login("cs_benign_01", "password-secret"),
                new BenignAction.BrowseCatalog("Keyboard"),
                new BenignAction.SetCartItem(4L, 2));
        assertThat(session.toString()).doesNotContain("password-secret");
    }

    @Test
    void acceptsBoundaryValuesForCatalogAndCartActions() {
        assertThat(new BenignAction.BrowseCatalog(null).query()).isNull();
        assertThat(new BenignAction.BrowseCatalog("").query()).isEmpty();
        assertThat(new BenignAction.BrowseCatalog("a".repeat(120)).query()).hasSize(120);
        assertThat(new BenignAction.SetCartItem(1L, 1)).isEqualTo(new BenignAction.SetCartItem(1L, 1));
        assertThat(new BenignAction.SetCartItem(9007199254740991L, 99).quantity()).isEqualTo(99);
    }

    @Test
    void makesAnImmutableCopyOfActions() {
        List<BenignAction> mutable = new ArrayList<>();
        mutable.add(new BenignAction.Login("cs_benign_01", "password-secret"));
        BenignSession session = new BenignSession("198.51.100.10", mutable);

        mutable.add(new BenignAction.BrowseCatalog(null));

        assertThat(session.actions()).hasSize(1);
        assertThatThrownBy(() -> session.actions().add(new BenignAction.BrowseCatalog(null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "1", "1234567890123456789012345678901234567890123456"})
    void rejectsInvalidClientAddresses(String clientIp) {
        List<BenignAction> actions = List.of(new BenignAction.Login("cs_benign_01", "password-secret"));

        assertThatThrownBy(() -> new BenignSession(clientIp, actions))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyNullOrNonLoginStartingSessions() {
        assertThatThrownBy(() -> new BenignSession("198.51.100.10", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenignSession("198.51.100.10", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenignSession("198.51.100.10", List.of(new BenignAction.BrowseCatalog("mouse"))))
                .isInstanceOf(IllegalArgumentException.class);
        List<BenignAction> withNull = new ArrayList<>();
        withNull.add(new BenignAction.Login("cs_benign_01", "password-secret"));
        withNull.add(null);
        assertThatThrownBy(() -> new BenignSession("198.51.100.10", withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "ab, password123",
            "UPPER, password123",
            "bad-login, password123",
            "cs_benign_01, short77"
    })
    void rejectsInvalidLoginCredentials(String login, String password) {
        assertThatThrownBy(() -> new BenignAction.Login(login, password))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLongPasswordAndSearchQuery() {
        assertThatThrownBy(() -> new BenignAction.Login("cs_benign_01", "p".repeat(73)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenignAction.BrowseCatalog("q".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1",
            "-1, 1",
            "9007199254740992, 1",
            "1, 0",
            "1, -5",
            "1, 100"
    })
    void rejectsInvalidCartItemParameters(long productId, int quantity) {
        assertThatThrownBy(() -> new BenignAction.SetCartItem(productId, quantity))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
