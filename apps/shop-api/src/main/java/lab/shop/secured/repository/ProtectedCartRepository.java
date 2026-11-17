package lab.shop.secured.repository;

import lab.shop.application.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedCartRepository implements CartRepository {

    private final JdbcClient jdbc;

    @Override
    public void createEmpty(long userId) {
        jdbc.sql("INSERT INTO carts (user_id) VALUES (:userId)")
                .param("userId", userId)
                .update();
    }
}
