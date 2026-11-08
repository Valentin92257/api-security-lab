package lab.shop.secured.service;

import lab.contracts.shop.model.LoginRequest;
import lab.contracts.shop.model.LoginResult;
import lab.contracts.shop.model.RegisterRequest;
import lab.contracts.shop.model.User;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.UnauthorizedException;
import lab.shop.api.security.JwtProperties;
import lab.shop.api.security.JwtService;
import lab.shop.application.model.UserCredentials;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProtectedAuthServiceTest {

    private static final String DUMMY_HASH = "dummy-hash-value";
    private static final String VALID_PASSWORD = "password123";

    @Mock
    private UserRepository userRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private ProtectedAuthService service;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(
                "test-secret-at-least-32-bytes-long-0123456789",
                "urn:lab:shop:test", "shop-test", Duration.ofMinutes(10));
        when(passwordEncoder.encode("dummy-password")).thenReturn(DUMMY_HASH);
        service = new ProtectedAuthService(userRepository, cartRepository, passwordEncoder, jwtService, jwtProperties);
    }

    @Test
    @DisplayName("register нормализует login и создаёт пользователя с пустой корзиной")
    void registerCreatesUserAndCart() {
        when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn("real-hash");
        when(userRepository.insert("alice", "Alice", "alice@example.com", "real-hash")).thenReturn(10L);

        RegisterRequest request = new RegisterRequest("  Alice  ", VALID_PASSWORD, "Alice", "alice@example.com");
        User user = service.register(request);

        assertThat(user.getId()).isEqualTo(10L);
        assertThat(user.getLogin()).isEqualTo("alice");
        verify(cartRepository).createEmpty(10L);
    }

    @Test
    @DisplayName("Занятые login/email превращаются в ConflictException, корзина не создаётся")
    void registerWithDuplicateKeyIsConflict() {
        when(passwordEncoder.encode(VALID_PASSWORD)).thenReturn("real-hash");
        when(userRepository.insert("alice", "Alice", "alice@example.com", "real-hash"))
                .thenThrow(new DuplicateKeyException("duplicate"));

        RegisterRequest request = new RegisterRequest("alice", VALID_PASSWORD, "Alice", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(ConflictException.class);
        verify(cartRepository, never()).createEmpty(anyLong());
    }

    @Test
    @DisplayName("login короче 3 символов отклоняется до обращения к репозиторию")
    void registerRejectsTooShortLogin() {
        RegisterRequest request = new RegisterRequest("ab", VALID_PASSWORD, "Alice", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository, cartRepository);
    }

    @Test
    @DisplayName("login с недопустимыми символами отклоняется")
    void registerRejectsInvalidLoginCharacters() {
        RegisterRequest request = new RegisterRequest("alice!", VALID_PASSWORD, "Alice", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Пароль короче 8 байт отклоняется")
    void registerRejectsTooShortPassword() {
        RegisterRequest request = new RegisterRequest("alice", "short12", "Alice", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Пароль длиннее 72 байт отклоняется")
    void registerRejectsTooLongPassword() {
        RegisterRequest request = new RegisterRequest("alice", "x".repeat(73), "Alice", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Пустое имя отклоняется")
    void registerRejectsBlankName() {
        RegisterRequest request = new RegisterRequest("alice", VALID_PASSWORD, "  ", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Пустой email отклоняется")
    void registerRejectsBlankEmail() {
        RegisterRequest request = new RegisterRequest("alice", VALID_PASSWORD, "Alice", " ");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Корректные credentials возвращают токен и срок жизни из JwtProperties")
    void loginReturnsTokenForValidCredentials() {
        when(userRepository.findCredentialsByLogin("bob")).thenReturn(Optional.of(new UserCredentials(5L, "stored-hash")));
        when(passwordEncoder.matches(VALID_PASSWORD, "stored-hash")).thenReturn(true);
        when(jwtService.issue(5L)).thenReturn("token-value");

        LoginResult result = service.login(new LoginRequest("  Bob  ", VALID_PASSWORD));

        assertThat(result.getAccessToken()).isEqualTo("token-value");
        assertThat(result.getTokenType()).isEqualTo(LoginResult.TokenTypeEnum.BEARER);
        assertThat(result.getExpiresIn()).isEqualTo(600);
    }

    @Test
    @DisplayName("Неверный пароль существующего пользователя даёт 401, токен не выдаётся")
    void loginRejectsWrongPassword() {
        when(userRepository.findCredentialsByLogin("bob")).thenReturn(Optional.of(new UserCredentials(5L, "stored-hash")));
        when(passwordEncoder.matches(VALID_PASSWORD, "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("bob", VALID_PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        verify(jwtService, never()).issue(anyLong());
    }

    @Test
    @DisplayName("Несуществующий логин проверяется по dummy-хешу, чтобы не раскрыть его отсутствие")
    void loginForUnknownUserChecksDummyHash() {
        when(userRepository.findCredentialsByLogin("ghost")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(VALID_PASSWORD, DUMMY_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost", VALID_PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        verify(passwordEncoder).matches(VALID_PASSWORD, DUMMY_HASH);
        verify(jwtService, never()).issue(anyLong());
    }

    @Test
    @DisplayName("Пароль с недопустимой длиной отклоняется до обращения к репозиторию")
    void loginRejectsInvalidPasswordLength() {
        assertThatThrownBy(() -> service.login(new LoginRequest("bob", "short")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository, jwtService);
    }
}

