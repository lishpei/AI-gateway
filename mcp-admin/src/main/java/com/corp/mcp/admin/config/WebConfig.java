package com.corp.mcp.admin.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final InternalKeyInterceptor internalKeyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 管理面 API：管理员 Token
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/audit/logs/batch");

        // 内部接口（网关审计上报）：内部密钥
        registry.addInterceptor(internalKeyInterceptor)
                .addPathPatterns("/api/v1/audit/logs/batch");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
