package com.corp.agent.admin.config;

import com.corp.agent.admin.common.Result;
import com.corp.agent.admin.util.Jsons;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final NodeTokenInterceptor nodeTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 管理面 API：管理员 Token
        registry.addInterceptor(adminAuthInterceptor).addPathPatterns("/api/v1/**");
        // 节点同步 API：节点 Token
        registry.addInterceptor(nodeTokenInterceptor).addPathPatterns("/internal/v1/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /** 管理员 Token 校验（Authorization: Bearer {admin-token}） */
    @Component
    public static class AdminAuthInterceptor implements HandlerInterceptor {
        @Value("${agent.admin-token}")
        private String adminToken;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            String header = request.getHeader("Authorization");
            if (header != null && header.equals("Bearer " + adminToken)) {
                return true;
            }
            writeUnauthorized(response);
            return false;
        }

        static void writeUnauthorized(HttpServletResponse response) throws Exception {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(Jsons.toJson(Result.error(40101, "未认证或 Token 无效")));
        }
    }

    /** 数据面节点 Token 校验（X-Node-Token，支持多节点） */
    @Component
    public static class NodeTokenInterceptor implements HandlerInterceptor {
        @Value("${agent.node-tokens}")
        private List<String> nodeTokens;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            String token = request.getHeader("X-Node-Token");
            if (token != null && nodeTokens.contains(token)) {
                return true;
            }
            AdminAuthInterceptor.writeUnauthorized(response);
            return false;
        }
    }
}
