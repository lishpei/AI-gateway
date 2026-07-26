# Agent 网关系统（MCP 网关 + A2A Agent 网关）

双网关布局，覆盖 Agent 生态两类协议：

- **MCP 网关**（《MCP网关详细设计文档》）：Agent → 工具。MCP Server 注册发现、工具级授权、
  用户身份委托传递（Delegation Token + RFC 8693 Token Exchange，禁止 Token 透传）、
  双传输协议（Streamable HTTP / 旧版 HTTP+SSE）、限流、审计。
- **A2A Agent 网关**（《Agent网关详细设计文档》）：Agent ↔ Agent。A2A v1.0.0 Agent Card 托管与发现
  （URL 重写 + ETag/304）、调用方 API Key + ACL 认证（防枚举）、5 种上游凭证代换、
  JSON-RPC/SSE 透传、节点轮询拉取配置（版本水位 + 全量兜底）。

## 项目结构

| 目录 | 说明 | 技术栈 | 端口 |
|---|---|---|---|
| `mcp-admin/` | MCP 管理台后端（+ 内嵌 IdP Mock） | Spring Boot 3.2 | 8080 |
| `mcp-admin-ui/` | MCP 管理台前端 | Vite + React 18 + antd 5 | 5173 |
| `mcp-gateway/` | MCP 网关数据面 | OpenResty（WSL） | 9080 |
| `mcp-server-mock/` | Mock MCP Server（考勤，双传输） | Node.js | 8090 |
| `agent-admin/` | A2A 管理面（Card/调用方/同步 API） | Spring Boot 3.2 | 8081 |
| `agent-admin-ui/` | A2A 管理面前端 | Vite + React 18 + antd 5 | 5174 |
| `a2a-gateway/` | A2A 网关数据面 | OpenResty（WSL） | 9081 |
| `agent-server-mock/` | Mock A2A Server Agent（weather） | Node.js | 8091 |
| `e2e/` | MCP 网关 E2E（16 个） | pytest | - |
| `e2e-agent/` | A2A 网关 E2E（13 个，独立目录避免 conftest 冲突） | pytest | - |
| `scripts/` | 启动/停止/WSL 安装脚本 | PowerShell + Bash | - |

设计文档：`MCP网关详细设计文档.md`、`Agent网关详细设计文档.md`

## 本地开发环境（Windows 10/11 单机，无 Docker）

### 已安装组件

| 组件 | 位置 | 说明 |
|---|---|---|
| JDK 17 | `D:\dev\jdk-17` | Temurin 17.0.19，已配置用户环境变量 JAVA_HOME/Path |
| Maven 3.9 | `D:\dev\maven` | 已配置阿里云镜像 |
| MySQL 8.0.29 | `D:\dev\mysql`（服务名 `MySQL8`） | 库 `mcp_admin`，账号 `mcp/mcp123`，root 无密码 |
| Node.js | 系统已装 v24 | 前端与 Mock Server |
| OpenResty + Redis | **WSL2 Ubuntu**（重启后按下方步骤安装） | 网关数据面 |

### 首次：WSL 环境安装（需重启一次）

```powershell
# 1. 安装 WSL2 Ubuntu（功能已启用，若未装发行版）
wsl --install -d Ubuntu
# ⚠️ 完成后重启电脑。若注册时报 0x800701bc，先装 WSL2 内核更新包：
#    https://wslstorestorage.blob.core.windows.net/wslblob/wsl_update_x64.msi

# 2. 进入 WSL，初始化网关环境（OpenResty + Redis + lua-resty-http）
wsl -d Ubuntu
bash /mnt/d/code/ai/agent-gateway/scripts/wsl-setup.sh
```

**防火墙**：WSL2 (Win10 NAT) 访问 Windows 侧服务需放行 vEthernet (WSL) 网卡，
`start-all.ps1` 已内置该命令（幂等）：
`Set-NetFirewallProfile -Profile Public -DisabledInterfaceAliases "vEthernet (WSL)"`

**上游地址**：管理台注册的 Server `baseUrl` 必须是 **WSL 可达的 Windows 主机 IP**
（`ip route show default | awk '{print $3}'`，随重启可能变化）；
`resourceUri` 是 audience 标识符，保持注册值即可。E2E 的 conftest 已自动解析主机 IP。

### 日常启动

```powershell
# 一键启动（6 个组件：两管理面 + 两 Mock + 两网关）
D:\code\ai\agent-gateway\scripts\start-all.ps1

# 一键停止
D:\code\ai\agent-gateway\scripts\stop-all.ps1

# 前端开发服务器（可选）
cd D:\code\ai\agent-gateway\mcp-admin-ui
npm run dev        # MCP 管理台: http://localhost:5173
cd ..\agent-admin-ui
npm run dev        # A2A 管理面: http://localhost:5174
```

### 组件地址

| 组件 | 地址 | 说明 |
|---|---|---|
| MCP 管理台 | http://localhost:8080 | Swagger: `/swagger-ui.html` |
| MCP 管理台前端 | http://localhost:5173 | dev token: `dev-admin-token-2026` |
| IdP Mock | http://localhost:8080/idp-mock | 测试用户 alice/alice123、bob/bob123 |
| Mock MCP Server | http://localhost:8090 | 考勤工具 attendance.query/stream |
| MCP 网关 | http://localhost:9080 | WSL 内运行 |
| A2A 管理面 | http://localhost:8081 | Swagger: `/swagger-ui.html` |
| Mock A2A Server | http://localhost:8091 | weather-reporter（上游要 X-Api-Key: mock-upstream-key） |
| A2A 网关 | http://localhost:9081 | WSL 内运行 |

### 测试

```powershell
# 后端单测
cd mcp-admin; mvn test
cd ..\agent-admin; mvn test

# E2E（需全部组件运行）
cd e2e; pytest -v              # MCP 网关 16 个
cd ..\e2e-agent; pytest -v     # A2A 网关 13 个
```

## 核心安全设计（详见设计文档）

1. **禁止 Token 透传**：Delegation Token 终止于网关，经 RFC 8693 Token Exchange 换发为
   audience 绑定到目标 Server 的短时令牌（5min）。
2. **委托链验证**：`act` 链最内层必须等于持证者，防 Confused Deputy。
3. **工具级授权**：策略快照 + 网关本地求值（DENY 优先、默认拒绝、fail-closed）。
4. **参数绑定**：工具参数可强制等于委托用户身份（如 `employee_id` 必须等于用户邮箱）。
5. **头部 hygiene**：入站身份头一律剥离，仅由网关注入。

## 生产部署（K8s + Docker，后续）

本地为单机无 Docker 形态；生产按设计文档第 14 章部署：
网关 Deployment（HPA）+ Redis Cluster + 管理台 Deployment + MySQL 主从。
