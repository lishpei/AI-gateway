package com.corp.mcp.admin.config;

import com.corp.mcp.admin.util.Jsons;
import com.corp.mcp.admin.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 管理面管理员 Token 校验（一期简化方案，设计文档 6.2）。
 * 请求头：Authorization: Bearer {admin-token}
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Value("${mcp.admin-token}")
    private String adminToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.equals("Bearer " + adminToken)) {
            return true;
        }
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(Jsons.toJson(Result.error(40101, "未认证或 Token 无效")));
        return false;
    }
}
