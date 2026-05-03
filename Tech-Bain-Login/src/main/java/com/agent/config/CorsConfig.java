package com.agent.config;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
@Tag(name = "跨域配置")
@Configuration
public class CorsConfig {
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        // 1. 是否允许发送 Cookie
        config.setAllowCredentials(true);
        // 2. 允许的前端域
        config.addAllowedOrigin("http://localhost:5173");
        // 3. 允许的 Header（你的 JWT Token 就在这）
        config.addAllowedHeader("*");
        // 4. 允许的请求方法
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}