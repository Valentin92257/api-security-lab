package lab.control;

import lab.control.trial.TrialService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

@SpringBootApplication
public class ControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }

    @Bean
    SmartInitializingSingleton recoverTrials(TrialService trials) {
        return trials::recoverInterrupted;
    }

    @Bean
    JsonMapperBuilderCustomizer strictControlJson() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
}
