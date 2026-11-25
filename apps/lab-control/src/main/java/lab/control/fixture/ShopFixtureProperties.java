package lab.control.fixture;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.fixture")
public record ShopFixtureProperties(
        String username,
        String password,
        String vulnerableUrl,
        String protectedUrl
) {
    public String urlFor(ShopTarget target) {
        return switch (target) {
            case VULNERABLE -> vulnerableUrl;
            case PROTECTED -> protectedUrl;
        };
    }
}
