package com.corp.agent.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A2A Agent 网关管理面应用入口。
 *
 * <p>负责 Agent Card 全量定义管理、发布（拼装对外 Card + 变更日志）、
 * 调用方凭证与 ACL 管理、节点配置同步 API（拉取模型）。</p>
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.corp.agent.admin.mapper")
public class AgentAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentAdminApplication.class, args);
    }
}
