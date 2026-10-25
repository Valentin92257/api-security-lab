package lab.shop;

import lab.shop.application.ShopImplementation;
import lab.shop.application.ShopMode;
import lab.shop.secured.SecuredShopImplementation;
import lab.shop.vulnerable.VulnerableShopImplementation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ShopProfileConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ShopProfileConfiguration.class,
                    SecuredShopImplementation.class, VulnerableShopImplementation.class);

    @Test
    void protectedProfileSelectsOnlySecuredImplementation() {
        runner.withPropertyValues("spring.profiles.active=protected").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ShopImplementation.class);
            assertThat(context.getBean(ShopImplementation.class)).isInstanceOf(SecuredShopImplementation.class);
            assertThat(context.getBean(ShopImplementation.class).mode()).isEqualTo(ShopMode.PROTECTED);
        });
    }

    @Test
    void vulnerableProfileSelectsOnlyVulnerableImplementation() {
        runner.withPropertyValues("spring.profiles.active=vulnerable").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ShopImplementation.class);
            assertThat(context.getBean(ShopImplementation.class)).isInstanceOf(VulnerableShopImplementation.class);
            assertThat(context.getBean(ShopImplementation.class).mode()).isEqualTo(ShopMode.VULNERABLE);
        });
    }

    @Test
    void missingProfileFailsStartup() {
        runner.withPropertyValues("spring.profiles.active=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Activate exactly one shop profile: protected or vulnerable");
        });
    }

    @Test
    void bothProfilesFailStartup() {
        runner.withPropertyValues("spring.profiles.active=protected,vulnerable").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Activate exactly one shop profile: protected or vulnerable");
        });
    }

    @Test
    void unrelatedProfileCanCoexistWithShopProfile() {
        runner.withPropertyValues("spring.profiles.active=protected,test").run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(ShopImplementation.class));
    }

    @Test
    void missingImplementationFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(ShopProfileConfiguration.class)
                .withPropertyValues("spring.profiles.active=protected").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Shop profile must select exactly one matching implementation");
                });
    }
}
