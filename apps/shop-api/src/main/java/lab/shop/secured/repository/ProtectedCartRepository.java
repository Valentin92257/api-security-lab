package lab.shop.secured.repository;

import lab.shop.application.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedCartRepository implements CartRepository {

    private final JdbcClient jdbc;

    @Override
    public void createEmpty(long userId, Instant now) {
        jdbc.sql("INSERT INTO carts (user_id, updated_at) VALUES (:userId, :now)")
                .param("userId", userId)
                .param("now", Timestamp.from(now))
                .update();
    }
}
