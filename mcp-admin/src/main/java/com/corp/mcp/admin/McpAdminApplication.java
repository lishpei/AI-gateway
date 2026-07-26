package com.corp.mcp.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MCP 网关管理台应用入口。
 *
 * <p>控制平面：MCP Server/Tool 注册、授权策略、市场展示、审计查询、
 * 策略快照同步到 Redis、IdP 对接（dev profile 内嵌 IdP Mock）。</p>
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.corp.mcp.admin.mapper")
public class McpAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpAdminApplication.class, args);
    }
}
