package lab.control.fixture;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lab.fixture")
public record ShopFixtureProperties(
        String username,
        String password,
        String vulnerableUrl,
        String protectedUrl,
        @DefaultValue("24") int userCount,
        @DefaultValue("200") int csAttempts,
        @DefaultValue("3") int csValidAccounts
) {
    public static final int DEFAULT_USER_COUNT = 24;
    public static final int DEFAULT_CS_ATTEMPTS = 200;
    public static final int DEFAULT_CS_VALID_ACCOUNTS = 3;

    @ConstructorBinding
    public ShopFixtureProperties {
        if (userCount < DEFAULT_USER_COUNT) {
            throw new IllegalArgumentException("userCount must be at least " + DEFAULT_USER_COUNT);
        }
        if (csValidAccounts < 1 || csValidAccounts > userCount) {
            throw new IllegalArgumentException("csValidAccounts must be between 1 and userCount");
        }
        if (csAttempts < csValidAccounts) {
            throw new IllegalArgumentException("csAttempts must be at least csValidAccounts");
        }
    }

    public ShopFixtureProperties(String username, String password, String vulnerableUrl, String protectedUrl) {
        this(username, password, vulnerableUrl, protectedUrl,
                DEFAULT_USER_COUNT, DEFAULT_CS_ATTEMPTS, DEFAULT_CS_VALID_ACCOUNTS);
    }

    public String urlFor(ShopTarget target) {
        return switch (target) {
            case VULNERABLE -> vulnerableUrl;
            case PROTECTED -> protectedUrl;
        };
    }
}
