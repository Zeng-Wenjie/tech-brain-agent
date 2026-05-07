package com.agent.config;

import com.agent.interceptor.TokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/*
    拦截器配置类
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private TokenInterceptor tokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/**")//拦截所有请求 / Intercept all requests.
                .excludePathPatterns(//放行登录接口 / Exclude login-related endpoints.
                        "/login",
                        "/register",
                        "/chat",
                        "/doc.html",
                        "/webjars/**",
                        "/favicon.ico",
                        "/swagger-resources/**",
                        "/v3/api-docs/**"
                );
    }
}
