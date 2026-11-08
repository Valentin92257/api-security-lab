package lab.shop.api;

import lab.contracts.shop.model.LoginRequest;
import lab.contracts.shop.model.LoginResult;
import lab.contracts.shop.model.RegisterRequest;
import lab.contracts.shop.model.User;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.exception.ConflictException;
import lab.shop.api.exception.UnauthorizedException;
import lab.shop.application.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ActiveProfiles("protected")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthEndpointTest {

    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    private AuthService authService;

    @Autowired
    private JsonMapper jsonMapper;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    @DisplayName("Успешная регистрация возвращает 201 и пользователя")
    void registerReturnsCreatedUser() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(new User(1L, "alice", "Alice", "alice@example.com"));

        HttpResponse<String> response = post(BASE_PATH + "/auth/register",
                new RegisterRequest("alice", "password123", "Alice", "alice@example.com"));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.get("login").asString()).isEqualTo("alice");
        assertThat(body.get("id").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Регистрация с занятым login/email возвращает 409")
    void registerWithDuplicateReturnsConflict() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new ConflictException("Логин или email уже заняты", new RuntimeException()));

        HttpResponse<String> response = post(BASE_PATH + "/auth/register",
                new RegisterRequest("alice", "password123", "Alice", "alice@example.com"));

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("Регистрация с нарушением формата полей возвращает 400")
    void registerWithInvalidDataReturnsBadRequest() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new BadRequestException("login не указан"));

        HttpResponse<String> response = post(BASE_PATH + "/auth/register",
                new RegisterRequest("", "password123", "Alice", "alice@example.com"));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("Успешный вход возвращает 200 и токен")
    void loginReturnsToken() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResult("token-value", LoginResult.TokenTypeEnum.BEARER, 900));

        HttpResponse<String> response = post(BASE_PATH + "/auth/login", new LoginRequest("alice", "password123"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.get("accessToken").asString()).isEqualTo("token-value");
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("Неверные credentials возвращают 401 без раскрытия причины в теле")
    void loginWithWrongCredentialsReturnsUnauthorized() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new UnauthorizedException("Неверный логин или пароль"));

        HttpResponse<String> response = post(BASE_PATH + "/auth/login", new LoginRequest("alice", "wrong-password"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("Неверный логин или пароль");
    }

    @Test
    @DisplayName("register и login не требуют токена (security: [] в схеме)")
    void registerAndLoginDoNotRequireAuthorizationHeader() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(new User(1L, "alice", "Alice", "alice@example.com"));
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResult("token-value", LoginResult.TokenTypeEnum.BEARER, 900));

        HttpResponse<String> registerResponse = post(BASE_PATH + "/auth/register",
                new RegisterRequest("alice", "password123", "Alice", "alice@example.com"));
        HttpResponse<String> loginResponse = post(BASE_PATH + "/auth/login",
                new LoginRequest("alice", "password123"));

        assertThat(registerResponse.statusCode()).isNotEqualTo(401);
        assertThat(loginResponse.statusCode()).isNotEqualTo(401);
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        String json = jsonMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
