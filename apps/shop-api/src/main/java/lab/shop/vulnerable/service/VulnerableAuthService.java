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
import lab.shop.application.model.UserCredentials;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import lab.shop.application.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableAuthService implements AuthService {

    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    @Override
    @Transactional
    public User register(RegisterRequest request) {
        String login = requireText(request.getLogin(), "login");
        String name = requireText(request.getName(), "name");
        String email = requireText(request.getEmail(), "email");

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
        String login = requireText(request.getLogin(), "login");

        Optional<UserCredentials> credentials = userRepository.findCredentialsByLogin(login);
        boolean matches = false;
        if (credentials.isPresent()) {
            matches = passwordEncoder.matches(request.getPassword(), credentials.get().passwordHash());
        }

        if (credentials.isEmpty() || !matches) {
            throw new UnauthorizedException("Неверный логин или пароль");
        }
        return new LoginResult(
                jwtService.issue(credentials.get().id()),
                LoginResult.TokenTypeEnum.BEARER,
                (int) jwtProperties.ttl().toSeconds());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " не указан");
        }
        return value.trim();
    }
}