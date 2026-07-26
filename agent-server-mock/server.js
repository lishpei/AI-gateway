/**
 * Mock A2A Server Agent（weather-reporter）—— A2A v1.0 零依赖实现。
 *
 * 方法支持：SendMessage（同步 Message/Task）、SendStreamingMessage（SSE）、
 *           GetTask、CancelTask。
 * 上游认证：默认要求 X-Api-Key: mock-upstream-key（验证网关凭证代换注入）。
 * 调试：GET /health、GET /__debug/requests（断言注入头/A2A-Version 透传）。
 */
'use strict';

const http = require('http');

const config = {
  port: parseInt(process.env.PORT || '8091', 10),
  expectedApiKey: process.env.UPSTREAM_API_KEY || 'mock-upstream-key',
  protocolVersion: '1.0',
};

const tasks = new Map();
const debugRequests = [];

// ---------- 工具 ----------
function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => resolve(data));
  });
}

function sendJson(res, status, obj, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  res.end(JSON.stringify(obj));
}

function rpcResult(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function rpcError(id, code, message, data) {
  return { jsonrpc: '2.0', id: id ?? null, error: { code, message, data } };
}

function recordDebug(req, body) {
  let rpc = null;
  try {
    const j = JSON.parse(body || '');
    rpc = { method: j.method, id: j.id };
  } catch { /* ignore */ }
  debugRequests.push({
    ts: new Date().toISOString(),
    method: req.method,
    url: req.url,
    headers: {
      'x-api-key': req.headers['x-api-key'] ? '<present>' : null,
      authorization: req.headers.authorization ? '<present>' : null,
      'a2a-version': req.headers['a2a-version'] || null,
      'a2a-extensions': req.headers['a2a-extensions'] || null,
      'x-request-id': req.headers['x-request-id'] || null,
      'x-api-key-gw': req.headers['x-api-key-gw'] || null,
    },
    rpc,
  });
  if (debugRequests.length > 100) debugRequests.shift();
}

// ---------- 认证（上游 API Key） ----------
function authenticate(req, res, rpcId) {
  if (req.headers['x-api-key'] === config.expectedApiKey) return true;
  sendJson(res, 401, rpcError(rpcId, -32010, 'upstream: invalid api key'));
  return false;
}

// ---------- 任务构造 ----------
function makeTask(contextId) {
  const id = 'task-' + Math.random().toString(36).slice(2, 10);
  const task = {
    id,
    contextId: contextId || 'ctx-' + Math.random().toString(36).slice(2, 8),
    status: { state: 'TASK_STATE_SUBMITTED', timestamp: new Date().toISOString() },
    artifacts: [],
    history: [],
  };
  tasks.set(id, task);
  return task;
}

function messageTextOf(params) {
  try {
    return params.message.parts.map((p) => p.text || '').join(' ');
  } catch {
    return '';
  }
}

// ---------- 方法处理 ----------
function handleSendMessage(params, id) {
  const text = messageTextOf(params);
  // 简单交互：直接回 Message（不建 Task）
  if (!/天气|weather/i.test(text)) {
    return rpcResult(id, {
      message: {
        role: 'ROLE_AGENT',
        parts: [{ text: `weather-reporter 收到: "${text}"（非天气问题，直接应答）` }],
      },
    });
  }
  // 天气问题：返回完成的 Task（同步语义）
  const task = makeTask(params.message.contextId);
  task.status = { state: 'TASK_STATE_COMPLETED', timestamp: new Date().toISOString() };
  task.artifacts = [
    {
      artifactId: 'art-1',
      parts: [{ text: '北京 2026-07-27：晴，26~34°C，东南风2级（mock 数据）' }],
    },
  ];
  return rpcResult(id, { task });
}

function handleGetTask(params, id) {
  const task = tasks.get(params.id);
  if (!task) {
    return rpcError(id, -32001, 'Task not found', [
      { '@type': 'type.googleapis.com/google.rpc.ErrorInfo', reason: 'TASK_NOT_FOUND', domain: 'a2a-protocol.org' },
    ]);
  }
  return rpcResult(id, { task });
}

function handleCancelTask(params, id) {
  const task = tasks.get(params.id);
  if (!task) return rpcError(id, -32001, 'Task not found');
  if (task.status.state.startsWith('TASK_STATE_C')) {
    return rpcError(id, -32002, 'Task not cancelable');
  }
  task.status = { state: 'TASK_STATE_CANCELED', timestamp: new Date().toISOString() };
  return rpcResult(id, { task });
}

/** SendStreamingMessage：SSE 流（Task → statusUpdate ×3 → 完成） */
function handleStreaming(params, id, res) {
  const task = makeTask(params?.message?.contextId);
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  const send = (result) => {
    res.write(`data: ${JSON.stringify({ jsonrpc: '2.0', id, result })}\n\n`);
  };
  // 首事件：Task（WORKING）
  task.status = { state: 'TASK_STATE_WORKING', timestamp: new Date().toISOString() };
  send({ task });
  const updates = [
    { state: 'TASK_STATE_WORKING', text: '正在查询气象数据...' },
    { state: 'TASK_STATE_WORKING', text: '正在生成播报...' },
    { state: 'TASK_STATE_COMPLETED', text: '北京 2026-07-27：晴，26~34°C（mock）' },
  ];
  let i = 0;
  const timer = setInterval(() => {
    if (i < updates.length) {
      const u = updates[i++];
      send({
        statusUpdate: {
          taskId: task.id,
          contextId: task.contextId,
          status: { state: u.state, message: { role: 'ROLE_AGENT', parts: [{ text: u.text }] }, timestamp: new Date().toISOString() },
        },
      });
      if (u.state === 'TASK_STATE_COMPLETED') {
        task.status.state = u.state;
        clearInterval(timer);
        res.end();
      }
    }
  }, 150);
}

// ---------- 主服务 ----------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${config.port}`);

  if (url.pathname === '/health') {
    return sendJson(res, 200, { status: 'ok', agent: 'weather-reporter-mock' });
  }
  if (url.pathname === '/__debug/requests') {
    return sendJson(res, 200, { count: debugRequests.length, requests: [...debugRequests].reverse() });
  }
  if (url.pathname === '/__debug/clear') {
    debugRequests.length = 0;
    return sendJson(res, 200, { cleared: true });
  }

  if (url.pathname !== '/a2a' || req.method !== 'POST') {
    return sendJson(res, 404, { error: 'not found' });
  }

  const body = await readBody(req);
  recordDebug(req, body);

  let msg;
  try {
    msg = JSON.parse(body);
  } catch {
    return sendJson(res, 400, rpcError(null, -32700, 'Parse error'));
  }
  const { id, method, params } = msg;

  // 上游认证（凭证代换验证点）
  if (!authenticate(req, res, id)) return;

  switch (method) {
    case 'SendMessage':
      return sendJson(res, 200, handleSendMessage(params, id));
    case 'SendStreamingMessage':
      return handleStreaming(params, id, res);
    case 'GetTask':
      return sendJson(res, 200, handleGetTask(params || {}, id));
    case 'CancelTask':
      return sendJson(res, 200, handleCancelTask(params || {}, id));
    default:
      return sendJson(res, 200, rpcError(id, -32601, 'Method not found: ' + method));
  }
});

server.listen(config.port, () => {
  console.log(`[agent-server-mock] listening on :${config.port}`);
  console.log(`[agent-server-mock] expects X-Api-Key: ${config.expectedApiKey}`);
});
