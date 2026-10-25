package lab.gateway;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GatewayPortsConfiguration {
    @Bean
    TomcatServletWebServerFactory servletWebServerFactory(
            @Value("${lab.gateway.additional-port}") int additionalPort) {
        var factory = new TomcatServletWebServerFactory();
        var connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(additionalPort);
        factory.addAdditionalConnectors(connector);
        return factory;
    }
}
