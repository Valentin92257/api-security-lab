package lab.shop.api;

import lab.contracts.shop.model.Product;
import lab.shop.api.exception.BadRequestException;
import lab.shop.api.security.JwtService;
import lab.shop.application.service.ProductService;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ActiveProfiles("protected")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductsEndpointTest {

    private static final long CURRENT_USER_ID = 1L;
    private static final String BASE_PATH = "/api/v1";

    @LocalServerPort
    private int port;

    @MockitoBean
    private ProductService productService;

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
    @DisplayName("GET /products без параметра q передаёт пустую строку и сериализует поля Product")
    void searchWithoutQueryUsesEmptyString() throws Exception {
        when(productService.searchProducts("")).thenReturn(List.of(
                new Product(1L, "Amber Lamp", "Desk lighting", 1500, Product.CurrencyEnum.RUB)));

        HttpResponse<String> response = get("/products", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isOne();
        JsonNode item = body.get(0);
        assertThat(item.get("id").asLong()).isEqualTo(1L);
        assertThat(item.get("name").asString()).isEqualTo("Amber Lamp");
        assertThat(item.get("description").asString()).isEqualTo("Desk lighting");
        assertThat(item.get("price").asInt()).isEqualTo(1500);
        assertThat(item.get("currency").asString()).isEqualTo("RUB");
        assertThat(item.has("published")).isFalse();
        verify(productService).searchProducts("");
    }

    @Test
    @DisplayName("GET /products?q=... передаёт параметр в сервис и возвращает совпадения")
    void searchWithQueryPassesValueToService() throws Exception {
        when(productService.searchProducts("lamp")).thenReturn(List.of(
                new Product(2L, "Lamp", "Light", 500, Product.CurrencyEnum.RUB)));

        HttpResponse<String> response = get("/products?q=" + encode("lamp"), tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).size()).isOne();
        assertThat(json(response).get(0).get("id").asLong()).isEqualTo(2L);
        verify(productService).searchProducts("lamp");
    }

    @Test
    @DisplayName("GET /products без совпадений возвращает пустой JSON-массив")
    void searchWithNoMatchesReturnsEmptyArray() throws Exception {
        when(productService.searchProducts("absent")).thenReturn(List.of());

        HttpResponse<String> response = get("/products?q=absent", tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.isArray()).isTrue();
        assertThat(body.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("BadRequestException из сервиса возвращается как 400 INVALID_REQUEST")
    void invalidQueryReturnsBadRequest() throws Exception {
        String longQuery = "a".repeat(121);
        when(productService.searchProducts(longQuery)).thenThrow(new BadRequestException("слишком длинный q"));

        HttpResponse<String> response = get("/products?q=" + longQuery, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("GET /products без токена возвращает 401 и не обращается к сервису")
    void withoutTokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = get("/products", null);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(productService);
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
