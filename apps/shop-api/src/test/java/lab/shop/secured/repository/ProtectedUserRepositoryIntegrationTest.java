package lab.shop.secured.repository;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("protected")
public class ProtectedUserRepositoryIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("insert создаёт пользователя, доступного через findCredentialsByLogin")
    void insertThenFindCredentials() {
        String login = uniqueLogin();

        long id = userRepository.insert(login, "Test User", login + "@example.com", "hash-value");

        Optional<UserRepository.Credentials> found = userRepository.findCredentialsByLogin(login);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().passwordHash()).isEqualTo("hash-value");
    }

    @Test
    @DisplayName("findCredentialsByLogin для несуществующего логина возвращает пусто")
    void findCredentialsForUnknownLoginIsEmpty() {
        assertThat(userRepository.findCredentialsByLogin(uniqueLogin())).isEmpty();
    }

    @Test
    @DisplayName("Повторный login нарушает уникальность")
    void duplicateLoginIsRejected() {
        String login = uniqueLogin();
        userRepository.insert(login, "First", login + "@example.com", "hash-1");

        assertThatThrownBy(() ->
                userRepository.insert(login, "Second", "other-" + login + "@example.com", "hash-2"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("Повторный email нарушает уникальность")
    void duplicateEmailIsRejected() {
        String login = uniqueLogin();
        String email = login + "@example.com";
        userRepository.insert(login, "First", email, "hash-1");

        assertThatThrownBy(() ->
                userRepository.insert("other-" + login, "Second", email, "hash-2"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    protected static String uniqueLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
