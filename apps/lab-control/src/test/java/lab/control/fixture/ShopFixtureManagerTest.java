package lab.control.fixture;

import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.model.CredentialPair;
import lab.control.fixture.model.FixtureManifest;
import lab.control.fixture.model.FixtureUser;
import lab.control.fixture.model.InvariantViolation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopFixtureManagerTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-25T12:00:00Z");

    private final ContractJsonCodec codec = new ContractJsonCodec();
    private final FixtureGenerator generator = new FixtureGenerator(codec);

    @Test
    void preparesBcryptHashesMatchingAllUsersAndOnlyValidCredentialPairs() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        ShopFixtureConnections connections = new ShopFixtureConnections(new ShopFixtureProperties("fixture_admin", "pw", "url-v", "url-p"));
        ShopFixtureManager manager = new ShopFixtureManager(generator, connections, codec, encoder);

        FixtureManifest manifest = manager.generate(42, BASE_TIME);
        PreparedFixture prepared = manager.prepare(manifest);

        assertThat(prepared.passwordHashesByUserId()).hasSize(24);
        assertThat(prepared.toString()).doesNotContain("$2a$");
        for (FixtureUser user : manifest.users()) {
            String hash = prepared.passwordHash(user.id());
            assertThat(hash).startsWith("$2a$");
            assertThat(encoder.matches(user.password(), hash)).isTrue();
        }
        for (CredentialPair pair : manifest.credentialStuffing().attempts()) {
            String hash = prepared.passwordHash(pair.userId());
            assertThat(encoder.matches(pair.password(), hash)).isEqualTo(pair.valid());
        }
    }

    @Test
    void throwsFixtureVerificationExceptionWhenInvariantsReportViolations() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<Long> longQuery = mock(JdbcClient.MappedQuerySpec.class);
        JdbcClient.MappedQuerySpec<InvariantViolation> violationQuery = mock(JdbcClient.MappedQuerySpec.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.param(anyString(), any(), anyInt())).thenReturn(statement);
        when(statement.update()).thenReturn(1);
        when(statement.query(Long.class)).thenReturn(longQuery);
        when(longQuery.single()).thenReturn(1L);
        when(statement.query(InvariantViolation.class)).thenReturn(violationQuery);
        InvariantViolation violation = new InvariantViolation("INV-COUPON-COUNT", "coupons", "1", "{\"expected\":1,\"actual\":0}");
        when(violationQuery.list()).thenReturn(List.of(violation));
        doAnswer(call -> {
            call.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        var targetConnection = new ShopFixtureConnections.TargetConnection(jdbc, transactions, null);
        var connections = new ShopFixtureConnections(Map.of(ShopTarget.PROTECTED, targetConnection));
        var manager = new ShopFixtureManager(generator, connections, codec, new BCryptPasswordEncoder(4));
        PreparedFixture prepared = manager.prepare(manager.generate(42, BASE_TIME));

        assertThatThrownBy(() -> manager.load(ShopTarget.PROTECTED, prepared))
                .isInstanceOf(FixtureVerificationException.class)
                .satisfies(error -> {
                    var verificationError = (FixtureVerificationException) error;
                    assertThat(verificationError.target()).isEqualTo(ShopTarget.PROTECTED);
                    assertThat(verificationError.violations()).containsExactly(violation);
                });
    }

    @Test
    void resolvesTargetUrlsAndRejectsMissingFixtureConnectionConfig() {
        var props = new ShopFixtureProperties("fixture_admin", "secret", "jdbc:v", "jdbc:p");
        assertThat(props.urlFor(ShopTarget.VULNERABLE)).isEqualTo("jdbc:v");
        assertThat(props.urlFor(ShopTarget.PROTECTED)).isEqualTo("jdbc:p");

        var missingUrl = new ShopFixtureConnections(new ShopFixtureProperties("fixture_admin", "secret", "", "jdbc:p"));
        assertThatThrownBy(() -> missingUrl.forTarget(ShopTarget.VULNERABLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("URL");

        var missingUser = new ShopFixtureConnections(new ShopFixtureProperties("", "secret", "jdbc:v", "jdbc:p"));
        assertThatThrownBy(() -> missingUser.forTarget(ShopTarget.VULNERABLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
    }
}
