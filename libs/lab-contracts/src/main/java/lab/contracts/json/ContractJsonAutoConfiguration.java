package lab.contracts.json;

import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies common JSON parsing rules to Boot's mapper and registers a validating
 * MVC converter for shared contract DTOs. Non-JSON converters are left intact.
 */
@AutoConfiguration
public class ContractJsonAutoConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public JsonMapperBuilderCustomizer strictJsonMapperCustomizer() {
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
    public ContractJsonCodec contractJsonCodec(ContractValidator contractValidator) {
        JsonMapper mapper = ContractJsonSupport.createJsonMapper();
        return new ContractJsonCodec(mapper, contractValidator);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public ServerHttpMessageConvertersCustomizer contractMessageConvertersCustomizer(ContractJsonCodec codec) {
        return builder -> builder.configureMessageConvertersList(converters ->
                converters.addFirst(new ContractJsonHttpMessageConverter(codec)));
    }
}
