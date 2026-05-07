package com.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String TOKEN_SECURITY_SCHEME = "token";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tech-Brain API")
                        .description("Tech-Brain interface documentation")
                        .version("1.0"))
                .components(new Components()
                        .addSecuritySchemes(TOKEN_SECURITY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("token")))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_SECURITY_SCHEME));
    }
}
