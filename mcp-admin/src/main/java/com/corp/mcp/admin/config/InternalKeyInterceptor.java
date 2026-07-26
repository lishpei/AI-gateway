package com.corp.mcp.admin.config;

import com.corp.mcp.admin.common.Result;
import com.corp.mcp.admin.util.Jsons;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 内部接口密钥校验（网关审计上报等）。请求头：X-Internal-Key。
 */
@Component
public class InternalKeyInterceptor implements HandlerInterceptor {

    @Value("${mcp.internal-key}")
    private String internalKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String key = request.getHeader("X-Internal-Key");
        if (internalKey.equals(key)) {
            return true;
        }
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(Jsons.toJson(Result.error(40101, "invalid internal key")));
        return false;
    }
}
