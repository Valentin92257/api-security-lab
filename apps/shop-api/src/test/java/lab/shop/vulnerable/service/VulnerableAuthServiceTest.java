package lab.shop.vulnerable.service;

import lab.contracts.shop.model.LoginRequest;
import lab.contracts.shop.model.LoginResult;
import lab.contracts.shop.model.RegisterRequest;
import lab.contracts.shop.model.User;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.UnauthorizedException;
import lab.shop.api.security.JwtProperties;
import lab.shop.api.security.JwtService;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import lab.shop.application.repository.UserRepository.Credentials;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VulnerableAuthServiceTest {

    private static final JwtProperties JWT_PROPERTIES =
            new JwtProperties("test-secret-at-least-32-bytes-long-0123456789", Duration.ofMinutes(10));

    @Mock
    private UserRepository userRepository;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private VulnerableAuthService service;

    @BeforeEach
    void setUp() {
        service = new VulnerableAuthService(userRepository, cartRepository, passwordEncoder, jwtService, JWT_PROPERTIES);
    }

    @Test
    @DisplayName("register создаёт пользователя и пустую корзину, значения только trim (без lowercase)")
    void registerCreatesUserAndCart() {
        when(passwordEncoder.encode("secret123")).thenReturn("real-hash");
        when(userRepository.insert("Alice", "Alice Smith", "Alice@example.com", "real-hash")).thenReturn(10L);

        RegisterRequest request = new RegisterRequest("  Alice  ", "secret123", " Alice Smith ", " Alice@example.com ");
        User user = service.register(request);

        assertThat(user.getId()).isEqualTo(10L);
        assertThat(user.getLogin()).isEqualTo("Alice");   // регистр не приводится, в отличие от protected
        assertThat(user.getEmail()).isEqualTo("Alice@example.com");
        verify(cartRepository).createEmpty(10L);
    }

    @Test
    @DisplayName("Занятые login/email превращаются в ConflictException, корзина не создаётся")
    void registerWithDuplicateKeyIsConflict() {
        when(passwordEncoder.encode(any())).thenReturn("real-hash");
        when(userRepository.insert(any(), any(), any(), any())).thenThrow(new DuplicateKeyException("duplicate"));

        RegisterRequest request = new RegisterRequest("alice", "secret123", "Alice", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(ConflictException.class);
        verify(cartRepository, never()).createEmpty(anyLong());
    }

    @Test
    @DisplayName("Пустой login отклоняется до проверки остальных полей")
    void registerRejectsBlankLogin() {
        RegisterRequest request = new RegisterRequest(" ", "secret123", "Alice", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository, cartRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Пустое имя отклоняется")
    void registerRejectsBlankName() {
        RegisterRequest request = new RegisterRequest("alice", "secret123", " ", "alice@example.com");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository, cartRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Пустой email отклоняется")
    void registerRejectsBlankEmail() {
        RegisterRequest request = new RegisterRequest("alice", "secret123", "Alice", " ");

        assertThatThrownBy(() -> service.register(request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository, cartRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Уязвимость: формат login не проверяется, спецсимволы проходят")
    void registerDoesNotValidateLoginFormat() {
        when(passwordEncoder.encode(any())).thenReturn("real-hash");
        when(userRepository.insert(any(), any(), any(), any())).thenReturn(1L);

        RegisterRequest request = new RegisterRequest("ab' OR '1'='1", "secret123", "Alice", "alice@example.com");

        assertThat(service.register(request).getLogin()).isEqualTo("ab' OR '1'='1");
    }

    @Test
    @DisplayName("Уязвимость: длина пароля не проверяется, однобайтовый пароль проходит")
    void registerDoesNotValidatePasswordLength() {
        when(passwordEncoder.encode("x")).thenReturn("real-hash");
        when(userRepository.insert(any(), any(), any(), any())).thenReturn(1L);

        RegisterRequest request = new RegisterRequest("alice", "x", "Alice", "alice@example.com");

        assertThat(service.register(request)).isNotNull();
        verify(passwordEncoder).encode("x");
    }

    @Test
    @DisplayName("Корректные credentials возвращают токен и срок жизни из JwtProperties")
    void loginReturnsTokenForValidCredentials() {
        when(userRepository.findCredentialsByLogin("alice")).thenReturn(Optional.of(new Credentials(5L, "stored-hash")));
        when(passwordEncoder.matches("secret123", "stored-hash")).thenReturn(true);
        when(jwtService.issue(5L)).thenReturn("token-value");

        LoginResult result = service.login(new LoginRequest("  alice  ", "secret123"));

        assertThat(result.getAccessToken()).isEqualTo("token-value");
        assertThat(result.getTokenType()).isEqualTo(LoginResult.TokenTypeEnum.BEARER);
        assertThat(result.getExpiresIn()).isEqualTo(600);
    }

    @Test
    @DisplayName("Неверный пароль существующего пользователя даёт 401")
    void loginRejectsWrongPassword() {
        when(userRepository.findCredentialsByLogin("alice")).thenReturn(Optional.of(new Credentials(5L, "stored-hash")));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(UnauthorizedException.class);
        verify(jwtService, never()).issue(anyLong());
    }

    @Test
    @DisplayName("Уязвимость: для несуществующего логина хеш не сравнивается вообще "
            + "(в отличие от protected, здесь нет dummy-хеша — ответ по несуществующему "
            + "логину быстрее, что позволяет перебором узнавать существующие логины)")
    void loginForUnknownUserSkipsHashComparisonEntirely() {
        when(userRepository.findCredentialsByLogin("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("ghost", "anything")))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(passwordEncoder);
        verify(jwtService, never()).issue(anyLong());
    }

    @Test
    @DisplayName("Пустой login в login() отклоняется до обращения к репозиторию")
    void loginRejectsBlankLogin() {
        assertThatThrownBy(() -> service.login(new LoginRequest(" ", "secret123")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userRepository, passwordEncoder, jwtService);
    }
}
