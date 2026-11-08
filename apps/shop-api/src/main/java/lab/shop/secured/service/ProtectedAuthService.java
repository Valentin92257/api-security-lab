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
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import lab.shop.application.service.AuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Profile("protected")
public class ProtectedAuthService implements AuthService {

    private static final Pattern LOGIN_PATTERN = Pattern.compile("[a-z0-9_]{3,32}");
    private static final int PASSWORD_MIN_BYTES = 8;
    private static final int PASSWORD_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    /**
     * Хеш-пустышка: пароль проверяется и для несуществующего логина, чтобы время ответа не выдавало его.
     */
    private final String dummyHash;

    protected ProtectedAuthService(UserRepository userRepository,
                                   CartRepository cartRepository,
                                   PasswordEncoder passwordEncoder,
                                   JwtService jwtService,
                                   JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.cartRepository = cartRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.dummyHash = passwordEncoder.encode("dummy-password");
    }

    @Override
    @Transactional
    public User register(RegisterRequest request) {
        String login = normalizeLogin(request.getLogin());
        String name = requireText(request.getName(), "name");
        String email = requireText(request.getEmail(), "email").toLowerCase(Locale.ROOT);
        validatePassword(request.getPassword());

        String hash = passwordEncoder.encode(request.getPassword());
        try {
            long id = userRepository.insert(login, name, email, hash);
            cartRepository.createEmpty(id);
            return new User(id, login, name, email);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Логин или email уже заняты", e);
        }
    }

    @Override
    public LoginResult login(LoginRequest request) {
        String login = normalizeLogin(request.getLogin());
        validatePassword(request.getPassword());

        Optional<UserRepository.Credentials> credentials = userRepository.findCredentialsByLogin(login);
        boolean matches = passwordEncoder.matches(
                request.getPassword(),
                credentials.map(UserRepository.Credentials::passwordHash).orElse(dummyHash));

        if (credentials.isEmpty() || !matches) {
            throw new UnauthorizedException("Неверный логин или пароль");
        }
        return new LoginResult(
                jwtService.issue(credentials.get().id()),
                LoginResult.TokenTypeEnum.BEARER,
                (int) jwtProperties.ttl().toSeconds());
    }

    /**
     * trim и lowercase, затем [a-z0-9_]{3,32}.
     */
    private static String normalizeLogin(String raw) {
        String login = requireText(raw, "login").toLowerCase(Locale.ROOT);
        if (!LOGIN_PATTERN.matcher(login).matches()) {
            throw new BadRequestException("login не соответствует [a-z0-9_]{3,32}");
        }
        return login;
    }

    /**
     * 8-72 байта UTF-8, без нормализации.
     */
    private static void validatePassword(String password) {
        if (password == null) {
            throw new BadRequestException("password не указан");
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < PASSWORD_MIN_BYTES || bytes > PASSWORD_MAX_BYTES) {
            throw new BadRequestException("password должен занимать 8-72 байта UTF-8");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " не указан");
        }
        return value.trim();
    }
}