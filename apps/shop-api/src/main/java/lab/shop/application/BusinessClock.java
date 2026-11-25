package lab.shop.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Бизнес-часы магазина (корзина, заказы, срок действия купонов).
 * JWT использует реальный {@link Clock}; база бизнес-часов будет задаваться через Runtime API.
 */
@Component
@RequiredArgsConstructor
public class BusinessClock {

    private final Clock clock;

    public Instant now() {
        return clock.instant();
    }
}
