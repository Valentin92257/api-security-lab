package lab.detector;

import lab.contracts.json.ContractJsonCodec;
import lab.detector.security.ServiceAccessFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DetectorSecurityConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "with space", "gateway", "shop"})
    void invalidOrAmbiguousRoleCredentialsPreventStartup(String control) {
        new ApplicationContextRunner().withBean(ContractJsonCodec.class)
                .withUserConfiguration(ServiceAccessFilter.class)
                .withPropertyValues("lab.detector.control-token=" + control,
                        "lab.detector.gateway-token=gateway", "lab.detector.shop-token=shop")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }
}
