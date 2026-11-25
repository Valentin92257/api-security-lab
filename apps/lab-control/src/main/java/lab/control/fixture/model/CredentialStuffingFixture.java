package lab.control.fixture.model;

import java.util.List;

public record CredentialStuffingFixture(
        List<FixtureUser> victims,
        List<CredentialPair> attempts
) {
    public CredentialStuffingFixture {
        victims = List.copyOf(victims);
        attempts = List.copyOf(attempts);
    }

    @Override
    public String toString() {
        return "CredentialStuffingFixture[victims=" + victims.size() + ", attempts=" + attempts.size() + "]";
    }
}
