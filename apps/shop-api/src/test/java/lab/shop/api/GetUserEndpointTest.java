package lab.shop.api;

import lab.shop.api.security.JwtService;
import lab.shop.application.model.UserProfile;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class GetUserEndpointTest {

    protected static final long CURRENT_USER_ID = 1L;
    protected static final long OTHER_USER_ID = 2L;

    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    protected UserRepository userRepository;

    @MockitoBean
    private CartRepository cartRepository;

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
    @DisplayName("Свой профиль возвращается: 200 и поля User")
    void ownProfileIsReturned() throws Exception {
        when(userRepository.findProfile(CURRENT_USER_ID)).thenReturn(Optional.of(profile(CURRENT_USER_ID)));

        HttpResponse<String> response = get("/users/" + CURRENT_USER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("id").asLong()).isEqualTo(CURRENT_USER_ID);
        assertThat(body.get("login").asString()).isEqualTo("user1");
        assertThat(body.get("email").asString()).isEqualTo("user1@example.com");
    }

    @Test
    @DisplayName("Отсутствующий профиль: 404")
    void absentProfileIsNotFound() throws Exception {
        when(userRepository.findProfile(CURRENT_USER_ID)).thenReturn(Optional.empty());

        HttpResponse<String> response = get("/users/" + CURRENT_USER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Без токена: 401, до репозитория запрос не доходит")
    void withoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = get("/users/" + CURRENT_USER_ID, null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(userRepository);
    }

    protected UserProfile profile(long id) {
        return new UserProfile(id, "user" + id, "User " + id, "user" + id + "@example.com");
    }

    protected String tokenFor(long userId) {
        return jwtService.issue(userId);
    }

    protected HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode json(HttpResponse<String> response) throws Exception {
        return jsonMapper.readTree(response.body());
    }
}
