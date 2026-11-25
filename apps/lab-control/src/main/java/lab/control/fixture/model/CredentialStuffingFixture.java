package lab.control.fixture.model;

import java.util.List;

public record CredentialStuffingFixture(
        List<FixtureUser> victims,
        List<CredentialPair> attempts
) {
    public static final int EXPECTED_ATTEMPTS = 200;

    public CredentialStuffingFixture {
        victims = List.copyOf(victims);
        attempts = List.copyOf(attempts);
    }

    @Override
    public String toString() {
        return "CredentialStuffingFixture[victims=" + victims.size() + ", attempts=" + attempts.size() + "]";
    }
}
