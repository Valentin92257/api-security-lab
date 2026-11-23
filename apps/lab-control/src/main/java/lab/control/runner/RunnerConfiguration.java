package lab.control.runner;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
class RunnerConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOn("stepExecutor")
    ScenarioRunner scenarioRunner(@Value("${lab.runner.run-timeout:PT5M}") Duration timeout) {
        return new ScenarioRunner(timeout);
    }

    @Bean(destroyMethod = "close")
    HttpClient runnerClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
    }
}
