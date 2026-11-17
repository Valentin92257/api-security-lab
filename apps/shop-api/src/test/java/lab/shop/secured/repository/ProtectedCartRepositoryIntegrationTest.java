package lab.shop.secured.repository;

import lab.shop.PostgreSQLIntegrationTest;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("protected")
public class ProtectedCartRepositoryIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("createEmpty создаёт ровно одну корзину для пользователя")
    void createEmptyCreatesOneCartRow() {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long userId = userRepository.insert(login, "Test User", login + "@example.com", "hash");

        cartRepository.createEmpty(userId);

        Integer count = jdbcClient.sql("SELECT count(*) FROM carts WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        assertThat(count).isOne();
    }
}
