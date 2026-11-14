package lab.shop.api.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Тестовая конфигурация: подменяет JwtDecoder приложения таким же (JwtSupport.decoder), но с часами, которые можно сдвинуть вперёд.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AdjustableClockConfig {

    @Bean
    public AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }

    @Bean
    @Primary
    public JwtDecoder adjustableClockJwtDecoder(JwtProperties props, AdjustableClock clock) {
        return JwtSupport.decoder(props, clock.clock());
    }

    public static final class AdjustableClock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        private final Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.now().plus(offset.get());
            }
        };

        public Clock clock() {
            return clock;
        }

        public void advance(Duration duration) {
            offset.updateAndGet(current -> current.plus(duration));
        }

        public void reset() {
            offset.set(Duration.ZERO);
        }
    }
}