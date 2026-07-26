-- ============================================================================
-- MCP 网关管理台数据库初始化脚本（生产 MySQL 8）
-- 对应《MCP网关详细设计文档》5.1 节
-- ============================================================================

CREATE DATABASE IF NOT EXISTS mcp_admin DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mcp_admin;

-- ---------- MCP Server 注册表 ----------
CREATE TABLE IF NOT EXISTS `mcp_servers` (
  `id`                 bigint       NOT NULL AUTO_INCREMENT,
  `server_id`          varchar(64)  NOT NULL COMMENT '唯一标识, ^[a-z0-9][a-z0-9-]{1,62}$',
  `name`               varchar(128) NOT NULL,
  `description`        text,
  `category`           varchar(32)  DEFAULT NULL COMMENT 'hr/finance/office/dev/...',
  `base_url`           varchar(512) NOT NULL COMMENT '默认后端地址(无实例列表时使用)',
  `instances`          json         DEFAULT NULL COMMENT '后端实例列表 [{"url":"https://...","weight":1}]',
  `protocol_type`      varchar(16)  NOT NULL DEFAULT 'streamable-http' COMMENT 'streamable-http|http-sse(legacy)',
  `resource_uri`       varchar(512) NOT NULL COMMENT 'RFC8707规范化资源URI, Token Exchange的audience',
  `auth_mode`          varchar(24)  NOT NULL DEFAULT 'user-delegation' COMMENT 'user-delegation|service|none',
  `health_endpoint`    varchar(128) NOT NULL DEFAULT '/health',
  `data_classification` varchar(32) DEFAULT 'internal' COMMENT 'public/internal/confidential/restricted',
  `owner_team`         varchar(64)  DEFAULT NULL,
  `owner_email`        varchar(128) DEFAULT NULL,
  `status`             tinyint      NOT NULL DEFAULT 0 COMMENT '0:草稿 1:待审 2:活跃 3:废弃',
  `version`            varchar(32)  DEFAULT NULL,
  `health_status`      varchar(16)  NOT NULL DEFAULT 'unknown',
  `health_checked_at`  datetime     DEFAULT NULL,
  `total_calls`        bigint       NOT NULL DEFAULT 0 COMMENT '由审计统计任务回写',
  `avg_latency_ms`     int          DEFAULT NULL,
  `created_by`         varchar(128) DEFAULT NULL,
  `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`            tinyint      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_server_id` (`server_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Server注册表';

-- ---------- MCP 工具元数据 ----------
CREATE TABLE IF NOT EXISTS `mcp_tools` (
  `id`                 bigint       NOT NULL AUTO_INCREMENT,
  `server_id`          varchar(64)  NOT NULL,
  `tool_name`          varchar(128) NOT NULL,
  `description`        text,
  `input_schema`       json         DEFAULT NULL COMMENT 'MCP inputSchema(JSON Schema)',
  `output_schema`      json         DEFAULT NULL,
  `annotations`        json         DEFAULT NULL COMMENT 'MCP annotations, 如{"readOnlyHint":true}',
  `required_scope`     varchar(128) DEFAULT NULL COMMENT 'Token Exchange时请求的scope, 默认 mcp:{serverId}:{toolName}',
  `rate_limit_rpm`     int          NOT NULL DEFAULT 60 COMMENT '默认每分钟限流(被策略约束覆盖)',
  `subject_bindings`   json         DEFAULT NULL COMMENT '参数绑定校验 [{"param":"employee_id","claim":"email","required":true}]',
  `validation_level`   varchar(16)  NOT NULL DEFAULT 'basic' COMMENT 'none|basic(类型/必填/枚举)|schema(完整JSON Schema)',
  `output_masking`     json         DEFAULT NULL COMMENT '输出脱敏规则 [{"pattern":"...","replacement":"***"}]',
  `data_classification` varchar(32) DEFAULT NULL,
  `is_active`          tinyint      NOT NULL DEFAULT 1,
  `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_server_tool` (`server_id`, `tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP工具元数据';

-- ---------- 授权策略表 ----------
CREATE TABLE IF NOT EXISTS `auth_policies` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `policy_name`    varchar(128) NOT NULL,
  `server_id`      varchar(64)  NOT NULL,
  `tool_name`      varchar(128) NOT NULL DEFAULT '*' COMMENT '工具名, *=全部',
  `grantee_type`   varchar(16)  NOT NULL COMMENT 'AGENT|USER|ROLE|GROUP',
  `grantee_id`     varchar(128) NOT NULL COMMENT '对象ID; AGENT支持委托链匹配',
  `grantee_name`   varchar(128) DEFAULT NULL,
  `data_scope`     varchar(16)  NOT NULL DEFAULT 'self' COMMENT 'self|team|department|organization',
  `constraints`    json         DEFAULT NULL COMMENT '{"max_calls_per_minute":60,"time_range":"09:00-18:00"}',
  `effect`         varchar(8)   NOT NULL DEFAULT 'ALLOW' COMMENT 'ALLOW|DENY (DENY优先)',
  `effective_time` datetime     DEFAULT NULL,
  `expiry_time`    datetime     DEFAULT NULL,
  `status`         tinyint      NOT NULL DEFAULT 0 COMMENT '0:待审 1:生效 2:过期 3:撤销',
  `approved_by`    varchar(128) DEFAULT NULL,
  `approved_at`    datetime     DEFAULT NULL,
  `created_by`     varchar(128) DEFAULT NULL,
  `created_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_server_status` (`server_id`, `status`),
  KEY `idx_grantee` (`grantee_type`, `grantee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='授权策略表';

-- ---------- 审计日志表（按年分区） ----------
CREATE TABLE IF NOT EXISTS `audit_logs` (
  `id`               bigint       NOT NULL AUTO_INCREMENT,
  `request_id`       varchar(64)  NOT NULL,
  `trace_id`         varchar(64)  DEFAULT NULL,
  `timestamp`        datetime(3)  NOT NULL COMMENT '毫秒精度',
  `caller_agent_id`  varchar(64)  DEFAULT NULL COMMENT '委托链末端(直接调用方)',
  `delegation_chain` varchar(512) DEFAULT NULL COMMENT '完整委托链JSON',
  `delegator_user_id` varchar(128) DEFAULT NULL COMMENT '终端用户sub(无用户委托时为NULL)',
  `delegator_org_id` varchar(64)  DEFAULT NULL,
  `jsonrpc_method`   varchar(64)  DEFAULT NULL COMMENT 'MCP方法名',
  `tool_name`        varchar(128) DEFAULT NULL,
  `server_id`        varchar(64)  NOT NULL,
  `request_args_hash` varchar(64) DEFAULT NULL COMMENT 'SHA-256(规范化的arguments)',
  `auth_result`      varchar(16)  NOT NULL COMMENT 'success|failed',
  `policy_decision`  varchar(16)  DEFAULT NULL COMMENT 'allow|deny|n-a',
  `deny_reason`      varchar(256) DEFAULT NULL,
  `token_exchanged`  tinyint      NOT NULL DEFAULT 0,
  `latency_ms`       int          DEFAULT NULL,
  `upstream_latency_ms` int       DEFAULT NULL,
  `response_status`  int          DEFAULT NULL,
  `response_size`    int          DEFAULT NULL,
  `client_ip`        varchar(64)  DEFAULT NULL,
  `created_at`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`, `timestamp`),
  KEY `idx_ts_server` (`timestamp`, `server_id`),
  KEY `idx_user_ts` (`delegator_user_id`, `timestamp`),
  KEY `idx_agent_ts` (`caller_agent_id`, `timestamp`),
  KEY `idx_request_id` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  PARTITION BY RANGE COLUMNS(`timestamp`) (
    PARTITION p2026 VALUES LESS THAN ('2027-01-01'),
    PARTITION p2027 VALUES LESS THAN ('2028-01-01'),
    PARTITION pmax  VALUES LESS THAN MAXVALUE
  );
