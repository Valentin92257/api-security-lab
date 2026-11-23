package lab.shop.api;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.api.security.AdjustableClockConfig;
import lab.shop.api.security.AdjustableClockConfig.AdjustableClock;
import lab.shop.api.security.JwtProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AdjustableClockConfig.class)
public abstract class AuthFlowIntegrationTest extends PostgreSQLIntegrationTest {

    private static final String BASE_PATH = "/api/v1";
    private static final String PASSWORD = "password123";

    @LocalServerPort
    private int port;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JsonMapper jsonMapper;

    private HttpClient client;

    @BeforeEach
    void setUpClient() {
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
        clock.reset();
    }

    @Test
    @DisplayName("Регистрация -> вход -> профиль: токен из login открывает собственный профиль")
    void registerLoginAndReadProfile() throws Exception {
        String login = uniqueLogin();
        String email = login + "@example.com";

        HttpResponse<String> registered = register(login, email);
        assertThat(registered.statusCode()).as("register: %s", registered.body()).isEqualTo(201);
        JsonNode user = json(registered);
        long id = user.get("id").asLong();
        assertThat(user.get("login").asString()).isEqualTo(login);
        assertThat(user.get("email").asString()).isEqualTo(email);
        assertThat(registered.body()).doesNotContainIgnoringCase("password");

        HttpResponse<String> loggedIn = login(login, PASSWORD);
        assertThat(loggedIn.statusCode()).as("login: %s", loggedIn.body()).isEqualTo(200);
        JsonNode tokenResult = json(loggedIn);
        assertThat(tokenResult.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(tokenResult.get("expiresIn").asLong()).isEqualTo(jwtProperties.ttl().toSeconds());
        String token = tokenResult.get("accessToken").asString();
        assertThat(token).isNotBlank();

        HttpResponse<String> profile = getProfile(id, token);
        assertThat(profile.statusCode()).as("profile: %s", profile.body()).isEqualTo(200);
        JsonNode body = json(profile);
        assertThat(body.get("id").asLong()).isEqualTo(id);
        assertThat(body.get("login").asString()).isEqualTo(login);
        assertThat(body.get("name").asString()).isEqualTo("Test User");
        assertThat(body.get("email").asString()).isEqualTo(email);
        assertThat(profile.body()).doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("Токен принимается до истечения срока и перестаёт приниматься после него")
    void tokenExpires() throws Exception {
        Account account = registerAndLogin();

        // середина срока жизни: токен ещё действителен
        clock.advance(jwtProperties.ttl().dividedBy(2));
        assertThat(getProfile(account.id(), account.token()).statusCode()).isEqualTo(200);

        // срок вышел (с запасом больше допуска по времени): 401, как у запроса без токена
        clock.advance(jwtProperties.ttl());
        HttpResponse<String> expired = getProfile(account.id(), account.token());
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    @DisplayName("Повторная регистрация того же login/email: 409")
    void registerTwiceIsConflict() throws Exception {
        String login = uniqueLogin();
        String email = login + "@example.com";
        assertThat(register(login, email).statusCode()).isEqualTo(201);

        HttpResponse<String> again = register(login, email);

        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("Неверный пароль: 401, токен не выдаётся")
    void wrongPasswordIsUnauthorized() throws Exception {
        String login = uniqueLogin();
        assertThat(register(login, login + "@example.com").statusCode()).isEqualTo(201);

        HttpResponse<String> response = login(login, "wrong-password-1");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("accessToken");
    }

    private record Account(long id, String login, String token) {
    }

    private Account registerAndLogin() throws Exception {
        String login = uniqueLogin();
        HttpResponse<String> registered = register(login, login + "@example.com");
        assertThat(registered.statusCode()).as("register: %s", registered.body()).isEqualTo(201);
        long id = json(registered).get("id").asLong();

        HttpResponse<String> loggedIn = login(login, PASSWORD);
        assertThat(loggedIn.statusCode()).as("login: %s", loggedIn.body()).isEqualTo(200);
        return new Account(id, login, json(loggedIn).get("accessToken").asString());
    }

    private HttpResponse<String> register(String login, String email) throws Exception {
        return post("/auth/register", Map.of(
                "login", login,
                "password", PASSWORD,
                "name", "Test User",
                "email", email));
    }

    private HttpResponse<String> login(String login, String password) throws Exception {
        return post("/auth/login", Map.of("login", login, "password", password));
    }

    private HttpResponse<String> getProfile(long id, String token) throws Exception {
        HttpRequest request = request("/users/" + id)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }

    private static String uniqueLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
