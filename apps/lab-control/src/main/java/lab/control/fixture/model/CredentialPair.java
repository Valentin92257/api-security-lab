package lab.control.fixture.model;

public record CredentialPair(
        long userId,
        String login,
        String password,
        boolean valid
) {
    @Override
    public String toString() {
        return "CredentialPair[userId=" + userId + ", login=" + login + ", valid=" + valid + "]";
    }
}
