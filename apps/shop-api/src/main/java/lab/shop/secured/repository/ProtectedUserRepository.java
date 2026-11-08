package lab.shop.secured.repository;

import lab.shop.application.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedUserRepository implements UserRepository {

    private final JdbcClient jdbc;

    @Override
    public long insert(String login, String name, String email, String passwordHash) {
        return jdbc.sql("""
                        INSERT INTO users (login, name, email, password_hash)
                        VALUES (:login, :name, :email, :passwordHash)
                        RETURNING id
                        """)
                .param("login", login)
                .param("name", name)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Credentials> findCredentialsByLogin(String login) {
        return jdbc.sql("SELECT id, password_hash FROM users WHERE login = :login")
                .param("login", login)
                .query(Credentials.class)
                .optional();
    }
}
