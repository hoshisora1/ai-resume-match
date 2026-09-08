package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.config.ApiProperties;
import com.zhulikang.aimatch.security.AnonymousSessionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Import(AnonymousSessionService.class)
@EnableConfigurationProperties(ApiProperties.class)
public class WebConfig implements WebMvcConfigurer {
    private final ApiTokenInterceptor apiTokenInterceptor;

    public WebConfig(ApiTokenInterceptor apiTokenInterceptor) {
        this.apiTokenInterceptor = apiTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiTokenInterceptor).addPathPatterns("/api/**");
    }
}
