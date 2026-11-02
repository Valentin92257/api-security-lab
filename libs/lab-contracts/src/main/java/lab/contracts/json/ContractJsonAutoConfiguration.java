package lab.contracts.json;

import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot auto-configuration that registers strict Jackson schema settings,
 * {@link ContractValidator}, and {@link ContractJsonCodec}.
 */
@AutoConfiguration
public class ContractJsonAutoConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer contractJsonMapperCustomizer() {
        return ContractJsonSupport::configure;
    }

    @Bean
    @ConditionalOnMissingBean
    public ContractValidator contractValidator(ObjectProvider<Validator> validatorProvider) {
        Validator validator = validatorProvider.getIfAvailable();
        return validator != null ? new ContractValidator(validator) : new ContractValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContractJsonCodec contractJsonCodec(
            ObjectProvider<JsonMapper> jsonMapperProvider,
            ContractValidator contractValidator) {
        JsonMapper mapper = jsonMapperProvider.getIfAvailable(ContractJsonSupport::createJsonMapper);
        return new ContractJsonCodec(mapper, contractValidator);
    }
}

