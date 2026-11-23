package lab.shop;

import lab.contracts.json.ContractJsonAutoConfiguration;
import lab.contracts.shop.model.LineItem;
import lab.contracts.shop.model.LoginRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopJsonConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"vulnerable", "protected"})
    void enforcesJsonTypesAndSyntaxInBothModes(String profile) {
        new WebApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class, ContractJsonAutoConfiguration.class))
                .run(context -> {
                    JsonMapper mapper = context.getBean(JsonMapper.class);
                    assertThatThrownBy(() -> mapper.readValue(
                            "{\"login\":123,\"password\":\"secret\"}", LoginRequest.class))
                            .isInstanceOf(JacksonException.class);
                    assertThatThrownBy(() -> mapper.readValue(
                            "{\"login\":\"first\",\"login\":\"second\",\"password\":\"secret\"}", LoginRequest.class))
                            .isInstanceOf(JacksonException.class);
                    assertThatThrownBy(() -> mapper.readValue("{\"quantity\":\"2\"}", LineItem.class))
                            .isInstanceOf(JacksonException.class);
                    assertThatThrownBy(() -> mapper.readValue("{\"quantity\":1.5}", LineItem.class))
                            .isInstanceOf(JacksonException.class);

                    assertThat(mapper.readValue("{\"login\":\"buyer\",\"password\":\"secret\"}",
                            LoginRequest.class).getLogin()).isEqualTo("buyer");
                    // Quantity ranges belong to mode-specific validation, not the JSON parser.
                    assertThat(mapper.readValue("{\"quantity\":-1}", LineItem.class).getQuantity()).isEqualTo(-1);
                });
    }
}
