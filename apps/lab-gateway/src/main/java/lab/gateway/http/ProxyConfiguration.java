package lab.gateway.http;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProxyProperties.class)
class ProxyConfiguration {
    @Bean(destroyMethod = "close")
    CloseableHttpClient upstreamClient(GatewayProxyProperties properties) {
        var timeout = Timeout.ofMilliseconds(properties.timeout().toMillis());
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                        .setSocketTimeout(timeout)
                        .build())
                .setMaxConnTotal(64)
                .setMaxConnPerRoute(64)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout)
                        .setResponseTimeout(timeout)
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .build();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService upstreamDeadlines() {
        var executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("upstream-deadline").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
