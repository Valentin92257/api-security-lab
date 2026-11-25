package lab.shop.api;

import lab.shop.PostgreSQLIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class OrderFlowIntegrationTest extends PostgreSQLIntegrationTest {

    private static final String BASE_PATH = "/api/v1";
    private static final String PASSWORD = "password123";
    private static final String INJECTION_PAYLOAD = "')) > 0) OR published = false -- ";

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    private HttpClient client;
    private long lampId;
    private long draftId;

    protected abstract boolean isProtected();

    @BeforeEach
    void setUp() {
        client = HttpClient.newHttpClient();
        cleanDatabase();
        lampId = insertProduct("Amber Lamp", "Desk lighting", 1500, true);
        draftId = insertProduct("Draft Canary", "Hidden unpublished product", 999, false);
    }

    @AfterEach
    void tearDown() {
        client.close();
        cleanDatabase();
    }

    @Test
    @DisplayName("Полный benign-цикл: поиск -> корзина -> quantity/no-op -> купон/no-op -> checkout -> GET order")
    void fullBenignFlow() throws Exception {
        Account user = registerAndLogin();
        long couponId = insertCoupon("SAVE10", 10, Instant.now().plusSeconds(3600), 5, 0);

        // 1. Поиск каталога
        HttpResponse<String> products = get("/products?q=Amber", user.token());
        assertThat(products.statusCode()).isEqualTo(200);
        assertThat(json(products).size()).isOne();
        assertThat(json(products).get(0).get("id").asLong()).isEqualTo(lampId);

        // 2. Пустая корзина
        HttpResponse<String> emptyCart = get("/cart", user.token());
        assertThat(emptyCart.statusCode()).isEqualTo(200);
        assertThat(json(emptyCart).get("revision").asLong()).isZero();
        assertThat(json(emptyCart).get("total").asLong()).isZero();

        // 3. Добавление товара и no-op повтор
        HttpResponse<String> withItem = put("/cart/items/" + lampId, Map.of("quantity", 2), user.token());
        assertThat(withItem.statusCode()).isEqualTo(200);
        assertThat(json(withItem).get("revision").asLong()).isEqualTo(1L);
        assertThat(json(withItem).get("subtotal").asLong()).isEqualTo(3000L);

        HttpResponse<String> sameItem = put("/cart/items/" + lampId, Map.of("quantity", 2), user.token());
        assertThat(sameItem.statusCode()).isEqualTo(200);
        assertThat(json(sameItem).get("revision").asLong()).isEqualTo(1L);

        // 4. Выбор купона (с trim/uppercase) и no-op повтор
        HttpResponse<String> withCoupon = put("/cart/coupon", Map.of("code", "  save10 "), user.token());
        assertThat(withCoupon.statusCode()).isEqualTo(200);
        assertThat(json(withCoupon).get("revision").asLong()).isEqualTo(2L);
        assertThat(json(withCoupon).get("discount").asLong()).isEqualTo(300L);
        assertThat(json(withCoupon).get("total").asLong()).isEqualTo(2700L);

        HttpResponse<String> sameCoupon = put("/cart/coupon", Map.of("code", "SAVE10"), user.token());
        assertThat(sameCoupon.statusCode()).isEqualTo(200);
        assertThat(json(sameCoupon).get("revision").asLong()).isEqualTo(2L);

        // 5. Checkout
        HttpResponse<String> created = checkout(user.token(), newKey(), 2L, 2700L);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Idempotency-Replayed")).contains("false");
        JsonNode order = json(created);
        long orderId = order.get("id").asLong();
        assertThat(order.get("userId").asLong()).isEqualTo(user.id());
        assertThat(order.get("subtotal").asLong()).isEqualTo(3000L);
        assertThat(order.get("discount").asLong()).isEqualTo(300L);
        assertThat(order.get("total").asLong()).isEqualTo(2700L);
        assertThat(order.get("coupon").get("code").asString()).isEqualTo("SAVE10");

        // 6. Чтение собственного заказа
        HttpResponse<String> fetched = get("/orders/" + orderId, user.token());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(json(fetched)).isEqualTo(order);

        assertThat(couponUsedCount(couponId)).isEqualTo(1L);
        HttpResponse<String> cartAfter = get("/cart", user.token());
        if (isProtected()) {
            assertThat(json(cartAfter).get("revision").asLong()).isEqualTo(3L);
            assertThat(json(cartAfter).get("items").isEmpty()).isTrue();
            assertThat(json(cartAfter).get("coupon").isNull()).isTrue();
        } else {
            assertThat(json(cartAfter).get("revision").asLong()).isEqualTo(2L);
            assertThat(json(cartAfter).get("items").size()).isOne();
        }
    }

    @Test
    @DisplayName("IJ-01: SQL-инъекция в GET /products?q=... скрывает draft в protected и раскрывает в vulnerable")
    void sqlInjectionInProductSearch() throws Exception {
        Account user = registerAndLogin();

        HttpResponse<String> response = get("/products?q=" + encode(INJECTION_PAYLOAD), user.token());

        assertThat(response.statusCode()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        json(response).forEach(node -> ids.add(node.get("id").asLong()));
        if (isProtected()) {
            assertThat(ids).doesNotContain(draftId);
        } else {
            assertThat(ids).contains(draftId);
        }
    }

    @Test
    @DisplayName("BO-01: чтение чужого заказа возвращает 404 в protected и 200 в vulnerable")
    void foreignOrderAccess() throws Exception {
        Account owner = registerAndLogin();
        Account attacker = registerAndLogin();

        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), owner.token()).statusCode()).isEqualTo(200);
        HttpResponse<String> created = checkout(owner.token(), newKey(), 1L, 1500L);
        assertThat(created.statusCode()).isEqualTo(201);
        long orderId = json(created).get("id").asLong();

        HttpResponse<String> stolen = get("/orders/" + orderId, attacker.token());
        if (isProtected()) {
            assertThat(stolen.statusCode()).isEqualTo(404);
            assertThat(stolen.body()).doesNotContain("Amber Lamp");
        } else {
            assertThat(stolen.statusCode()).isEqualTo(200);
            assertThat(json(stolen).get("userId").asLong()).isEqualTo(owner.id());
        }
    }

    @Test
    @DisplayName("RP-01: повтор того же Idempotency-Key и тела после очистки корзины и истечения купона")
    void replaySameKeyAndBodyAfterCheckout() throws Exception {
        Account user = registerAndLogin();
        long couponId = insertCoupon("REPLAY10", 10, Instant.now().plusSeconds(3600), 5, 0);
        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), user.token()).statusCode()).isEqualTo(200);
        assertThat(put("/cart/coupon", Map.of("code", "REPLAY10"), user.token()).statusCode()).isEqualTo(200);

        String key = newKey();
        HttpResponse<String> first = checkout(user.token(), key, 2L, 1350L);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).contains("false");
        long firstOrderId = json(first).get("id").asLong();

        // Купон истекает после первого успешного checkout
        jdbc.sql("UPDATE coupons SET expires_at = :expired WHERE id = :id")
                .param("expired", Timestamp.from(Instant.now().minusSeconds(60)))
                .param("id", couponId)
                .update();

        HttpResponse<String> replay = checkout(user.token(), key, 2L, 1350L);
        assertThat(replay.statusCode()).isEqualTo(201);
        if (isProtected()) {
            assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
            assertThat(json(replay).get("id").asLong()).isEqualTo(firstOrderId);
            assertThat(ordersCountForUser(user.id())).isEqualTo(1);
        } else {
            assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("false");
            assertThat(json(replay).get("id").asLong()).isNotEqualTo(firstOrderId);
            assertThat(ordersCountForUser(user.id())).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("RP-02: тот же ключ с другим телом и новый ключ на оформленную cartRevision")
    void replayConflictCases() throws Exception {
        Account user = registerAndLogin();
        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), user.token()).statusCode()).isEqualTo(200);

        String key1 = newKey();
        assertThat(checkout(user.token(), key1, 1L, 1500L).statusCode()).isEqualTo(201);

        HttpResponse<String> differentBody = checkout(user.token(), key1, 1L, 1400L);
        HttpResponse<String> newKeySameRevision = checkout(user.token(), newKey(), 1L, 1500L);

        if (isProtected()) {
            assertThat(differentBody.statusCode()).isEqualTo(409);
            assertThat(newKeySameRevision.statusCode()).isEqualTo(409);
            assertThat(ordersCountForUser(user.id())).isEqualTo(1);
        } else {
            assertThat(differentBody.statusCode()).isEqualTo(201);
            assertThat(newKeySameRevision.statusCode()).isEqualTo(201);
            assertThat(ordersCountForUser(user.id())).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("BL-01: истёкший и исчерпанный купоны отклоняются в protected и применяются в vulnerable")
    void expiredAndExhaustedCoupons() throws Exception {
        long expiredId = insertCoupon("EXPIRED20", 20, Instant.now().minusSeconds(60), 5, 0);
        long exhaustedId = insertCoupon("EXHAUSTED20", 20, Instant.now().plusSeconds(3600), 1, 1);

        Account userA = registerAndLogin();
        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), userA.token()).statusCode()).isEqualTo(200);
        HttpResponse<String> expiredCart = put("/cart/coupon", Map.of("code", "EXPIRED20"), userA.token());
        assertThat(expiredCart.statusCode()).isEqualTo(200);
        assertThat(json(expiredCart).get("coupon").get("eligibility").asString()).isEqualTo("EXPIRED");
        assertThat(json(expiredCart).get("discount").asLong()).isZero();

        HttpResponse<String> expiredCheckout = checkout(userA.token(), newKey(), 2L, 1200L);

        Account userB = registerAndLogin();
        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), userB.token()).statusCode()).isEqualTo(200);
        HttpResponse<String> exhaustedCart = put("/cart/coupon", Map.of("code", "EXHAUSTED20"), userB.token());
        assertThat(exhaustedCart.statusCode()).isEqualTo(200);
        assertThat(json(exhaustedCart).get("coupon").get("eligibility").asString()).isEqualTo("EXHAUSTED");

        HttpResponse<String> exhaustedCheckout = checkout(userB.token(), newKey(), 2L, 1200L);

        if (isProtected()) {
            assertThat(expiredCheckout.statusCode()).isEqualTo(409);
            assertThat(exhaustedCheckout.statusCode()).isEqualTo(409);
            assertThat(couponUsedCount(expiredId)).isZero();
            assertThat(couponUsedCount(exhaustedId)).isEqualTo(1L);
            assertThat(ordersCountForUser(userA.id())).isZero();
            assertThat(ordersCountForUser(userB.id())).isZero();
        } else {
            assertThat(expiredCheckout.statusCode()).isEqualTo(201);
            assertThat(exhaustedCheckout.statusCode()).isEqualTo(201);
            assertThat(couponUsedCount(expiredId)).isEqualTo(1L);
            assertThat(couponUsedCount(exhaustedId)).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("BL-02: quantity 0 / -1 / 100 отклоняются в protected и сохраняются/оформляются в vulnerable")
    void invalidQuantities() throws Exception {
        Account user = registerAndLogin();

        HttpResponse<String> zero = put("/cart/items/" + lampId, Map.of("quantity", 0), user.token());
        HttpResponse<String> negative = put("/cart/items/" + lampId, Map.of("quantity", -1), user.token());
        HttpResponse<String> overLimit = put("/cart/items/" + lampId, Map.of("quantity", 100), user.token());

        if (isProtected()) {
            assertThat(zero.statusCode()).isEqualTo(400);
            assertThat(negative.statusCode()).isEqualTo(400);
            assertThat(overLimit.statusCode()).isEqualTo(400);
            assertThat(json(get("/cart", user.token())).get("items").isEmpty()).isTrue();
        } else {
            assertThat(zero.statusCode()).isEqualTo(200);
            assertThat(negative.statusCode()).isEqualTo(200);
            assertThat(overLimit.statusCode()).isEqualTo(200);
            HttpResponse<String> order = checkout(user.token(), newKey(), 3L, 150_000L);
            assertThat(order.statusCode()).isEqualTo(201);
            assertThat(json(order).get("items").get(0).get("quantity").asInt()).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("BL-04: заниженный quotedTotal отклоняется в protected и принимается как цена заказа в vulnerable")
    void tamperedQuotedTotal() throws Exception {
        Account user = registerAndLogin();
        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), user.token()).statusCode()).isEqualTo(200);

        HttpResponse<String> response = checkout(user.token(), newKey(), 1L, 1L);

        if (isProtected()) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(ordersCountForUser(user.id())).isZero();
        } else {
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(json(response).get("total").asLong()).isEqualTo(1L);
            assertThat(ordersCountForUser(user.id())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("BL-03 (cart race): конкурентный checkout одной cartRevision с разными ключами")
    void concurrentCartCheckoutRace() throws Exception {
        Account user = registerAndLogin();
        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), user.token()).statusCode()).isEqualTo(200);

        int attempts = 5;
        List<HttpResponse<String>> responses = runConcurrently(attempts,
                _ -> checkout(user.token(), newKey(), 1L, 1500L));

        long createdCount = responses.stream().filter(r -> r.statusCode() == 201).count();
        long conflictCount = responses.stream().filter(r -> r.statusCode() == 409).count();

        if (isProtected()) {
            assertThat(createdCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(attempts - 1);
            assertThat(ordersCountForUser(user.id())).isEqualTo(1);
        } else {
            assertThat(createdCount).isGreaterThan(1);
            assertThat(ordersCountForUser(user.id())).isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("RP-01 (parallel same key): конкурентные повторы одного Idempotency-Key сериализуются")
    void concurrentSameIdempotencyKeyReplay() throws Exception {
        Account user = registerAndLogin();
        assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), user.token()).statusCode()).isEqualTo(200);

        String sameKey = newKey();
        int attempts = 5;
        List<HttpResponse<String>> responses =
                runConcurrently(attempts, _ -> checkout(user.token(), sameKey, 1L, 1500L));

        if (isProtected()) {
            List<HttpResponse<String>> created = responses.stream()
                    .filter(r -> r.statusCode() == 201)
                    .toList();
            long freshCount = created.stream()
                    .filter(r -> r.headers().firstValue("Idempotency-Replayed").equals(java.util.Optional.of("false")))
                    .count();
            long replayedCount = created.stream()
                    .filter(r -> r.headers().firstValue("Idempotency-Replayed").equals(java.util.Optional.of("true")))
                    .count();
            List<Long> orderIds = created.stream()
                    .map(r -> json(r).get("id").asLong())
                    .distinct()
                    .toList();

            assertThat(created).hasSize(attempts);
            assertThat(freshCount).isEqualTo(1);
            assertThat(replayedCount).isEqualTo(attempts - 1);
            assertThat(orderIds).hasSize(1);
            assertThat(ordersCountForUser(user.id())).isEqualTo(1);
        } else {
            long createdCount = responses.stream().filter(r -> r.statusCode() == 201).count();
            assertThat(createdCount).isGreaterThan(1);
            assertThat(ordersCountForUser(user.id())).isGreaterThan(1);
        }
    }

    @Test
    @DisplayName("BL-03 (shared coupon race): конкурентное использование купона с max_uses=1 разными пользователями")
    void concurrentSharedCouponRace() throws Exception {
        long couponId = insertCoupon("RACE10", 10, Instant.now().plusSeconds(3600), 1, 0);
        int attempts = 5;
        List<Account> users = new ArrayList<>(attempts);
        for (int i = 0; i < attempts; i++) {
            Account user = registerAndLogin();
            assertThat(put("/cart/items/" + lampId, Map.of("quantity", 1), user.token()).statusCode()).isEqualTo(200);
            assertThat(put("/cart/coupon", Map.of("code", "RACE10"), user.token()).statusCode()).isEqualTo(200);
            users.add(user);
        }

        List<HttpResponse<String>> responses = runConcurrently(attempts,
                index -> checkout(users.get(index).token(), newKey(), 2L, 1350L));

        long createdCount = responses.stream().filter(r -> r.statusCode() == 201).count();
        long conflictCount = responses.stream().filter(r -> r.statusCode() == 409).count();

        if (isProtected()) {
            assertThat(createdCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(attempts - 1);
            assertThat(couponUsedCount(couponId)).isEqualTo(1L);
        } else {
            assertThat(createdCount).isGreaterThan(1);
            assertThat(couponUsedCount(couponId)).isGreaterThan(1L);
        }
    }

    @FunctionalInterface
    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    private <T> List<T> runConcurrently(int count, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.run(index);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Account(long id, String token) {
    }

    private Account registerAndLogin() throws Exception {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        HttpResponse<String> reg = post("/auth/register", Map.of(
                "login", login,
                "password", PASSWORD,
                "name", "Test User",
                "email", login + "@example.com"), null, null);
        assertThat(reg.statusCode()).as("register: %s", reg.body()).isEqualTo(201);
        long userId = json(reg).get("id").asLong();

        HttpResponse<String> loggedIn = post("/auth/login", Map.of("login", login, "password", PASSWORD), null, null);
        assertThat(loggedIn.statusCode()).as("login: %s", loggedIn.body()).isEqualTo(200);
        return new Account(userId, json(loggedIn).get("accessToken").asString());
    }

    private long insertProduct(String name, String description, long price, boolean published) {
        return jdbc.sql("""
                        INSERT INTO products (name, description, price, published)
                        VALUES (:name, :description, :price, :published)
                        RETURNING id
                        """)
                .param("name", name)
                .param("description", description)
                .param("price", price)
                .param("published", published)
                .query(Long.class)
                .single();
    }

    private long insertCoupon(String code, int discountPercent, Instant expiresAt, int maxUses, int usedCount) {
        return jdbc.sql("""
                        INSERT INTO coupons (code, discount_percent, expires_at, max_uses, used_count)
                        VALUES (:code, :discountPercent, :expiresAt, :maxUses, :usedCount)
                        RETURNING id
                        """)
                .param("code", code)
                .param("discountPercent", discountPercent)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("maxUses", maxUses)
                .param("usedCount", usedCount)
                .query(Long.class)
                .single();
    }

    private long couponUsedCount(long couponId) {
        return jdbc.sql("SELECT used_count FROM coupons WHERE id = :id")
                .param("id", couponId)
                .query(Long.class)
                .single();
    }

    private int ordersCountForUser(long userId) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }

    private void cleanDatabase() {
        jdbc.sql("DELETE FROM order_items").update();
        jdbc.sql("DELETE FROM orders").update();
        jdbc.sql("DELETE FROM cart_items").update();
        jdbc.sql("DELETE FROM carts").update();
        jdbc.sql("DELETE FROM users").update();
        jdbc.sql("DELETE FROM coupons").update();
        jdbc.sql("DELETE FROM products").update();
    }

    private HttpResponse<String> checkout(String token, String idempotencyKey, long cartRevision, long quotedTotal)
            throws Exception {
        return post("/orders", Map.of("cartRevision", cartRevision, "quotedTotal", quotedTotal), idempotencyKey, token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = request(path).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Object body, String idempotencyKey, String token) throws Exception {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE_PATH + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Request-Id", UUID.randomUUID().toString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }

    private static String newKey() {
        return "idem-" + UUID.randomUUID();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
