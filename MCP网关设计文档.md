# 企业级 MCP 平台设计方案

**日期**: 2026-07-26  
**状态**: 设计阶段  

---

## 目录

1. [设计背景与目标](#1-设计背景与目标)
2. [系统全景架构](#2-系统全景架构)
3. [MCP 网关代理层](#3-mcp-网关代理层)
4. [管理台](#4-管理台)
5. [数据模型](#5-数据模型)
6. [接口设计](#6-接口设计)
7. [部署架构](#7-部署架构)
8. [安全设计](#8-安全设计)
9. [实施路线图](#9-实施路线图)
10. [开发交接清单](#10-开发交接清单)

---

## 1. 设计背景与目标

### 1.1 业务场景

```
员工(Alice) → 员工助手(中台Agent) → Agent网关(已有) → 业务Agent → MCP网关 → MCP Server
                                                              ↓
                                                           管理台
                                                              ↓
                                                           认证中心(企业已有)
```

### 1.2 核心问题

在多跳Agent调用链中，终端用户的身份和权限必须安全传递到MCP Server：
- 防止Token透传（MCP规范明确禁止）
- 防止混淆副手攻击（Confused Deputy）
- 支持委托授权（Delegation）
- 实现工具级最小权限
- 提供MCP市场的发布、发现、授权管理能力

### 1.3 设计目标

| 目标 | 说明 |
|------|------|
| **身份传播** | 将用户身份安全传递到MCP Server，支持数据级权限控制 |
| **委托验证** | 验证Agent的委托令牌，确认Agent有权代表用户执行操作 |
| **权限管控** | 工具级（Tool-level）访问控制，最小权限原则 |
| **安全隔离** | Token Exchange机制，禁止原始Token透传 |
| **审计追踪** | 全链路审计，记录谁→通过哪个Agent→调用了什么工具 |
| **协议兼容** | 兼容MCP 2025-11-25规范，支持Streamable HTTP传输 |
| **市场能力** | MCP发布注册、浏览发现、授权管理、审计查询 |

---

## 2. 系统全景架构

### 2.1 三大子系统

| 子系统 | 技术栈 | 核心职责 |
|--------|--------|---------|
| **MCP网关** | OpenResty + Redis | 实时请求处理：协议接入、认证、授权、限流、转发、脱敏、审计上报 |
| **管理台** | React + Spring Boot + MySQL | 控制平面：MCP注册、授权策略配置、市场展示、审计查询、策略同步到网关 |
| **认证中心** | 企业已有服务 | 用户认证、Agent身份、委托令牌颁发、Token Exchange/Introspection |

> 认证中心为企业已有服务，开发调试阶段使用Mock服务替代。

### 2.2 职责边界

| 子系统 | 做什么 | 不做什么 |
|--------|--------|---------|
| **MCP网关** | 协议处理、认证、授权、限流、转发、脱敏、审计上报 | 不持久化业务数据（仅Redis缓存）、不管理注册表、不做复杂策略配置UI |
| **管理台** | MCP注册、授权策略配置、市场展示、审计查询、策略同步到网关Redis | 不处理实时请求流、不做Token验证（委托给认证中心） |
| **认证中心** | 用户认证、Agent身份、委托令牌、Token Exchange/Introspection | 不了解MCP协议细节、不管理工具权限 |
| **Agent网关** | 已有系统，仅做Agent间网络路由 | 不触碰任何凭证，对Token透明 |

### 2.3 系统上下文图

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                      企业AI Agent平台                                       │
│                                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────┐  │
│  │   终端用户    │───▶│   员工助手    │───▶│   Agent网关   │───▶│      业务Agent集群        │  │
│  │  (Alice)     │    │ (中台Agent)   │    │  (已有系统)   │    │  Agent B / Agent C ...   │  │
│  └──────────────┘    └──────────────┘    └──────────────┘    └───────────┬──────────────┘  │
│                                                                         │                 │
│                                                                         │ MCP协议调用      │
│                                                                         ▼                 │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                           MCP 网关 (OpenResty + Redis)                               │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌──────────┐ │   │
│  │  │  Rate Limit │  │    Auth     │  │   Router    │  │   Proxy     │  │  Audit   │ │   │
│  │  │  (限流)      │  │  (认证授权)  │  │  (路由)      │  │  (代理转发)  │  │ (审计)    │ │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  └──────────┘ │   │
│  │         │                │                │                │                │        │   │
│  │         └────────────────┴────────────────┴────────────────┴────────────────┘        │   │
│  │                                          │                                           │   │
│  │                                          ▼                                           │   │
│  │                              ┌─────────────────────┐                                │   │
│  │                              │       Redis 缓存层     │                                │   │
│  │                              │  工具注册表 / 策略缓存   │                                │   │
│  │                              │  Token验证 / 限流计数   │                                │   │
│  │                              └─────────────────────┘                                │   │
│  └────────────────────────────────────────┬────────────────────────────────────────────┘   │
│                                           │                                                │
│                                           ▼                                                │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                           MCP Server 集群                                            │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │   │
│  │  │ 考勤MCP   │  │ 薪资MCP   │  │ 审批MCP   │  │ 文件MCP   │  │    ...           │   │   │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                           管理台 (React + Spring Boot + MySQL)                         │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐    │   │
│  │  │  MCP市场/查询  │  │  MCP发布注册  │  │  授权管理      │  │  审计查询/统计        │    │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────────────┘    │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐    │   │
│  │  │  前端门户      │  │  Spring Boot │  │    MySQL     │  │  认证中心Mock(调试)   │    │   │
│  │  │  (React)     │  │   后端API    │  │   持久化      │  │                     │    │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────────────┘    │   │
│  └────────────────────────────────────────┬────────────────────────────────────────────┘   │
│                                           │                                                │
│                                           │ 策略/注册表同步                                 │
│                                           ▼                                                │
│                                    MCP网关 Redis                                           │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                           企业身份认证中心 (IdP) - 已有服务                             │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐    │   │
│  │  │  用户身份管理  │  │  Agent身份管理 │  │ 委托令牌颁发  │  │  Token Exchange服务   │    │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────────────┘    │   │
│  └─────────────────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.4 请求流转链路

```
1. 业务Agent 携带 Delegation Token 调用 MCP网关
   POST /mcp/v1  Authorization: Bearer <delegation_token>

2. OpenResty Rate Limit: 限流检查 (Redis Token Bucket)
   超过阈值 → 429 Too Many Requests

3. OpenResty Auth: 验证 Delegation Token → 认证中心 Introspection
   Redis缓存命中则跳过; 否则 POST /oauth/introspect

4. OpenResty Auth: 查询授权策略 (Redis缓存 → 管理台API兜底)
   检查Agent是否有权调用该工具, 员工是否有数据权限

5. OpenResty Proxy: Token Exchange (RFC 8693) → 认证中心
   Delegation Token → MCP Server专用 Access Token (audience绑定)

6. OpenResty Proxy: 注入用户身份到请求头, 转发到 MCP Server
   X-User-ID, X-Org-ID, X-Dept-ID + Bearer <mcp_access_token>

7. MCP Server: 验证 audience + 提取用户身份 → 查询数据
   根据 user_id 返回该员工个人的考勤/薪资数据

8. OpenResty Proxy: 响应处理 + 输出脱敏 (PII检测)
   敏感字段替换 → 返回给业务Agent

9. OpenResty Audit: 异步写入审计日志
   → 管理台API (或降级到本地文件)
```

---

## 3. MCP 网关代理层

### 3.1 技术选型：OpenResty + Redis

| 需求 | OpenResty优势 |
|------|--------------|
| **高并发** | Nginx事件驱动 + Lua协程，单机10万+并发 |
| **低延迟** | 请求在Nginx进程内完成，无额外网络跳转 |
| **灵活扩展** | Lua脚本动态加载，无需重启即可更新策略 |
| **生态成熟** | lua-resty-jwt、lua-resty-redis、lua-resty-http 等成熟库 |
| **运维复用** | 企业已有Nginx/OpenResty运维经验 |

### 3.2 Lua模块架构

```
nginx.conf
│
├── init_by_lua_file       init.lua          # 全局初始化：配置加载、JWKS预加载
├── init_worker_by_lua_file init_worker.lua # Worker初始化：定时任务
│
├── http {
│   ├── lua_shared_dict mcp_cache 10m;       # 进程内共享内存
│   │
│   ├── upstream mcp_backend {                # MCP Server动态后端
│   │     balancer_by_lua_file balancer.lua  # 动态负载均衡 + 健康检查
│   │   }
│   │
│   ├── server {
│   │     listen 8080 ssl http2;
│   │
│   │     location /health {                 # 健康检查
│   │     location /ready {                  # 就绪检查
│   │     location /admin/sync {             # 管理台同步推送接收
│   │     location /metrics {                # Prometheus指标
│   │
│   │     location /mcp/v1 {                 # MCP协议主端点
│   │         access_by_lua_file rate_limit.lua    # ① 限流
│   │         access_by_lua_file auth.lua          # ② 认证授权
│   │         rewrite_by_lua_file router.lua       # ③ 路由
│   │         content_by_lua_file proxy.lua        # ④ 代理转发
│   │         log_by_lua_file audit.lua            # ⑤ 审计日志
│   │     }
│   │   }
│   }
```

### 3.3 核心模块职责

#### 3.3.1 init.lua — 初始化模块

- 加载全局配置（认证中心地址、Redis地址、管理台API地址）
- 预加载认证中心JWKS公钥（用于本地JWT签名验证辅助）
- 初始化Redis连接池配置

#### 3.3.2 rate_limit.lua — 限流模块

- 基于Redis实现Token Bucket限流算法
- 支持按Agent ID、按工具名、按Server ID多维限流
- 超限返回429，响应头携带X-RateLimit-*信息

#### 3.3.3 auth.lua — 认证与授权模块

**认证流程：**
1. 从请求头提取 `Authorization: Bearer <token>`
2. 计算Token MD5哈希
3. 优先查询Redis缓存 `mcp:token:verify:<hash>`（TTL 30秒）
4. 缓存未命中 → 向认证中心发起Token Introspection
5. 验证Token的 `active` 状态
6. 验证 `act` 委托链完整性（防止跳过中间环节）
7. 提取用户身份存入 `ngx.ctx`

**授权流程：**
1. 从 `ngx.ctx` 获取用户身份和Agent身份
2. 构建Redis策略Key：`mcp:policy:{org_id}:{agent_id}:{tool_name}`
3. 查询Redis缓存的策略决策结果
4. 缓存未命中 → 调用管理台权限检查API兜底
5. 拒绝则返回403

#### 3.3.4 router.lua — 路由模块

- 解析JSON-RPC 2.0请求体
- 提取方法名和参数
- `tools/call`：提取 `params.name` 作为工具名
- 查询Redis `mcp:tool:server` Hash获取目标Server ID
- 查询Redis `mcp:server:url` Hash获取目标Server地址
- 设置 `ngx.var.target_host` 供proxy使用

#### 3.3.5 proxy.lua — 代理转发模块

**Token Exchange：**
1. 检查Redis缓存 `mcp:token:exchange:<hash>`（TTL 4分钟）
2. 缓存未命中 → 向认证中心发起RFC 8693 Token Exchange
   - `grant_type`: `urn:ietf:params:oauth:grant-type:token-exchange`
   - `subject_token`: 原始Delegation Token
   - `audience`: 目标MCP Server的base_url
   - `scope`: 工具对应的Scope
3. 缓存Exchange结果到Redis

**请求转发：**
1. 构建下游HTTP请求头：
   - `Authorization: Bearer <mcp_access_token>`
   - `X-User-ID`: 委托用户ID
   - `X-Org-ID`: 组织ID
   - `X-Dept-ID`: 部门ID
   - `X-Agent-ID`: 最终执行Agent ID
   - `X-Request-ID`: 请求追踪ID
   - `X-Trace-ID`: 链路追踪ID
2. 转发JSON-RPC请求体到目标MCP Server
3. 接收响应

**响应处理：**
1. 输出内容安全检查（PII检测）
2. 邮箱、手机号等敏感信息脱敏替换
3. 返回给上游业务Agent

**审计上下文记录：**
- 将请求信息、用户身份、工具名、Server ID、响应状态、耗时等存入 `ngx.ctx.audit`

#### 3.3.6 audit.lua — 审计日志模块

- 使用 `ngx.timer.at(0, ...)` 实现异步非阻塞上报
- 首选：HTTP POST到管理台 `/api/v1/audit/batch`
- 降级：写入本地日志文件 `/var/log/mcp-gateway/audit.log`
- 上报失败不阻塞主流程

#### 3.3.7 sync.lua — 管理台同步接收模块

- 接收管理台推送的同步数据（工具注册表、Server地址、授权策略）
- 验证 `X-Internal-Key` 防止未授权访问
- 将数据写入Redis对应Hash/Key
- 支持全量同步和增量同步

#### 3.3.8 balancer.lua — 负载均衡模块

- 根据目标Server ID查询Redis中的后端实例列表
- 支持轮询、加权轮询、一致性哈希策略
- 结合健康检查状态排除不可用实例

### 3.4 Redis数据结构设计

```
# 1. 工具 → Server 映射 (Hash)
HSET mcp:tool:server attendance.query attendance-server
HSET mcp:tool:server salary.query salary-server

# 2. Server → 地址 映射 (Hash)
HSET mcp:server:url attendance-server https://mcp-attendance.internal:8080

# 3. Server → 认证方式 (Hash)
HSET mcp:server:auth attendance-server oauth2

# 4. 工具输入Schema (Hash, JSON字符串)
HSET mcp:tool:schema attendance.query '{"type":"object",...}'

# 5. 工具所需Scope (Hash)
HSET mcp:tool:scope attendance.query mcp:tools/attendance.query

# 6. 授权策略 (String, TTL 1小时)
# Key格式: mcp:policy:{org_id}:{agent_id}:{tool_name}
SET mcp:policy:org-corp:agent-b-prod-001:attendance.query 1 EX 3600
SET mcp:policy:org-corp:agent-b-prod-001:salary.query 0 EX 3600

# 7. Token验证结果缓存 (String, TTL 30秒)
SET mcp:token:verify:<md5(token)> '{"active":true,...}' EX 30

# 8. Token Exchange结果缓存 (String, TTL 4分钟)
SET mcp:token:exchange:<md5(token:audience:scope)> '{"access_token":"..."}' EX 240

# 9. 限流计数器 (String, TTL 61秒)
INCR mcp:ratelimit:{agent_id}:{window}
EXPIRE mcp:ratelimit:{agent_id}:{window} 61

# 10. Server健康状态 (String, TTL 10秒)
SET mcp:server:health:attendance-server healthy EX 10

# 11. Server工具列表 (Set)
SADD mcp:server:tools:attendance-server attendance.query attendance.export
```

### 3.5 nginx.conf 配置要点

```
worker_processes auto;

events {
    worker_connections 4096;
    use epoll;
    multi_accept on;
}

http {
    # Lua共享内存
    lua_shared_dict mcp_cache 10m;
    lua_shared_dict mcp_locks 1m;

    # 初始化
    init_by_lua_file /etc/nginx/lua/init.lua;
    init_worker_by_lua_file /etc/nginx/lua/init_worker.lua;

    # MCP Server动态后端
    upstream mcp_backend {
        server 127.0.0.1:8081 backup;
        balancer_by_lua_file /etc/nginx/lua/balancer.lua;
        keepalive 100;
        keepalive_timeout 60s;
        keepalive_requests 1000;
    }

    server {
        listen 8080 ssl http2;
        server_name mcp-gateway.corp.com;

        ssl_certificate /etc/nginx/certs/server.crt;
        ssl_certificate_key /etc/nginx/certs/server.key;

        # 健康检查
        location /health {
            access_log off;
            return 200 '{"status":"healthy"}';
        }

        # 就绪检查
        location /ready {
            access_log off;
            content_by_lua_file /etc/nginx/lua/ready.lua;
        }

        # 管理台同步推送 (仅允许内网)
        location /admin/sync {
            internal;
            allow 10.0.0.0/8;
            deny all;
            content_by_lua_file /etc/nginx/lua/sync.lua;
        }

        # Prometheus指标
        location /metrics {
            access_log off;
            content_by_lua_file /etc/nginx/lua/metrics.lua;
        }

        # MCP协议主端点
        location /mcp/v1 {
            access_by_lua_file /etc/nginx/lua/rate_limit.lua;
            access_by_lua_file /etc/nginx/lua/auth.lua;
            rewrite_by_lua_file /etc/nginx/lua/router.lua;
            content_by_lua_file /etc/nginx/lua/proxy.lua;
            log_by_lua_file /etc/nginx/lua/audit.lua;
        }

        # SSE兼容端点
        location /mcp/sse {
            access_by_lua_file /etc/nginx/lua/rate_limit.lua;
            access_by_lua_file /etc/nginx/lua/auth.lua;
            rewrite_by_lua_file /etc/nginx/lua/router.lua;
            content_by_lua_file /etc/nginx/lua/proxy_sse.lua;
            log_by_lua_file /etc/nginx/lua/audit.lua;
        }
    }
}
```

---

## 4. 管理台

### 4.1 系统定位

管理台是MCP平台的**控制平面**，面向平台管理员和MCP发布者，提供：
- MCP Server/Tool 的注册与生命周期管理
- 授权策略的配置与下发
- MCP市场的展示与发现
- 审计日志的查询与分析
- 与认证中心的对接（开发阶段使用Mock）
- 向OpenResty网关同步策略和注册表

### 4.2 技术栈

| 层级 | 技术选型 | 说明 |
|------|---------|------|
| 前端 | React 18 + Ant Design 5 | 管理门户 |
| 后端 | Spring Boot 3.x + Spring Security | REST API |
| ORM | MyBatis-Plus | 数据库操作 |
| 数据库 | MySQL 8.0 | 持久化存储 |
| 缓存同步 | Redis (与网关共享) | 策略推送 |
| 消息队列 | RabbitMQ / Kafka (可选) | 审计日志接收 |
| 文档 | SpringDoc OpenAPI | API文档 |

### 4.3 前端页面设计

#### 4.3.1 页面总览

| 页面 | 路径 | 功能 |
|------|------|------|
| **MCP市场首页** | `/market` | 卡片式展示所有已发布MCP，支持搜索、筛选、分类 |
| **MCP详情页** | `/market/{serverId}` | 工具列表、输入Schema、使用说明、在线测试、评分评论 |
| **MCP发布页** | `/registry/publish` | 表单注册新MCP Server，上传工具元数据，支持自动发现 |
| **授权管理页** | `/auth/policies` | 配置哪些Agent/员工/角色可以使用哪些工具 |
| **审计日志页** | `/audit/logs` | 查询调用记录，支持多维筛选和报表导出 |
| **系统设置** | `/settings` | 网关配置、限流参数、缓存策略、同步状态 |

#### 4.3.2 MCP市场首页

布局描述：
- 顶部：搜索框 + 分类Tab（全部/人力资源/财务/办公协同/数据分析/开发工具）+ 筛选器
- 主体：卡片网格布局，每卡片包含：
  - MCP名称、所属部门、版本号
  - 星级评分、累计调用次数
  - 工具数量
  - 状态标签（已订阅/需授权/未授权）
  - "查看详情"按钮
- 底部：分页器 + 统计信息（共X个MCP Server，Y个工具）

#### 4.3.3 MCP详情页

Tab页签设计：
- **概览**：描述、接入信息（协议/地址/认证方式）、所有者、数据分类、版本历史
- **工具列表**：表格展示所有工具，含工具名、描述、权限要求、数据分类、操作（在线测试）
- **文档**：使用说明、接入指南、示例代码
- **评论**：用户评分和评论
- **授权管理**：当前授权状态概览 + 授权对象列表（Agent/员工/角色）+ 新增授权按钮

#### 4.3.4 MCP发布页

表单分区：
- **基本信息**：MCP名称、MCP ID（唯一标识）、所属部门、数据分类、描述
- **接入信息**：协议类型（Streamable HTTP/SSE）、服务地址、认证方式、OAuth Client ID、健康检查端点
- **工具定义**：
  - 手动添加：工具名、描述、输入Schema（JSON编辑器）、输出Schema、注解、所需Scope、限流配置
  - 从文件导入：支持JSON批量导入
  - 自动发现：输入Server地址，调用 `tools/list` 自动注册
- **操作按钮**：保存草稿 / 提交审核 / 立即发布

#### 4.3.5 授权管理页

布局描述：
- 顶部："新建授权策略"按钮 + 筛选器（MCP/Agent/员工/角色/状态）
- 主体：策略表格，列包括：
  - MCP Server名称
  - 工具名
  - 被授权对象（名称 + 类型标签：Agent/员工/角色/用户组）
  - 授权范围
  - 有效期
  - 状态（生效/待审/过期/撤销）
  - 操作（编辑/撤销/审核）
- 底部：分页器

新建策略弹窗：
- 选择MCP Server → 选择工具（可多选或全部）
- 选择被授权对象类型 → 搜索并选择具体对象
- 配置数据权限范围（自己/团队/部门/全组织）
- 配置约束条件（调用频率限制、有效时间段）
- 设置有效期（永久/固定期限）
- 提交

#### 4.3.6 审计日志页

布局描述：
- 顶部：时间范围选择器 + 筛选器（用户/Agent/工具/MCP Server/策略决策）
- 主体：日志表格，列包括：
  - 时间戳
  - 请求ID
  - 委托用户
  - 调用Agent
  - 工具名
  - MCP Server
  - 认证结果
  - 策略决策
  - 延迟(ms)
  - 响应状态
- 统计面板：总调用量、平均延迟、拒绝率、Top工具、Top Agent
- 导出按钮：支持Excel/CSV导出

### 4.4 Spring Boot后端模块设计

#### 4.4.1 项目结构

```
mcp-admin/
├── src/main/java/com/corp/mcp/admin/
│   ├── McpAdminApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java          # Spring Security + JWT
│   │   ├── RedisConfig.java             # Redis连接配置
│   │   ├── IdPClientConfig.java         # 认证中心客户端配置
│   │   └── WebMvcConfig.java            # CORS、拦截器
│   ├── controller/
│   │   ├── MarketController.java        # MCP市场API
│   │   ├── RegistryController.java      # MCP注册管理API
│   │   ├── AuthPolicyController.java    # 授权策略API
│   │   ├── AuditController.java         # 审计日志API
│   │   ├── SyncController.java          # 网关同步推送API
│   │   └── HealthController.java        # 健康检查
│   ├── service/
│   │   ├── MarketService.java
│   │   ├── RegistryService.java
│   │   ├── AuthPolicyService.java
│   │   ├── AuditService.java
│   │   ├── SyncService.java             # 向OpenResty推送同步
│   │   └── IdPClientService.java        # 认证中心对接
│   ├── domain/
│   │   ├── entity/                      # MyBatis-Plus实体
│   │   ├── dto/                         # 请求/响应DTO
│   │   ├── vo/                          # 视图对象
│   │   └── enums/                       # 枚举
│   ├── mapper/                          # MyBatis Mapper接口
│   ├── repository/                      # 数据访问层
│   ├── security/
│   │   ├── JwtAuthenticationFilter.java
│   │   └── UserDetailsServiceImpl.java
│   └── util/
│       ├── RedisUtil.java
│       └── JsonUtil.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   └── mapper/                          # XML映射文件
└── pom.xml
```

#### 4.4.2 核心Service职责

**RegistryService — MCP注册管理**
- MCP Server的CRUD（含软删除）
- MCP Tool的CRUD
- 从远程MCP Server自动发现工具（调用 `tools/list`）
- 版本管理
- 状态流转：草稿 → 待审 → 活跃 → 废弃

**AuthPolicyService — 授权策略管理**
- 策略的CRUD
- 策略审批工作流
- 策略生效/过期自动处理
- 批量授权/撤销
- 权限检查（供网关兜底调用）

**SyncService — 网关同步**
- 定时全量同步（每5分钟）
- 策略变更时增量同步
- 向OpenResty网关 `/admin/sync` 推送数据
- 同步状态监控

**AuditService — 审计日志**
- 批量接收网关上报的审计日志
- 日志查询（分页、筛选、排序）
- 统计报表生成
- 日志导出

**IdPClientService — 认证中心对接**
- 用户身份查询
- Agent身份查询
- Token Introspection代理
- Token Exchange代理
- **开发阶段**：提供Mock实现，模拟认证中心响应

#### 4.4.3 认证中心Mock（开发调试）

开发阶段使用Mock服务替代真实认证中心：

```
Mock认证中心提供以下端点：

POST /oauth/token
  功能：用户登录获取JWT / Token Exchange
  请求：username/password 或 token-exchange参数
  响应：{ access_token, token_type, expires_in, scope }

POST /oauth/introspect
  功能：Token验证
  请求：token
  响应：{ active, sub, aud, exp, delegation, act }

GET /.well-known/jwks.json
  功能：JWKS公钥
  响应：{ keys: [...] }

GET /api/users/{userId}
  功能：查询用户信息
  响应：{ user_id, name, org_id, dept_id, role }

GET /api/agents/{agentId}
  功能：查询Agent信息
  响应：{ agent_id, name, type, owner, scopes }
```

Mock数据可配置，支持预设测试用户和Agent。

---

## 5. 数据模型

### 5.1 MySQL表结构

#### mcp_servers — MCP Server注册表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 主键 |
| server_id | VARCHAR(64) UNIQUE | MCP Server唯一标识 |
| name | VARCHAR(128) | 显示名称 |
| description | TEXT | 描述 |
| category | VARCHAR(32) | 分类：hr/finance/office/dev |
| base_url | VARCHAR(512) | 服务地址 |
| protocol_type | VARCHAR(16) DEFAULT 'streamable-http' | 协议类型 |
| auth_type | VARCHAR(32) DEFAULT 'oauth2' | 认证方式 |
| oauth_client_id | VARCHAR(64) | OAuth Client ID |
| data_classification | VARCHAR(32) | 数据分类 |
| owner_team | VARCHAR(64) | 所属团队 |
| owner_email | VARCHAR(128) | 负责人邮箱 |
| status | TINYINT DEFAULT 0 | 0:草稿 1:待审 2:活跃 3:废弃 4:删除 |
| version | VARCHAR(32) | 版本号 |
| health_endpoint | VARCHAR(128) DEFAULT '/health' | 健康检查端点 |
| health_status | VARCHAR(16) DEFAULT 'unknown' | 健康状态 |
| health_checked_at | DATETIME | 最后健康检查时间 |
| total_calls | BIGINT DEFAULT 0 | 累计调用次数 |
| avg_latency_ms | INT | 平均延迟 |
| rating | DECIMAL(2,1) | 评分 |
| created_by | VARCHAR(128) | 创建人 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |
| deleted | TINYINT DEFAULT 0 | 逻辑删除标志 |

#### mcp_tools — MCP工具元数据

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 主键 |
| server_id | VARCHAR(64) | 所属MCP Server |
| tool_name | VARCHAR(128) | 工具名 |
| description | TEXT | 描述 |
| input_schema | JSON | 输入参数Schema |
| output_schema | JSON | 输出参数Schema |
| annotations | JSON | 注解 |
| required_scope | VARCHAR(128) | 所需Scope |
| rate_limit_rpm | INT DEFAULT 60 | 每分钟限流 |
| data_classification | VARCHAR(32) | 数据分类 |
| is_active | TINYINT DEFAULT 1 | 是否启用 |
| created_at | DATETIME | 创建时间 |

#### auth_policies — 授权策略表（核心）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 主键 |
| policy_name | VARCHAR(128) | 策略名称 |
| server_id | VARCHAR(64) | MCP Server ID |
| tool_name | VARCHAR(128) DEFAULT '*' | 工具名，*表示全部 |
| grantee_type | VARCHAR(16) | AGENT/USER/ROLE/GROUP |
| grantee_id | VARCHAR(128) | 被授权对象ID |
| grantee_name | VARCHAR(128) | 被授权对象名称 |
| allowed_scopes | JSON | 允许的Scope列表 |
| allowed_tools | JSON | 允许的工具列表 |
| constraints | JSON | 约束条件 |
| data_scope | VARCHAR(16) DEFAULT 'self' | 数据权限范围 |
| effective_time | DATETIME | 生效时间 |
| expiry_time | DATETIME | 过期时间 |
| is_permanent | TINYINT DEFAULT 0 | 是否永久 |
| status | TINYINT DEFAULT 0 | 0:待审 1:生效 2:过期 3:撤销 |
| approved_by | VARCHAR(128) | 审批人 |
| approved_at | DATETIME | 审批时间 |
| created_by | VARCHAR(128) | 创建人 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

#### agent_bindings — Agent绑定表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 主键 |
| agent_id | VARCHAR(64) | Agent ID |
| agent_name | VARCHAR(128) | Agent名称 |
| agent_type | VARCHAR(32) | 类型 |
| server_id | VARCHAR(64) | 绑定的MCP Server |
| allowed_tools | JSON | 允许的工具列表 |
| binding_status | TINYINT DEFAULT 0 | 0:待审 1:生效 2:撤销 |
| created_at | DATETIME | 创建时间 |

#### user_bindings — 用户绑定表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 主键 |
| user_id | VARCHAR(128) | 用户ID |
| user_name | VARCHAR(128) | 用户名称 |
| org_id | VARCHAR(64) | 组织ID |
| dept_id | VARCHAR(64) | 部门ID |
| server_id | VARCHAR(64) | 绑定的MCP Server |
| allowed_tools | JSON | 允许的工具列表 |
| binding_status | TINYINT DEFAULT 0 | 0:待审 1:生效 2:撤销 |
| created_at | DATETIME | 创建时间 |

#### audit_logs — 审计日志表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | 主键 |
| request_id | VARCHAR(64) | 请求ID |
| trace_id | VARCHAR(64) | 链路追踪ID |
| timestamp | DATETIME | 时间戳 |
| caller_agent_id | VARCHAR(64) | 调用Agent ID |
| delegator_user_id | VARCHAR(128) | 委托用户ID |
| delegator_org_id | VARCHAR(64) | 委托组织ID |
| tool_name | VARCHAR(128) | 工具名 |
| server_id | VARCHAR(64) | MCP Server ID |
| request_method | VARCHAR(16) | 请求方法 |
| request_path | VARCHAR(512) | 请求路径 |
| request_args_hash | VARCHAR(64) | 参数SHA256哈希 |
| auth_result | VARCHAR(16) | 认证结果 |
| policy_decision | VARCHAR(16) | 策略决策 |
| deny_reason | VARCHAR(256) | 拒绝原因 |
| latency_ms | INT | 延迟(ms) |
| response_status | INT | 响应状态码 |
| response_size | INT | 响应大小 |
| client_ip | VARCHAR(64) | 客户端IP |
| user_agent | VARCHAR(256) | User-Agent |
| created_at | DATETIME | 创建时间 |

> 审计日志表建议按年分区（PARTITION BY RANGE YEAR(timestamp)）。

### 5.2 核心实体关系图

```
mcp_servers (1) ──────< (N) mcp_tools
    │
    │ (1) ──────< (N) auth_policies
    │                    │
    │                    ├── grantee_type = AGENT → agent_bindings
    │                    ├── grantee_type = USER  → user_bindings
    │                    └── grantee_type = ROLE → (角色在认证中心)
    │
    │ (1) ──────< (N) agent_bindings
    │ (1) ──────< (N) user_bindings
    │
    └───────< (N) audit_logs
```

---

## 6. 接口设计

### 6.1 管理台对外API

#### MCP市场

```
GET /api/v1/market/servers
  参数: category, keyword, page, size
  响应: { total, list: [McpServerVO] }

GET /api/v1/market/servers/{serverId}
  响应: McpServerDetailVO (含tools, policies, docs, healthStatus)
```

#### MCP注册管理

```
POST /api/v1/registry/servers
  请求: McpServerCreateDTO
  响应: 201 Created

PUT /api/v1/registry/servers/{serverId}
  请求: McpServerUpdateDTO

DELETE /api/v1/registry/servers/{serverId}
  说明: 软删除

GET /api/v1/registry/servers/{serverId}
  响应: McpServerDetailVO

POST /api/v1/registry/servers/{serverId}/tools
  请求: McpToolCreateDTO

GET /api/v1/registry/servers/{serverId}/tools
  响应: [McpToolVO]

PUT /api/v1/registry/servers/{serverId}/tools/{toolName}

DELETE /api/v1/registry/servers/{serverId}/tools/{toolName}

POST /api/v1/registry/servers/{serverId}/discover
  说明: 从远程MCP Server自动发现工具
```

#### 授权管理

```
GET /api/v1/auth/policies
  参数: serverId, granteeType, status, page, size
  响应: { total, list: [AuthPolicyVO] }

POST /api/v1/auth/policies
  请求: AuthPolicyCreateDTO
  响应: 201 Created

PUT /api/v1/auth/policies/{policyId}
  请求: AuthPolicyUpdateDTO

DELETE /api/v1/auth/policies/{policyId}
  说明: 撤销策略

GET /api/v1/auth/policies/{policyId}
  响应: AuthPolicyVO

POST /api/v1/auth/policies/{policyId}/approve
  请求: { approved: boolean, comment: string }
  说明: 审批策略

POST /api/v1/auth/policies/batch
  请求: { policies: [AuthPolicyCreateDTO] }
  说明: 批量创建

DELETE /api/v1/auth/policies/batch
  请求: { policyIds: [long] }
  说明: 批量撤销

POST /api/v1/auth/check
  请求: { agentId, userId, toolName, serverId }
  响应: { allowed: boolean, scopes: [string], constraints: object }
  说明: 权限检查（供网关兜底调用）
```

#### 审计日志

```
GET /api/v1/audit/logs
  参数: startTime, endTime, userId, agentId, toolName, serverId, policyDecision, page, size
  响应: { total, list: [AuditLogVO] }

POST /api/v1/audit/logs/batch
  请求: { logs: [AuditLogDTO] }
  说明: 批量接收审计日志（供网关调用，内部接口）

GET /api/v1/audit/statistics
  参数: period (hour/day/week/month)
  响应: { totalCalls, avgLatency, topTools, topAgents, denyRate }
```

#### 网关同步

```
POST /api/v1/sync/push
  请求: { servers: [...], tools: [...], policies: [...] }
  说明: 推送同步到网关（内部接口，管理台调用网关）

GET /api/v1/sync/status
  响应: { lastSyncTime, syncedServers, syncedPolicies, gatewayStatus }
```

### 6.2 核心DTO定义

#### McpServerCreateDTO

```
serverId: string (required, 唯一标识)
name: string (required)
description: string
category: string (enum: hr/finance/office/dev/...)
baseUrl: string (required)
protocolType: string (enum: streamable-http/sse, default: streamable-http)
authType: string (enum: oauth2/apikey/mTLS/none, default: oauth2)
oauthClientId: string
dataClassification: string (enum: public/internal/confidential/restricted)
ownerTeam: string
ownerEmail: string
healthEndpoint: string (default: /health)
tools: [McpToolCreateDTO]
```

#### McpToolCreateDTO

```
toolName: string (required)
description: string (required)
inputSchema: JSON string (required)
outputSchema: JSON string
annotations: JSON string (如 {"readOnly": true})
requiredScope: string
rateLimitRpm: integer (default: 60)
dataClassification: string
```

#### AuthPolicyCreateDTO

```
policyName: string
serverId: string (required)
toolName: string (default: *, 表示全部工具)
granteeType: string (required, enum: AGENT/USER/ROLE/GROUP)
granteeId: string (required)
allowedScopes: [string]
allowedTools: [string]
constraints: object (如 {"max_calls_per_minute": 60, "time_range": "09:00-18:00"})
dataScope: string (enum: self/team/department/organization, default: self)
effectiveTime: datetime
expiryTime: datetime
isPermanent: boolean (default: false)
```

#### AuditLogDTO

```
requestId: string
traceId: string
timestamp: datetime
callerAgentId: string
delegatorUserId: string
delegatorOrgId: string
toolName: string
serverId: string
requestMethod: string
requestPath: string
requestArgsHash: string
authResult: string (success/failed)
policyDecision: string (allow/deny)
denyReason: string
latencyMs: integer
responseStatus: integer
responseSize: integer
clientIp: string
userAgent: string
```

---

## 7. 部署架构

### 7.1 生产部署

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Kubernetes 集群                                 │
│                                                                              │
│  ┌─────────────────────────┐  ┌─────────────────────────┐                  │
│  │   MCP网关 (OpenResty)    │  │   管理台 (Spring Boot)   │                  │
│  │   ┌─────────────────┐   │  │   ┌─────────────────┐   │                  │
│  │   │  Pod 1 (Nginx)   │   │  │   │  Pod 1         │   │                  │
│  │   │  Pod 2 (Nginx)   │   │  │   │  Pod 2         │   │                  │
│  │   │  Pod 3 (Nginx)   │   │  │   │  Pod 3         │   │                  │
│  │   └─────────────────┘   │  │   └─────────────────┘   │                  │
│  │        Service: 8080    │  │        Service: 8080    │                  │
│  └─────────────────────────┘  └─────────────────────────┘                  │
│            │                           │                                     │
│            ▼                           ▼                                     │
│  ┌─────────────────────────┐  ┌─────────────────────────┐                  │
│  │   Redis Cluster          │  │   MySQL 8.0             │                  │
│  │   ┌─────────────────┐   │  │   ┌─────────────────┐   │                  │
│  │   │  Master         │   │  │   │  Primary         │   │                  │
│  │   │  Replica 1      │   │  │   │  Replica 1       │   │                  │
│  │   │  Replica 2      │   │  │   │  Replica 2       │   │                  │
│  │   └─────────────────┘   │  │   └─────────────────┘   │                  │
│  └─────────────────────────┘  └─────────────────────────┘                  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────┐                    │
│  │   前端 (React) - Nginx静态资源                        │                    │
│  │   ┌─────────────────┐                               │                    │
│  │   │  Nginx Pod      │                               │                    │
│  │   │  (build静态文件) │                               │                    │
│  │   └─────────────────┘                               │                    │
│  └─────────────────────────────────────────────────────┘                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Docker Compose 开发环境

```yaml
version: '3.8'

services:
  mcp-gateway:
    image: openresty/openresty:1.25.3.1-alpine
    ports:
      - "8080:8080"
    volumes:
      - ./nginx.conf:/usr/local/openresty/nginx/conf/nginx.conf
      - ./lua:/etc/nginx/lua
      - ./certs:/etc/nginx/certs
    depends_on:
      - redis
    networks:
      - mcp-network

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    networks:
      - mcp-network

  mcp-admin:
    image: mcp-admin:2.0.0
    ports:
      - "8081:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/mcp_admin?useUnicode=true&characterEncoding=utf8
      - SPRING_DATASOURCE_USERNAME=mcp
      - SPRING_DATASOURCE_PASSWORD=mcp123
      - SPRING_REDIS_HOST=redis
      - SPRING_REDIS_PORT=6379
      - MCP_GATEWAY_SYNC_URL=http://mcp-gateway:8080
      - MCP_GATEWAY_SYNC_SECRET=mcp-admin-secret
      - IDP_BASE_URL=http://idp-mock:8082
    depends_on:
      - mysql
      - redis
      - idp-mock
    networks:
      - mcp-network

  mcp-admin-ui:
    image: mcp-admin-ui:2.0.0
    ports:
      - "80:80"
    depends_on:
      - mcp-admin
    networks:
      - mcp-network

  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=root123
      - MYSQL_DATABASE=mcp_admin
      - MYSQL_USER=mcp
      - MYSQL_PASSWORD=mcp123
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - mcp-network

  idp-mock:
    image: mcp-idp-mock:1.0.0
    ports:
      - "8082:8082"
    networks:
      - mcp-network

volumes:
  redis-data:
  mysql-data:

networks:
  mcp-network:
    driver: bridge
```

---

## 8. 安全设计

### 8.1 安全原则

| 原则 | 实现 |
|------|------|
| **禁止Token透传** | 所有Token必须经过Exchange，原始Delegation Token不转发到MCP Server |
| **最小权限** | 工具级Scope，每个工具独立授权 |
| **短生命周期** | MCP Access Token有效期5分钟，Delegation Token有效期1小时 |
| **audience绑定** | 使用RFC 8707资源指示器，Token绑定到特定MCP Server |
| **输入消毒** | 所有工具参数经过正则过滤，防止命令注入 |
| **输出脱敏** | 响应内容经过PII检测和脱敏处理 |

### 8.2 威胁防护

| 威胁 | 防护措施 |
|------|---------|
| **Confused Deputy** | act链验证 + audience绑定 + Token Exchange |
| **Token重放** | jti黑名单 + 短期Token + 单次使用限制 |
| **权限提升** | 策略引擎严格匹配，拒绝任何未显式授权的Scope |
| **Prompt Injection** | 输出内容过滤，禁止执行工具返回的指令 |
| **数据泄露** | PII检测 + 数据分类 + 字段级脱敏 |
| **DoS攻击** | 速率限制 + 熔断 + 资源配额 |

### 8.3 输入验证规则

```yaml
input_validation:
  global_rules:
    - max_input_length: 10000
    - strip_shell_metachars: true
    - block_patterns:
        - "$("
        - "`"
        - "; rm "
        - "| curl"
        - "| wget"
        - "<script"

  per_tool_rules:
    attendance.query:
      - employee_id:
          type: email
          pattern: "^[a-zA-Z0-9._%+-]+@corp\.com$"
      - month:
          type: string
          pattern: "^\d{4}-\d{2}$"

    salary.query:
      - employee_id:
          type: email
          pattern: "^[a-zA-Z0-9._%+-]+@corp\.com$"
          # 强制与delegator一致（在Policy Engine中检查）
```

---

## 9. 实施路线图

### Phase 1: MVP (4周)

**MCP网关：**
- [ ] OpenResty基础框架搭建（nginx.conf + Lua模块骨架）
- [ ] Redis缓存层部署
- [ ] 基础认证（Delegation Token验证 + Introspection）
- [ ] 基础路由（工具名 → MCP Server）
- [ ] 基础代理转发（HTTP转发 + 用户身份注入）
- [ ] 审计日志异步上报

**管理台：**
- [ ] Spring Boot项目搭建 + MySQL基础表
- [ ] MCP Server CRUD API
- [ ] MCP Tool CRUD API
- [ ] 基础授权策略（Agent级）
- [ ] 审计日志接收与查询API
- [ ] 认证中心Mock服务
- [ ] React项目搭建 + 基础页面框架
- [ ] MCP列表页 + 详情页

**集成：**
- [ ] 管理台 → 网关Redis同步机制
- [ ] 端到端联调（业务Agent → MCP网关 → MCP Server Mock）

### Phase 2: 生产就绪 (4周)

**MCP网关：**
- [ ] Token Exchange (RFC 8693) 完整实现
- [ ] 限流熔断（Token Bucket + 熔断器）
- [ ] 响应脱敏（PII检测）
- [ ] 动态负载均衡 + 健康检查
- [ ] SSE协议兼容转发
- [ ] Prometheus指标暴露

**管理台：**
- [ ] MCP市场首页（卡片式 + 搜索筛选）
- [ ] MCP发布页（表单 + 自动发现）
- [ ] 授权管理页（Agent + 员工 + 角色三级授权）
- [ ] 审计查询页（多维筛选 + 统计面板）
- [ ] 策略审批工作流
- [ ] 系统设置页

**集成：**
- [ ] 全链路测试
- [ ] 性能压测

### Phase 3: 企业级增强 (4周)

- [ ] 多租户支持
- [ ] 高级审计（行为基线、异常检测）
- [ ] MCP Server自动健康检查
- [ ] 审批工作流自定义
- [ ] 高可用部署（多实例 + 负载均衡）
- [ ] 监控告警（Prometheus + Grafana）
- [ ] 管理台权限分级（超级管理员/平台运营/MCP发布者/审计员）
- [ ] 日志归档与清理策略

---

## 10. 开发交接清单

### 10.1 MCP网关 (OpenResty) 开发清单

| 优先级 | 模块 | 文件 | 说明 |
|--------|------|------|------|
| P0 | 主配置 | `nginx.conf` | 所有location、upstream、ssl、共享内存配置 |
| P0 | 初始化 | `lua/init.lua` | 全局配置加载、JWKS预加载、Redis连接池配置 |
| P0 | 限流 | `lua/rate_limit.lua` | Redis Token Bucket限流，支持多维度 |
| P0 | 认证 | `lua/auth.lua` | Token Introspection（Redis缓存 → 认证中心）、act链验证 |
| P0 | 授权 | `lua/auth.lua` | Redis策略查询、管理台API兜底 |
| P0 | 路由 | `lua/router.lua` | JSON-RPC解析、工具名路由、Redis查询 |
| P0 | 代理 | `lua/proxy.lua` | Token Exchange、请求转发、响应脱敏 |
| P0 | 审计 | `lua/audit.lua` | 异步审计上报（ngx.timer.at） |
| P0 | 同步 | `lua/sync.lua` | 接收管理台推送，写Redis |
| P1 | 负载均衡 | `lua/balancer.lua` | 动态后端选择、健康检查 |
| P1 | SSE代理 | `lua/proxy_sse.lua` | SSE协议兼容转发 |
| P1 | 就绪检查 | `lua/ready.lua` | Redis、认证中心连通性检查 |
| P1 | 指标 | `lua/metrics.lua` | Prometheus格式指标暴露 |
| P2 | Worker初始化 | `lua/init_worker.lua` | 定时任务（JWKS刷新、健康检查） |

**Lua库依赖：**
- `lua-resty-jwt` — JWT验证
- `lua-resty-redis` — Redis客户端
- `lua-resty-http` — HTTP客户端
- `lua-resty-cjson` — JSON处理

### 10.2 管理台 (Spring Boot) 开发清单

| 优先级 | 模块 | 说明 |
|--------|------|------|
| P0 | `McpServer` 全套CRUD | Entity + Mapper + Service + Controller + DTO/VO |
| P0 | `McpTool` 全套CRUD | 工具元数据管理 |
| P0 | `AuthPolicy` 全套CRUD | 授权策略（核心表） |
| P0 | `AuditLog` 接收/查询 | 批量接收网关上报 + 分页查询 |
| P0 | `SyncService` | 向OpenResty推送同步数据（全量+增量） |
| P0 | `IdPClientService` + Mock | 认证中心对接 + 开发Mock |
| P1 | `MarketService` + Controller | MCP市场API（搜索、筛选、详情） |
| P1 | `RegistryService` + Controller | 注册管理 + 自动发现 |
| P1 | 策略审批 | 审批工作流API |
| P1 | 统计报表 | 审计统计API |
| P2 | `AgentBinding` / `UserBinding` | 绑定关系管理 |
| P2 | 多租户 | 租户隔离 |

### 10.3 管理台 (React前端) 开发清单

| 优先级 | 页面 | 说明 |
|--------|------|------|
| P0 | 登录页 | 对接管理台JWT认证 |
| P0 | 布局框架 | 侧边栏导航 + 顶部栏 + 内容区 |
| P0 | MCP列表页 | 卡片式展示，搜索/筛选/分页 |
| P0 | MCP详情页 | 概览/工具列表/文档/评论/授权管理Tab |
| P0 | MCP发布页 | 表单（基本信息+接入信息+工具定义） |
| P0 | 授权管理页 | 策略表格 + 新建策略弹窗 |
| P0 | 审计日志页 | 日志表格 + 筛选器 + 统计面板 |
| P1 | 系统设置页 | 网关配置、同步状态 |
| P1 | 个人中心 | 用户信息、我的授权 |

### 10.4 认证中心Mock 开发清单

| 优先级 | 端点 | 说明 |
|--------|------|------|
| P0 | `POST /oauth/token` | 用户登录JWT / Token Exchange |
| P0 | `POST /oauth/introspect` | Token验证 |
| P0 | `GET /.well-known/jwks.json` | JWKS公钥 |
| P1 | `GET /api/users/{userId}` | 查询用户信息 |
| P1 | `GET /api/agents/{agentId}` | 查询Agent信息 |
| P1 | `GET /api/roles/{roleId}` | 查询角色信息 |

Mock数据配置文件：`mock-data.json`（预设测试用户、Agent、角色）

---

**文档结束**
