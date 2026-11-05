package lab.contracts.json;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractJsonAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, ContractJsonAutoConfiguration.class));

    @Test
    void registersContractConverterAlongsideStandardConverters() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ContractJsonCodec.class).hasSingleBean(ContractValidator.class);
            var builder = HttpMessageConverters.forServer().registerDefaults();
            context.getBean("contractMessageConvertersCustomizer", ServerHttpMessageConvertersCustomizer.class)
                    .customize(builder);
            var converters = new ArrayList<>();
            builder.build().forEach(converters::add);
            assertThat(converters.getFirst()).isInstanceOf(ContractJsonHttpMessageConverter.class);
            assertThat(converters).anyMatch(StringHttpMessageConverter.class::isInstance);
        });
    }

    @Test
    void preservesApplicationMapperAndExistingCustomizers() {
        JsonMapper applicationMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        runner.withBean(JsonMapper.class, () -> applicationMapper)
                .withBean("applicationConvertersCustomizer", ServerHttpMessageConvertersCustomizer.class,
                        () -> builder -> {})
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JsonMapper.class)).isSameAs(applicationMapper);
                    assertThat(applicationMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
                    assertThat(context.getBeansOfType(ServerHttpMessageConvertersCustomizer.class)).hasSize(2);
                    assertThat(context.getBean(ContractJsonCodec.class).jsonMapper()).isNotSameAs(applicationMapper);
                });
    }

    @Test
    void installsConverterInBootMvc() {
        runner.withConfiguration(AutoConfigurations.of(
                        HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var converters = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
                    assertThat(converters.getFirst()).isInstanceOf(ContractJsonHttpMessageConverter.class);
                    assertThat(converters).anyMatch(StringHttpMessageConverter.class::isInstance);
                });
    }

    @Test
    void appliesCommonRulesToBootMapperForOrdinaryDtos() {
        runner.run(context -> {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertThatThrownBy(() -> mapper.readValue("{\"value\":123}", ContractJsonSupportTest.TextValue.class))
                    .isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> mapper.readValue("{\"value\":\"a\",\"value\":\"b\"}",
                    ContractJsonSupportTest.TextValue.class)).isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> mapper.readValue("{\"value\":\"a\"} {}",
                    ContractJsonSupportTest.TextValue.class)).isInstanceOf(JacksonException.class);
        });
    }
}
