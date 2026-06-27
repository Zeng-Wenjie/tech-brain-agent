package com.agent.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Raises Tomcat multipart limits for browser-selected project folder imports.
 */
@Configuration
public class SelfDevUploadTomcatConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> selfDevProjectUploadTomcatCustomizer(
            @Value("${techbrain.self-dev.project-upload.max-part-count:200000}") int maxPartCount,
            @Value("${techbrain.self-dev.project-upload.max-parameter-count:200000}") int maxParameterCount) {
        return factory -> factory.addConnectorCustomizers(connector -> configureConnector(
                connector,
                maxPartCount,
                maxParameterCount));
    }

    private void configureConnector(Connector connector, int maxPartCount, int maxParameterCount) {
        connector.setMaxPartCount(maxPartCount);
        connector.setMaxParameterCount(maxParameterCount);
    }
}
