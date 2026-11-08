package lab.shop.vulnerable.repository;

import lab.shop.application.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("vulnerable")
@RequiredArgsConstructor
public class VulnerableCartRepository implements CartRepository {

    private final JdbcClient jdbc;

    @Override
    public void createEmpty(long userId) {
        String sql = "INSERT INTO carts (user_id) VALUES ('%s')";

        jdbc.sql(String.format(sql, userId))
                .update();
    }
}
