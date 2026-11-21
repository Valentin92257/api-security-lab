package lab.shop.secured.repository;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.application.model.NewOrder;
import lab.shop.application.model.OrderItem;
import lab.shop.application.model.OrderResponse;
import lab.shop.application.model.StoredOrder;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.OrderRepository;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("protected")
public class ProtectedOrderRepositoryIntegrationTest extends PostgreSQLIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected CartRepository cartRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected DataSource dataSource;

    protected long userId;
    protected long cartId;
    protected long productId;

    /**
     * В protected есть UNIQUE(user_id, idempotency_key_hash) и UNIQUE(cart_id, cart_revision).
     */
    protected boolean enforcesIdempotencyKeyUniqueness() {
        return true;
    }

    protected boolean enforcesCartRevisionUniqueness() {
        return true;
    }

    /**
     * protected берёт advisory lock; в vulnerable он намеренно отключён.
     */
    protected boolean serializesIdempotencyKey() {
        return true;
    }

    @BeforeEach
    void setUp() {
        userId = insertUser();
        cartRepository.createEmpty(userId, T0);
        cartId = jdbc.sql("SELECT id FROM carts WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        productId = jdbc.sql("""
                        INSERT INTO products (name, price, published)
                        VALUES ('Лампа', 1000, true)
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("nextId выдаёт различные положительные id до вставки")
    void nextIdReturnsDistinctPositiveIds() {
        long first = orderRepository.nextId();
        long second = orderRepository.nextId();

        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("insert сохраняет заказ и позиции")
    void insertStoresOrderAndItems() {
        long orderId = insertOrder(0, bytes(1), bytes(2));

        Long subtotal = jdbc.sql("SELECT subtotal FROM orders WHERE id = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();
        Integer items = jdbc.sql("SELECT count(*) FROM order_items WHERE order_id = :id")
                .param("id", orderId)
                .query(Integer.class)
                .single();
        Long unitPrice = jdbc.sql("SELECT unit_price FROM order_items WHERE order_id = :id AND product_id = :productId")
                .param("id", orderId)
                .param("productId", productId)
                .query(Long.class)
                .single();

        assertThat(subtotal).isEqualTo(1000);
        assertThat(items).isOne();
        assertThat(unitPrice).isEqualTo(1000L);
    }

    @Test
    @DisplayName("insert сохраняет response_body с апострофом в названии товара")
    void insertStoresResponseBodyWithApostrophe() {
        long orderId = orderRepository.nextId();
        String responseBody = """
                {"id":%d,"userId":%d,"cartId":%d,"cartRevision":0,"items":[{"productId":%d,"name":"Лампа 'Java'","quantity":1,"unitPrice":1000,"lineTotal":1000}],"coupon":null,"subtotal":1000,"discount":0,"total":1000,"currency":"RUB"}
                """.formatted(orderId, userId, cartId, productId);

        orderRepository.insert(new NewOrder(
                orderId, userId, cartId, 0, 1000, 0, 1000,
                null, T0, bytes(5), bytes(6), responseBody,
                List.of(new OrderItem(productId, 1, 1000))));

        String stored = orderRepository.findResponse(orderId).orElseThrow().responseBody();
        assertThat(jsonMapper.readTree(stored)).isEqualTo(jsonMapper.readTree(responseBody));
    }

    @Test
    @DisplayName("findByIdempotencyKey возвращает id, fingerprint и сохранённый response_body")
    void findByIdempotencyKeyReturnsStoredOrder() {
        byte[] keyHash = bytes(1);
        byte[] fingerprint = bytes(2);
        long orderId = insertOrder(0, keyHash, fingerprint);

        StoredOrder stored = orderRepository.findByIdempotencyKey(userId, keyHash).orElseThrow();

        assertThat(stored.id()).isEqualTo(orderId);
        assertThat(stored.requestFingerprint()).containsExactly(fingerprint);

        JsonNode body = jsonMapper.readTree(stored.responseBody());
        assertThat(body.get("id").asLong()).isEqualTo(orderId);
        assertThat(body.get("userId").asLong()).isEqualTo(userId);
        assertThat(body.get("cartRevision").asLong()).isZero();
    }

    @Test
    @DisplayName("Поиск по ключу ограничен владельцем: чужой user ничего не находит")
    void findByIdempotencyKeyIsScopedByUser() {
        byte[] keyHash = bytes(1);
        insertOrder(0, keyHash, bytes(2));
        long anotherUser = insertUser();

        assertThat(orderRepository.findByIdempotencyKey(anotherUser, keyHash)).isEmpty();
    }

    @Test
    @DisplayName("Неизвестный ключ возвращает Optional.empty")
    void findByIdempotencyKeyMissingReturnsEmpty() {
        assertThat(orderRepository.findByIdempotencyKey(userId, bytes(9))).isEmpty();
    }

    @Test
    @DisplayName("existsForRevision различает израсходованную и свободную revision")
    void existsForRevisionDetectsUsedRevision() {
        insertOrder(0, bytes(1), bytes(2));

        assertThat(orderRepository.existsForRevision(cartId, 0)).isTrue();
        assertThat(orderRepository.existsForRevision(cartId, 1)).isFalse();
        assertThat(orderRepository.existsForRevision(cartId + 1, 0)).isFalse();
    }

    @Test
    @DisplayName("findResponse возвращает сохранённый ответ и владельца заказа")
    void findResponseReturnsStoredResponseAndOwner() {
        long orderId = insertOrder(0, bytes(1), bytes(2));

        OrderResponse response = orderRepository.findResponse(orderId).orElseThrow();

        assertThat(response.userId()).isEqualTo(userId);
        JsonNode body = jsonMapper.readTree(response.responseBody());
        assertThat(body.get("id").asLong()).isEqualTo(orderId);

        assertThat(orderRepository.findResponse(orderId + 1)).isEmpty();
    }

    @Test
    @DisplayName("Повторный ключ идемпотентности: protected отклоняет, vulnerable допускает")
    void duplicateIdempotencyKey() {
        byte[] keyHash = bytes(1);
        insertOrder(0, keyHash, bytes(2));

        if (enforcesIdempotencyKeyUniqueness()) {
            assertThatThrownBy(() -> insertOrder(1, keyHash, bytes(3)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } else {
            insertOrder(1, keyHash, bytes(3));

            Integer count = jdbc.sql("SELECT count(*) FROM orders WHERE user_id = :userId AND idempotency_key_hash = decode(:hash, 'hex')")
                    .param("userId", userId)
                    .param("hash", HexFormat.of().formatHex(keyHash))
                    .query(Integer.class)
                    .single();
            assertThat(count).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Повторная cartRevision: protected отклоняет, vulnerable допускает")
    void duplicateCartRevision() {
        insertOrder(0, bytes(1), bytes(2));

        if (enforcesCartRevisionUniqueness()) {
            assertThatThrownBy(() -> insertOrder(0, bytes(3), bytes(4)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } else {
            insertOrder(0, bytes(3), bytes(4));

            Integer count = jdbc.sql("SELECT count(*) FROM orders WHERE cart_id = :cartId AND cart_revision = 0")
                    .param("cartId", cartId)
                    .query(Integer.class)
                    .single();
            assertThat(count).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("lockIdempotencyKey: protected ждёт ограниченно и падает по таймауту, vulnerable не блокирует")
    void lockIdempotencyKeyTimesOutWhenHeldByAnotherTransaction() throws Exception {
        long lockId = 424242L;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, lockId);
                statement.execute();
            }

            if (serializesIdempotencyKey()) {
                assertThatThrownBy(() -> orderRepository.lockIdempotencyKey(lockId, Duration.ofMillis(100)))
                        .isInstanceOf(PessimisticLockingFailureException.class);
            } else {
                assertThatCode(() -> orderRepository.lockIdempotencyKey(lockId, Duration.ofMillis(100)))
                        .doesNotThrowAnyException();
            }

            connection.rollback();
        }
    }

    protected long insertUser() {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return userRepository.insert(login, "Test User", login + "@example.com", "hash");
    }

    protected long insertOrder(long cartRevision, byte[] keyHash, byte[] fingerprint) {
        long id = orderRepository.nextId();
        String responseBody = """
                {"id":%d,"userId":%d,"cartId":%d,"cartRevision":%d,"subtotal":1000,"discount":0,"total":1000,"currency":"RUB"}
                """.formatted(id, userId, cartId, cartRevision);

        List<OrderItem> items = List.of(new OrderItem(productId, 1, 1000));
        orderRepository.insert(new NewOrder(
                id, userId, cartId, cartRevision, 1000, 0, 1000,
                null, T0, keyHash, fingerprint, responseBody, items));

        return id;
    }

    protected static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
