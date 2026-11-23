package lab.shop.secured.repository;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.application.model.UserCredentials;
import lab.shop.application.model.UserProfile;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("protected")
public class ProtectedUserRepositoryIntegrationTest extends PostgreSQLIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$HASHMARKERHASHMARKERHASHMARKERHASHMARKERHASHMARKER";
    private static final String HASH_MARKER = "HASHMARKER";

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("insert создаёт пользователя, доступного через findUserCredentialsByLogin")
    void insertThenFindCredentials() {
        String login = uniqueLogin();

        long id = userRepository.insert(login, "Test User", login + "@example.com", "hash-value");

        Optional<UserCredentials> found = userRepository.findCredentialsByLogin(login);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().passwordHash()).isEqualTo("hash-value");
    }

    @Test
    @DisplayName("findUserCredentialsByLogin для несуществующего логина возвращает пусто")
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
    @DisplayName("findProfile возвращает собственный профиль")
    void findProfileReturnsOwnProfile() {
        String login = uniqueLogin();
        long id = userRepository.insert(login, "Test User", login + "@example.com", "hash");

        Optional<UserProfile> profile = userRepository.findProfile(id);

        assertThat(profile).contains(new UserProfile(id, login, "Test User", login + "@example.com"));
    }

    @Test
    @DisplayName("findProfile для несуществующего id возвращает пусто")
    void findProfileForUnknownIdIsEmpty() {
        assertThat(userRepository.findProfile(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("Повторный email нарушает уникальность, исключение не содержит хеш")
    void duplicateEmailIsRejected() {
        String login = uniqueLogin();
        String email = login + "@example.com";
        userRepository.insert(login, "First", email, "hash-1");

        assertThatThrownBy(() ->
                userRepository.insert("other-" + login, "Second", email, PASSWORD_HASH))
                .isInstanceOf(DuplicateKeyException.class)
                .satisfies(ProtectedUserRepositoryIntegrationTest::assertNoPasswordHash);
    }

    @Test
    @DisplayName("Границы длины: name 100 и email 254 символа принимаются")
    void maximumLengthsAreAccepted() {
        String login = uniqueLogin();
        String email = login + "a".repeat(254 - "@example.com".length() - login.length()) + "@example.com";

        long id = userRepository.insert(login, "n".repeat(100), email, "hash");

        UserProfile profile = userRepository.findProfile(id).orElseThrow();
        assertThat(profile.name()).hasSize(100);
        assertThat(profile.email()).hasSize(254);
    }

    @Test
    @DisplayName("name длиннее 100 символов нарушает ограничение БД, исключение не содержит хеш")
    void tooLongNameViolatesDatabaseConstraint() {
        String login = uniqueLogin();

        assertThatThrownBy(() -> userRepository.insert(login, "n".repeat(101), login + "@example.com", PASSWORD_HASH))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class)
                .satisfies(ProtectedUserRepositoryIntegrationTest::assertNoPasswordHash);
    }

    @Test
    @DisplayName("email длиннее 254 символов нарушает ограничение БД, исключение не содержит хеш")
    void tooLongEmailViolatesDatabaseConstraint() {
        String login = uniqueLogin();
        String email = login + "a".repeat(255 - "@example.com".length() - login.length()) + "@example.com";

        assertThatThrownBy(() -> userRepository.insert(login, "Test User", email, PASSWORD_HASH))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class)
                .satisfies(ProtectedUserRepositoryIntegrationTest::assertNoPasswordHash);
    }

    private static String uniqueLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static void assertNoPasswordHash(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append('\n');
        }
        assertThat(text.toString()).doesNotContain(HASH_MARKER);
    }
}
