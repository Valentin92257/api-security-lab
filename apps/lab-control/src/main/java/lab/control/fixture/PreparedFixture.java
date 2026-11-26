package lab.control.fixture;

import lab.control.fixture.model.FixtureManifest;

import java.util.Map;

/**
 * {@link FixtureManifest} с предвычисленными BCrypt-хешами паролей, чтобы не хешировать
 * повторно 24 пароля при парной загрузке в {@code vulnerable} и {@code protected}.
 */
public record PreparedFixture(
        FixtureManifest manifest,
        Map<Long, String> passwordHashesByUserId
) {
    public PreparedFixture {
        passwordHashesByUserId = Map.copyOf(passwordHashesByUserId);
    }

    public String passwordHash(long userId) {
        String hash = passwordHashesByUserId.get(userId);
        if (hash == null || hash.isBlank()) {
            throw new IllegalStateException("Missing password hash for userId=" + userId);
        }
        return hash;
    }

    @Override
    public String toString() {
        return "PreparedFixture[manifest=" + manifest + ", hashedUsers=" + passwordHashesByUserId.size() + "]";
    }
}
