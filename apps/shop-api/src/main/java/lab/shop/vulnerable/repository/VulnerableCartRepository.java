package lab.shop.vulnerable.repository;

import lab.shop.application.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableCartRepository implements CartRepository {

    private final JdbcClient jdbc;

    @Override
    public void createEmpty(long userId, Instant now) {
        String sql = "INSERT INTO carts (user_id, updated_at) VALUES ('%s', :now)".formatted(userId);

        jdbc.sql(sql)
                .param("now", Timestamp.from(now))
                .update();
    }
}
