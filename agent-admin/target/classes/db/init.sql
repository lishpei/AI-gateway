-- ============================================================================
-- A2A Agent 网关管理面数据库初始化脚本（MySQL 8）
-- 对应《Agent网关详细设计文档》4.1 节
-- ============================================================================

CREATE DATABASE IF NOT EXISTS agent_admin DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE agent_admin;

-- ---------- Agent Card 完整定义 ----------
CREATE TABLE IF NOT EXISTS `agent_card` (
  `id`                   varchar(64)  NOT NULL COMMENT 'Agent唯一标识，正则 ^[a-z0-9][a-z0-9-]{1,62}$',
  `name`                 varchar(128) NOT NULL COMMENT 'Agent名称',
  `description`          text         COMMENT 'Agent描述',
  `provider_organization` varchar(256) DEFAULT NULL COMMENT '提供方组织名',
  `provider_url`         varchar(512)  DEFAULT NULL COMMENT '提供方网址',
  `version`              varchar(32)  NOT NULL COMMENT 'Agent版本，如 1.2.0',
  `documentation_url`    varchar(512)  DEFAULT NULL COMMENT '文档地址',
  `icon_url`             varchar(512)  DEFAULT NULL COMMENT '图标地址',
  `protocol_version`     varchar(8)   NOT NULL DEFAULT '1.0' COMMENT '上游声明的A2A协议版本(Major.Minor)',
  `endpoint_url`         varchar(512) NOT NULL COMMENT '上游真实A2A端点(内部，不出现在对外Card中)',
  `capabilities`         json         NOT NULL COMMENT 'AgentCapabilities: {streaming,pushNotifications,extendedAgentCard,extensions[]}',
  `security_schemes`     json          DEFAULT NULL COMMENT '对外声明的安全方案(不含秘密)',
  `security_requirements` json         DEFAULT NULL COMMENT '安全要求声明',
  `default_input_modes`  json         NOT NULL COMMENT '默认输入媒体类型数组',
  `default_output_modes` json         NOT NULL COMMENT '默认输出媒体类型数组',
  `skills`               json         NOT NULL COMMENT 'AgentSkill数组',
  `signatures`           json          DEFAULT NULL COMMENT 'Card JWS签名(一期原样透传)',
  `status`               tinyint      NOT NULL DEFAULT 1 COMMENT '1:启用 0:禁用(禁用视为未发布)',
  `published_seq`        bigint        DEFAULT NULL COMMENT '最近一次发布对应的config_change_log.seq; NULL=从未发布',
  `created_at`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Card全量定义';

-- ---------- 上游凭证（加密存储） ----------
CREATE TABLE IF NOT EXISTS `upstream_credential` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `agent_id`    varchar(64)  NOT NULL COMMENT '关联agent_card.id',
  `auth_type`   varchar(32)  NOT NULL COMMENT 'NONE|API_KEY|HTTP_BEARER|HTTP_BASIC|OAUTH2_CLIENT_CREDENTIALS|MTLS',
  `config_enc`  text         COMMENT 'AES-256-GCM加密后的认证配置JSON',
  `created_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上游Server Agent认证凭证(加密)';

-- ---------- 调用方 ----------
CREATE TABLE IF NOT EXISTS `caller` (
  `id`          varchar(64)  NOT NULL COMMENT '调用方唯一标识',
  `name`        varchar(128) NOT NULL COMMENT '调用方名称',
  `description` varchar(512)  DEFAULT NULL,
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '1:启用 0:禁用',
  `created_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Client Agent调用方';

-- ---------- 调用方 API Key ----------
CREATE TABLE IF NOT EXISTS `caller_credential` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `caller_id`      varchar(64) NOT NULL,
  `key_name`       varchar(64) NOT NULL COMMENT 'Key备注名',
  `api_key_prefix` varchar(16) NOT NULL COMMENT 'Key前8位, 用于日志与界面识别',
  `api_key_hash`   char(64)    NOT NULL COMMENT 'SHA-256(API Key)十六进制, 明文不落库',
  `status`         tinyint     NOT NULL DEFAULT 1 COMMENT '1:启用 0:吊销',
  `expires_at`     datetime     DEFAULT NULL COMMENT '过期时间, NULL=永不过期',
  `created_at`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_hash` (`api_key_hash`),
  KEY `idx_caller` (`caller_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用方API Key(仅存哈希)';

-- ---------- 调用方授权 ----------
CREATE TABLE IF NOT EXISTS `caller_agent_acl` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `caller_id`  varchar(64) NOT NULL,
  `agent_id`   varchar(64) NOT NULL,
  `created_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_caller_agent` (`caller_id`, `agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用方可访问的Agent白名单';

-- ---------- 配置变更日志（同步版本水位） ----------
CREATE TABLE IF NOT EXISTS `config_change_log` (
  `seq`         bigint      NOT NULL AUTO_INCREMENT COMMENT '全局递增版本号(同步水位)',
  `entity_type` varchar(16) NOT NULL COMMENT 'AGENT|UPSTREAM_CRED|CALLER|CALLER_CRED|ACL',
  `entity_id`   varchar(128) NOT NULL COMMENT '实体标识: agent_id / caller_id / key_hash 等',
  `operation`   varchar(8)  NOT NULL COMMENT 'UPSERT|DELETE',
  `changed_at`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`seq`),
  KEY `idx_entity` (`entity_type`, `entity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置变更流水, 节点增量同步依据';
