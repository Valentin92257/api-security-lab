package lab.shop.vulnerable.repository;

import lab.shop.application.model.UserCredentials;
import lab.shop.application.model.UserProfile;
import lab.shop.application.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableUserRepository implements UserRepository {

    private final JdbcClient jdbc;

    @Override
    public long insert(String login, String name, String email, String passwordHash) {
        String sql = """
                INSERT INTO users (login, name, email, password_hash)
                VALUES ('%s', $inj$%s$inj$, '%s', :passwordHash)
                RETURNING id
                """.formatted(login, name, email);

        return jdbc.sql(sql)
                .param("passwordHash", passwordHash)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<UserCredentials> findCredentialsByLogin(String login) {
        String sql = "SELECT id, password_hash FROM users WHERE login = '%s'".formatted(login);
        return jdbc.sql(sql)
                .query(UserCredentials.class)
                .optional();
    }

    @Override
    public Optional<UserProfile> findProfile(long id) {
        String sql = "SELECT id, login, name, email FROM users WHERE id = %s".formatted(id);

        return jdbc.sql(sql)
                .query(UserProfile.class)
                .optional();
    }
}