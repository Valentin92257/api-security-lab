package lab.shop.api;

import lab.contracts.shop.model.User;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.JwtService;
import lab.shop.application.service.UserService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ActiveProfiles("protected")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GetUserEndpointTest {

    private static final long CURRENT_USER_ID = 1L;
    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    private UserService userService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JsonMapper jsonMapper;

    private HttpClient client;

    @BeforeEach
    void setUpClient() {
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDownClient() {
        client.close();
    }

    @Test
    @DisplayName("GET /users/{userId} возвращает 200 и поля User")
    void profileIsReturned() throws Exception {
        when(userService.getUser(CURRENT_USER_ID))
                .thenReturn(new User(CURRENT_USER_ID, "user1", "User 1", "user1@example.com"));

        HttpResponse<String> response = get("/users/" + CURRENT_USER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(CURRENT_USER_ID);
        assertThat(body.get("login").asString()).isEqualTo("user1");
        assertThat(body.get("email").asString()).isEqualTo("user1@example.com");
    }

    @Test
    @DisplayName("NotFoundException из сервиса возвращается как 404 NOT_FOUND")
    void absentProfileIsNotFound() throws Exception {
        when(userService.getUser(CURRENT_USER_ID)).thenThrow(new NotFoundException("Пользователь не найден"));

        HttpResponse<String> response = get("/users/" + CURRENT_USER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Нечисловой userId возвращает 400 INVALID_REQUEST до вызова сервиса")
    void malformedIdReturnsBadRequest() throws Exception {
        HttpResponse<String> response = get("/users/not-a-number", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("Без токена: 401 с WWW-Authenticate, до сервиса запрос не доходит")
    void withoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = get("/users/" + CURRENT_USER_ID, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        verifyNoInteractions(userService);
    }

    private String tokenFor(long userId) {
        return jwtService.issue(userId);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }
}
