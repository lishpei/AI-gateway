/**
 * Mock MCP Server（考勤）—— 零依赖实现（Node 内置 http + crypto）。
 *
 * 传输支持：
 *   1. Streamable HTTP:  POST/GET/DELETE /mcp（MCP-Session-Id 会话管理）
 *   2. 旧版 HTTP+SSE:    GET /sse + POST /messages?session_id=...
 *
 * 安全：
 *   - RS256 JWT 验签（JWKS 从 IdP Mock 拉取，Node crypto 验证）
 *   - aud 必须等于配置的 resourceUri（防横向复用）
 *   - 数据级权限：仅返回与调用者身份匹配的数据
 *
 * 调试：
 *   - GET /health            健康检查
 *   - GET /__debug/requests  最近请求记录（E2E 断言用）
 */
'use strict';

const http = require('http');
const crypto = require('crypto');

const config = {
  port: parseInt(process.env.PORT || '8090', 10),
  resourceUri: process.env.RESOURCE_URI || 'http://localhost:8090/mcp',
  jwksUrl: process.env.JWKS_URL || 'http://localhost:8080/idp-mock/.well-known/jwks.json',
  protocolVersion: '2025-06-18',
  serverInfo: { name: 'attendance-mcp-mock', version: '1.0.0' },
};

// ---------- 模拟数据 ----------
const ATTENDANCE = {
  'alice@corp.com': { month: '2026-07', workdays: 22, leave: 1, late: 0, overtime: 3 },
  'bob@corp.com': { month: '2026-07', workdays: 21, leave: 0, late: 2, overtime: 0 },
};

const TOOLS = [
  {
    name: 'attendance.query',
    description: '查询员工考勤（仅本人）',
    inputSchema: {
      type: 'object',
      properties: {
        employee_id: { type: 'string', description: '员工邮箱' },
        month: { type: 'string', pattern: '^\\d{4}-\\d{2}$' },
      },
      required: ['employee_id'],
    },
  },
  {
    name: 'attendance.stream',
    description: '流式推送考勤统计进度（SSE 演示）',
    inputSchema: { type: 'object', properties: { month: { type: 'string' } } },
  },
];

// ---------- 会话与调试状态 ----------
const sessions = new Map();        // streamable: sessionId -> { createdAt }
const legacySessions = new Map();  // legacy: sessionId -> { res, createdAt }
const debugRequests = [];          // 最近 100 条请求记录

// ---------- JWKS 缓存 ----------
let jwksCache = null;
let jwksFetchedAt = 0;

async function getJwks() {
  if (jwksCache && Date.now() - jwksFetchedAt < 300_000) return jwksCache;
  const res = await fetch(config.jwksUrl);
  if (!res.ok) throw new Error('jwks fetch failed: ' + res.status);
  jwksCache = await res.json();
  jwksFetchedAt = Date.now();
  return jwksCache;
}

function b64urlToBuffer(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  return Buffer.from(s, 'base64');
}

/** RS256 验签 + exp 校验。返回 claims 或 null。 */
async function verifyJwt(token, requiredKid) {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const header = JSON.parse(b64urlToBuffer(parts[0]).toString('utf8'));
    const jwks = await getJwks();
    const jwk = jwks.keys.find((k) => k.kid === header.kid) || jwks.keys[0];
    const key = crypto.createPublicKey({ key: jwk, format: 'jwk' });
    const ok = crypto.verify(
      'sha256',
      Buffer.from(parts[0] + '.' + parts[1]),
      { key, padding: crypto.constants.RSA_PKCS1_PADDING },
      b64urlToBuffer(parts[2])
    );
    if (!ok) return null;
    const claims = JSON.parse(b64urlToBuffer(parts[1]).toString('utf8'));
    if (claims.exp && claims.exp * 1000 < Date.now()) return null;
    return claims;
  } catch (e) {
    return null;
  }
}

// ---------- 工具函数 ----------
function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => resolve(data));
  });
}

function sendJson(res, status, obj, headers = {}) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  res.end(body);
}

function rpcResult(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function rpcError(id, code, message) {
  return { jsonrpc: '2.0', id: id ?? null, error: { code, message } };
}

function newSessionId() {
  return crypto.randomUUID().replace(/-/g, '').slice(0, 24);
}

function recordDebug(req, claims, body) {
  debugRequests.push({
    ts: new Date().toISOString(),
    method: req.method,
    url: req.url,
    headers: {
      authorization: req.headers.authorization ? 'Bearer <present>' : null,
      'x-user-id': req.headers['x-user-id'] || null,
      'x-user-email': req.headers['x-user-email'] || null,
      'x-data-scope': req.headers['x-data-scope'] || null,
      'x-agent-chain': req.headers['x-agent-chain'] || null,
      'mcp-session-id': req.headers['mcp-session-id'] || null,
      'x-request-id': req.headers['x-request-id'] || null,
    },
    token: claims
      ? { sub: claims.sub, aud: claims.aud, scope: claims.scope, act: claims.act || null, client_id: claims.client_id }
      : null,
    rpc: body ? safeParseRpc(body) : null,
  });
  if (debugRequests.length > 100) debugRequests.shift();
}

function safeParseRpc(body) {
  try {
    const j = JSON.parse(body);
    return { method: j.method, id: j.id, tool: j.params?.name || null };
  } catch {
    return null;
  }
}

/** 数据级权限：返回调用者可用的邮箱（网关注入的 X-User-Email 优先，其次 token sub 若形如邮箱） */
function callerEmail(req, claims) {
  const h = req.headers['x-user-email'];
  if (h) return h;
  if (claims?.sub && claims.sub.includes('@')) return claims.sub;
  // mock IdP 的 sub 是 userId，映射邮箱
  const map = { alice: 'alice@corp.com', bob: 'bob@corp.com' };
  return claims?.sub ? map[claims.sub] || null : null;
}

// ---------- 认证中间件 ----------
async function authenticate(req, res) {
  const auth = req.headers.authorization || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
  if (!token) {
    sendJson(res, 401, { error: 'missing bearer token' });
    return null;
  }
  const claims = await verifyJwt(token);
  if (!claims) {
    sendJson(res, 401, { error: 'invalid token' });
    return null;
  }
  // aud 校验：必须包含本 Server 的 resourceUri（防横向复用）
  const aud = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (!aud.includes(config.resourceUri)) {
    sendJson(res, 403, { error: 'token audience mismatch', expected: config.resourceUri, got: aud });
    return null;
  }
  return claims;
}

// ---------- tools/call 处理 ----------
function handleToolCall(req, claims, params) {
  const name = params?.name;
  const args = params?.arguments || {};
  const email = callerEmail(req, claims);

  if (name === 'attendance.query') {
    if (!args.employee_id) {
      return { isError: true, content: [{ type: 'text', text: 'missing argument: employee_id' }] };
    }
    // 数据级权限：仅本人
    if (args.employee_id !== email) {
      return {
        isError: true,
        content: [{ type: 'text', text: `permission denied: 仅可查询本人考勤 (caller=${email})` }],
      };
    }
    const record = ATTENDANCE[args.employee_id];
    if (!record) {
      return { isError: true, content: [{ type: 'text', text: 'no attendance data' }] };
    }
    const month = args.month || record.month;
    return {
      content: [
        {
          type: 'text',
          text: `${args.employee_id} ${month} 考勤: 出勤${record.workdays}天, 请假${record.leave}天, 迟到${record.late}次, 加班${record.overtime}小时`,
        },
      ],
      structuredContent: { employee: args.employee_id, month, ...record },
    };
  }
  return { isError: true, content: [{ type: 'text', text: 'unknown tool: ' + name }] };
}

// ---------- Streamable HTTP: /mcp ----------
async function handleMcpPost(req, res, claims, body) {
  let msg;
  try {
    msg = JSON.parse(body);
  } catch {
    return sendJson(res, 400, rpcError(null, -32700, 'Parse error'));
  }
  const { id, method, params } = msg;

  // 通知/响应：202 无 body
  if (id === undefined || id === null) {
    res.writeHead(202);
    return res.end();
  }

  // 会话校验（initialize 之外的请求需要合法 session）
  if (method !== 'initialize') {
    const sid = req.headers['mcp-session-id'];
    if (sid && !sessions.has(sid)) {
      return sendJson(res, 404, rpcError(id, -32001, 'session not found'));
    }
  }

  switch (method) {
    case 'initialize': {
      const sid = newSessionId();
      sessions.set(sid, { createdAt: Date.now() });
      return sendJson(
        res,
        200,
        rpcResult(id, {
          protocolVersion: params?.protocolVersion || config.protocolVersion,
          capabilities: { tools: { listChanged: false } },
          serverInfo: config.serverInfo,
        }),
        { 'MCP-Session-Id': sid }
      );
    }
    case 'ping':
      return sendJson(res, 200, rpcResult(id, {}));
    case 'tools/list':
      return sendJson(res, 200, rpcResult(id, { tools: TOOLS }));
    case 'tools/call': {
      if (params?.name === 'attendance.stream') {
        return streamAttendance(res, id, params.arguments || {});
      }
      return sendJson(res, 200, rpcResult(id, handleToolCall(req, claims, params)));
    }
    default:
      return sendJson(res, 200, rpcError(id, -32601, 'Method not found: ' + method));
  }
}

/** attendance.stream：SSE 流式响应（引导事件 + 进度通知 + 最终响应） */
function streamAttendance(res, id, args) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  let seq = 0;
  const sendEvent = (data) => {
    res.write(`id: evt-${++seq}\n`);
    res.write(`data: ${JSON.stringify(data)}\n\n`);
  };
  // 引导事件（事件 ID + 空 data）
  res.write(`id: evt-0\ndata: \n\n`);
  const month = args.month || '2026-07';
  const steps = ['统计出勤天数...', '核对请假记录...', '汇总加班时长...'];
  let i = 0;
  const timer = setInterval(() => {
    if (i < steps.length) {
      sendEvent(rpcResult(null, { _meta: { progress: `${i + 1}/${steps.length}` }, msg: steps[i] }));
      // 注意：演示用，进度以 notification 形式推送
      i++;
    } else {
      clearInterval(timer);
      sendEvent(
        rpcResult(id, {
          content: [{ type: 'text', text: `${month} 考勤统计完成（流式）` }],
        })
      );
      res.end();
    }
  }, 150);
}

// ---------- Legacy: /sse + /messages ----------
function handleLegacySse(req, res, claims) {
  const sid = newSessionId();
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  legacySessions.set(sid, { res, createdAt: Date.now() });
  // 首事件：endpoint（相对路径，经网关时由网关改写）
  res.write(`event: endpoint\ndata: /messages?session_id=${sid}\n\n`);
  req.on('close', () => legacySessions.delete(sid));
}

async function handleLegacyMessage(req, res, claims, body, sessionId) {
  const session = legacySessions.get(sessionId);
  if (!session) {
    return sendJson(res, 404, { error: 'session not found' });
  }
  let msg;
  try {
    msg = JSON.parse(body);
  } catch {
    return sendJson(res, 400, rpcError(null, -32700, 'Parse error'));
  }
  // 202 先行
  res.writeHead(202);
  res.end();

  const { id, method, params } = msg;
  if (id === undefined || id === null) return; // 通知无响应

  let reply;
  switch (method) {
    case 'initialize':
      reply = rpcResult(id, {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: config.serverInfo,
      });
      break;
    case 'tools/list':
      reply = rpcResult(id, { tools: TOOLS });
      break;
    case 'tools/call':
      reply = rpcResult(id, handleToolCall(req, claims, params));
      break;
    case 'ping':
      reply = rpcResult(id, {});
      break;
    default:
      reply = rpcError(id, -32601, 'Method not found: ' + method);
  }
  // 经 SSE 流下行
  try {
    session.res.write(`event: message\ndata: ${JSON.stringify(reply)}\n\n`);
  } catch {
    /* 客户端已断开 */
  }
}

// ---------- 主服务 ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${config.port}`);
  const path = url.pathname;

  // 无认证端点
  if (path === '/health') {
    return sendJson(res, 200, { status: 'ok', server: config.serverInfo });
  }
  if (path === '/__debug/requests') {
    return sendJson(res, 200, { count: debugRequests.length, requests: [...debugRequests].reverse() });
  }
  if (path === '/__debug/clear') {
    debugRequests.length = 0;
    return sendJson(res, 200, { cleared: true });
  }

  // 认证
  const claims = await authenticate(req, res);
  if (!claims) return;

  const body = req.method === 'POST' ? await readBody(req) : null;
  recordDebug(req, claims, body);

  // Streamable HTTP
  if (path === '/mcp') {
    if (req.method === 'POST') return handleMcpPost(req, res, claims, body);
    if (req.method === 'GET') {
      // 独立 SSE 流（演示：立即建立，定期心跳注释）
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      res.write(`id: evt-get-0\ndata: \n\n`);
      const hb = setInterval(() => res.write(`: heartbeat\n\n`), 3000);
      req.on('close', () => clearInterval(hb));
      return;
    }
    if (req.method === 'DELETE') {
      const sid = req.headers['mcp-session-id'];
      if (sid) sessions.delete(sid);
      res.writeHead(200);
      return res.end();
    }
    res.writeHead(405);
    return res.end();
  }

  // Legacy
  if (path === '/sse' && req.method === 'GET') {
    return handleLegacySse(req, res, claims);
  }
  if (path === '/messages' && req.method === 'POST') {
    return handleLegacyMessage(req, res, claims, body, url.searchParams.get('session_id'));
  }

  sendJson(res, 404, { error: 'not found: ' + path });
});

server.listen(config.port, () => {
  console.log(`[mcp-server-mock] listening on :${config.port}`);
  console.log(`[mcp-server-mock] resourceUri=${config.resourceUri}`);
  console.log(`[mcp-server-mock] jwks=${config.jwksUrl}`);
  getJwks()
    .then(() => console.log('[mcp-server-mock] JWKS loaded'))
    .catch((e) => console.warn('[mcp-server-mock] JWKS preload failed (will retry on demand):', e.message));
});
