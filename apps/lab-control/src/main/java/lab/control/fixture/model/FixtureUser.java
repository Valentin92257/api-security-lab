package lab.control.fixture.model;

public record FixtureUser(
        long id,
        String login,
        String name,
        String email,
        String password,
        FixtureUserRole role
) {
    @Override
    public String toString() {
        return "FixtureUser[id=" + id + ", login=" + login + ", role=" + role + "]";
    }
}
