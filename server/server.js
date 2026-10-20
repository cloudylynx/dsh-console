#!/usr/bin/env node
/**
 * ============================================================================
 * DSH 控制台 · 统一后端服务
 * ============================================================================
 * 把原先分散的多个服务合并成一个进程、一个端口：
 *
 *   /api/status   系统状态（CPU/内存/磁盘/守护进程）      ← 状态服务
 *   /api/tasks    任务列表 / 上报                       ← 状态服务
 *   /api/messages 消息收发                              ← 状态服务
 *   /api/files    文件浏览 / 上传 / 下载 / 删除          ← 文件服务
 *   /api/logs     编译日志（轮询）                      ← 日志服务
 *   /api/logs/stream  编译日志（SSE 实时）              ← 日志服务
 *   /api/daemons  守护进程状态
 *   /api/diagnose 一键诊断报告
 *
 * 终端（ttyd）不在这里：ttyd 本身是 WebSocket 服务，需要独立进程和
 * 独立的端口。App 通过设置里的「终端地址」指向它。
 *
 * ★配置
 *   环境变量：
 *     DSH_CONSOLE_PORT   监听端口（默认 8080）
 *     DSH_CONSOLE_HOST   监听地址（默认 0.0.0.0）
 *     DSH_CONSOLE_ROOTS  文件白名单，逗号分隔的 "名称:路径[:ro]" 形式
 *     DSH_CONSOLE_LOG    编译日志文件路径
 *     DSH_CONSOLE_TOKEN  可选：启用后所有 /api 需带 X-Auth-Token
 *
 * ★安全
 *   · 文件访问受根目录白名单约束，防目录穿越
 *   · 删除需显式 confirm 参数
 *   · 可选 Token 认证
 *   · 默认只监听局域网可路由地址上的 HTTP，部署时请自行加防火墙
 * ============================================================================
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync, spawn } = require('child_process');

// ────────────────────────── 配置 ──────────────────────────

const PORT = parseInt(process.env.DSH_CONSOLE_PORT || '8080', 10);
const HOST = process.env.DSH_CONSOLE_HOST || '0.0.0.0';
const TOKEN = process.env.DSH_CONSOLE_TOKEN || '';
const LOG_FILE = process.env.DSH_CONSOLE_LOG || path.join(__dirname, 'build.log');
const TASK_FILE = process.env.DSH_CONSOLE_TASKS || path.join(__dirname, 'tasks.json');
const MSG_FILE = process.env.DSH_CONSOLE_MESSAGES || path.join(__dirname, 'messages.json');

/** 文件白名单：名称:路径[:ro] */
function parseRoots(spec) {
  const defs = (spec && spec.trim())
    ? spec.split(',').map(s => s.trim()).filter(Boolean)
    : [
        // 默认给几个通用目录，使用者可自行改环境变量
        'Home:/home/USER:rw',
        'Tmp:/tmp:rw',
      ];
  return defs.map(d => {
    // 格式: 名称:路径[:rw|ro]
    // 路径本身可能含冒号，所以从右往左拆 mode
    const raw = d.split(':');
    let writable = true;
    let rest = raw;
    if (raw.length >= 3) {
      const last = raw[raw.length - 1].toLowerCase();
      if (last === 'rw' || last === 'ro') {
        writable = last !== 'ro';
        rest = raw.slice(0, -1);
      }
    }
    const name = rest[0] || path.basename(rest[1] || 'root');
    const p = (rest[1] || rest[0] || '.').replace(/^~(?=\/|$)/, os.homedir());
    return { name, path: p, writable };
  }).filter(r => {
    try { return fs.existsSync(r.path); } catch (e) { return false; }
  });
}

const ROOTS = parseRoots(process.env.DSH_CONSOLE_ROOTS);

function log(msg) {
  console.log(`[${new Date().toISOString()}] ${msg}`);
}

// ────────────────────────── 工具 ──────────────────────────

function resolveSafe(rootPath, relPath) {
  const root = path.resolve(rootPath);
  const target = path.resolve(root, '.' + path.sep + (relPath || ''));
  if (target !== root && !target.startsWith(root + path.sep)) return null;
  return target;
}

function fmtSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
  return (bytes / 1073741824).toFixed(2) + ' GB';
}

function fmtTime(ms) {
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
       + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function send(res, status, payload, extraHeaders) {
  const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
  res.writeHead(status, Object.assign({
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
  }, extraHeaders || {}));
  res.end(body);
}

function readJson(file, fallback) {
  try {
    const raw = fs.readFileSync(file, 'utf8');
    const v = JSON.parse(raw);
    return v && typeof v === 'object' ? v : fallback;
  } catch (e) {
    return fallback;
  }
}

function writeJson(file, value) {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(value, null, 2), 'utf8');
    return true;
  } catch (e) {
    log(`write failed ${file}: ${e.message}`);
    return false;
  }
}

// ────────────────────────── 系统状态 ──────────────────────────

function readMemInfo() {
  try {
    const raw = fs.readFileSync('/proc/meminfo', 'utf8');
    const get = k => {
      const m = raw.match(new RegExp(k + ':\\s+(\\d+)'));
      return m ? parseInt(m[1], 10) : 0;
    };
    const total = get('MemTotal');
    const avail = get('MemAvailable');
    const swapTotal = get('SwapTotal');
    const swapFree = get('SwapFree');
    return {
      totalMB: Math.round(total / 1024),
      usedMB: Math.round((total - avail) / 1024),
      percent: total ? Math.round(((total - avail) / total) * 100) : 0,
      swapTotalMB: Math.round(swapTotal / 1024),
      swapFreeMB: Math.round(swapFree / 1024),
    };
  } catch (e) {
    return { totalMB: 0, usedMB: 0, percent: 0, swapTotalMB: 0, swapFreeMB: 0 };
  }
}

function shell(cmd, timeout = 5000) {
  try {
    return execSync(cmd, { encoding: 'utf8', timeout }).trim();
  } catch (e) {
    return '';
  }
}

function readDisk(dir) {
  const out = shell(`df -h ${JSON.stringify(dir)} 2>/dev/null | tail -1`);
  if (!out) return { percent: 0, free: '', total: '' };
  const parts = out.split(/\s+/);
  return {
    total: parts[1] || '',
    used: parts[2] || '',
    free: parts[3] || '',
    percent: parseInt((parts[4] || '0').replace('%', ''), 10) || 0,
  };
}

function readCpuTemp() {
  for (const f of ['/sys/class/thermal/thermal_zone0/temp',
                   '/sys/class/hwmon/hwmon0/temp']) {
    try {
      const v = parseInt(fs.readFileSync(f, 'utf8').trim(), 10);
      if (v > 1000) return Math.round(v / 1000);
      if (v > 0) return Math.round(v);
    } catch (e) {}
  }
  return 0;
}

/** 守护进程列表：可由环境变量 DSH_CONSOLE_DAEMONS 指定 "名称:单元名" */
const DAEMON_DEFS = (process.env.DSH_CONSOLE_DAEMONS || '')
  .split(',').map(s => s.trim()).filter(Boolean)
  .map(d => {
    const [name, unit] = d.split(':');
    return { name: name || unit, unit: unit || name };
  });

function readDaemons() {
  if (!DAEMON_DEFS.length) return [];
  return DAEMON_DEFS.map(d => {
    const active = shell(`systemctl is-active ${d.unit} 2>/dev/null`);
    const enabled = shell(`systemctl is-enabled ${d.unit} 2>/dev/null`);
    return {
      name: d.name,
      unit: d.unit,
      active: active || 'unknown',
      enabled: enabled || 'unknown',
      healthy: active === 'active',
    };
  });
}

function buildStatus() {
  const mem = readMemInfo();
  const disk = readDisk(process.env.DSH_CONSOLE_DISK_DIR || os.homedir());
  const load = os.loadavg();
  return {
    ts: Date.now(),
    host: os.hostname(),
    platform: `${os.type()} ${os.release()}`,
    arch: os.arch(),
    uptime: Math.floor(os.uptime()),
    cpu: {
      load1: load[0].toFixed(2),
      load5: load[1].toFixed(2),
      load15: load[2].toFixed(2),
      cores: os.cpus().length,
    },
    mem,
    disk,
    temp: readCpuTemp(),
    daemons: readDaemons(),
  };
}

// ────────────────────────── 任务 ──────────────────────────

function loadTasks() {
  const data = readJson(TASK_FILE, { tasks: [] });
  if (!Array.isArray(data.tasks)) data.tasks = [];
  // 5 分钟未更新的 running 任务视为过期
  const now = Date.now();
  data.tasks = data.tasks.filter(t => {
    if (t.state !== 'running') return true;
    return now - (t.updatedAt || t.ts || now) < 5 * 60 * 1000;
  });
  return data.tasks;
}

// ────────────────────────── 消息 ──────────────────────────

function loadMessages() {
  const data = readJson(MSG_FILE, { messages: [] });
  if (!Array.isArray(data.messages)) data.messages = [];
  return data.messages;
}

// ────────────────────────── 日志 ──────────────────────────

function readLogTail(maxBytes) {
  try {
    const st = fs.statSync(LOG_FILE);
    if (!st.isFile() || st.size === 0) return '';
    const len = Math.min(maxBytes, st.size);
    const fd = fs.openSync(LOG_FILE, 'r');
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, Math.max(0, st.size - len));
    fs.closeSync(fd);
    return buf.toString('utf8');
  } catch (e) {
    return '';
  }
}

function logStatus() {
  try {
    const st = fs.statSync(LOG_FILE);
    return {
      size: fmtSize(st.size),
      bytes: st.size,
      mtime: fmtTime(st.mtimeMs),
      ageSec: Math.floor((Date.now() - st.mtimeMs) / 1000),
    };
  } catch (e) {
    return { size: '(无日志)', bytes: 0, mtime: '', ageSec: -1 };
  }
}

// ────────────────────────── 诊断 ──────────────────────────

function buildDiagnose() {
  const status = buildStatus();
  const sections = [];
  const problems = [];

  const daemonItems = status.daemons.map(d => ({
    label: d.name,
    ok: d.healthy,
    text: `${d.active}${d.enabled === 'enabled' ? ' / 开机自启' : ''}`,
  }));
  if (daemonItems.length) sections.push({ title: '守护进程', items: daemonItems });

  sections.push({
    title: '系统资源',
    items: [
      { label: 'CPU 负载', ok: parseFloat(status.cpu.load1) < status.cpu.cores,
        text: `${status.cpu.load1} / ${status.cpu.cores} 核` },
      { label: '内存', ok: status.mem.percent < 90,
        text: `${status.mem.usedMB}/${status.mem.totalMB}MB (${status.mem.percent}%)` },
      { label: '磁盘', ok: status.disk.percent < 90,
        text: `${status.disk.percent}% 剩余 ${status.disk.free}` },
      { label: '温度', ok: !status.temp || status.temp < 80, text: `${status.temp}°C` },
      { label: 'SWAP', ok: true,
        text: `${status.mem.swapFreeMB}/${status.mem.swapTotalMB}MB 空闲` },
    ],
  });

  sections.push({
    title: '网络',
    items: [
      { label: '网卡地址', ok: true,
        text: Object.values(os.networkInterfaces()).flat()
          .filter(i => i.family === 'IPv4' && !i.internal)
          .map(i => i.address).join(', ') || '(无)' },
      { label: '主机名', ok: true, text: status.host },
    ],
  });

  for (const s of sections) {
    for (const it of s.items) if (!it.ok) problems.push(`${s.title} / ${it.label}`);
  }

  return { ts: Date.now(), problems, sections };
}

// ────────────────────────── HTTP 服务 ──────────────────────────

const server = http.createServer(async (req, res) => {
  const parsed = new URL(req.url || '/', 'http://localhost');
  let p = parsed.pathname.replace(/\/+$/, '') || '/';
  const q = parsed.searchParams;
  const method = (req.method || 'GET').toUpperCase();

  // CORS 预检
  if (method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type,X-Auth-Token',
    });
    return res.end();
  }

  // Token 认证（启用后）
  if (TOKEN) {
    const provided = req.headers['x-auth-token']
      || q.get('token') || '';
    if (provided !== TOKEN) {
      return send(res, 401, { error: 'unauthorized' });
    }
  }

  // ══════════════════════════════════════════════════════
  //  兼容别名（旧版路径 → 新版 API），方便平滑迁移
  // ══════════════════════════════════════════════════════
  const ALIASES = {
    '/progress':         { path: '/api/status' },
    '/messages':         { path: '/api/messages' },
    '/message':          { path: '/api/message' },
    '/message/read':     { path: '/api/message/read' },
    '/message/read-all': { path: '/api/message/read-all' },
    '/task':             { path: '/api/task' },
    '/task/done':        { path: '/api/task/done' },
    '/tasks':            { path: '/api/tasks' },
    '/daemons':          { path: '/api/daemons' },
    '/diagnose':         { path: '/api/diagnose' },
    '/roots':            { path: '/api/files/roots' },
    '/list':             { path: '/api/files/list' },
    '/download':         { path: '/api/files/download' },
    '/delete':           { path: '/api/files/delete' },
    '/upload':           { path: '/api/files/upload' },
    '/log':              { path: '/api/logs' },
    '/status':           { path: '/api/status' },
  };
  if (ALIASES[p]) p = ALIASES[p].path;

  // ── 状态 ──
  if (p === '/api/status') {
    return send(res, 200, buildStatus());
  }

  if (p === '/api/daemons') {
    return send(res, 200, { daemons: readDaemons() });
  }

  if (p === '/api/diagnose') {
    return send(res, 200, buildDiagnose());
  }

  // ── 任务 ──
  if (p === '/api/tasks' && method === 'GET') {
    return send(res, 200, { tasks: loadTasks() });
  }

  if (p === '/api/task' && method === 'POST') {
    const body = await readBody(req);
    let data;
    try { data = JSON.parse(body || '{}'); } catch (e) { data = {}; }
    const tasks = loadTasks();
    const id = data.id || ('task-' + Date.now());
    const now = Date.now();
    const existing = tasks.find(t => t.id === id);
    if (existing) {
      Object.assign(existing, data, { updatedAt: now });
    } else {
      tasks.push(Object.assign({
        id, name: '未命名任务', detail: '', state: 'running',
        progress: 0, source: 'api', ts: now, updatedAt: now,
      }, data, { updatedAt: now }));
    }
    writeJson(TASK_FILE, { tasks });
    return send(res, 200, { ok: true, id });
  }

  if (p === '/api/task/done' && method === 'POST') {
    const body = await readBody(req);
    let data;
    try { data = JSON.parse(body || '{}'); } catch (e) { data = {}; }
    let tasks = loadTasks();
    if (data.all) {
      tasks = [];
    } else {
      tasks = tasks.map(t => (t.id === data.id
        ? Object.assign({}, t, { state: 'done', updatedAt: Date.now() })
        : t));
    }
    writeJson(TASK_FILE, { tasks });
    return send(res, 200, { ok: true, count: tasks.length });
  }

  // ── 消息 ──
  if (p === '/api/messages' && method === 'GET') {
    return send(res, 200, { messages: loadMessages() });
  }

  if (p === '/api/message' && method === 'POST') {
    const body = await readBody(req);
    let data;
    try { data = JSON.parse(body || '{}'); } catch (e) { data = {}; }
    const messages = loadMessages();
    const msg = Object.assign({
      id: 'msg-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      title: '', body: '', from: 'server', ts: Date.now(), read: false,
    }, data);
    messages.unshift(msg);
    writeJson(MSG_FILE, { messages: messages.slice(0, 500) });
    return send(res, 200, { ok: true, id: msg.id });
  }

  if (p === '/api/message/read' && method === 'POST') {
    const body = await readBody(req);
    let data;
    try { data = JSON.parse(body || '{}'); } catch (e) { data = {}; }
    const messages = loadMessages();
    let n = 0;
    for (const m of messages) {
      if (!m.read && (!data.id || m.id === data.id)) { m.read = true; n++; }
    }
    writeJson(MSG_FILE, { messages });
    return send(res, 200, { ok: true, count: n });
  }

  if (p === '/api/message/read-all' && method === 'POST') {
    const messages = loadMessages();
    let n = 0;
    for (const m of messages) if (!m.read) { m.read = true; n++; }
    writeJson(MSG_FILE, { messages });
    return send(res, 200, { ok: true, count: n });
  }

  // ── 文件 ──
  if (p === '/api/files/roots' && method === 'GET') {
    return send(res, 200, {
      roots: ROOTS.map((r, i) => ({ index: i, name: r.name, writable: r.writable })),
    });
  }

  if (p === '/api/files/list' && method === 'GET') {
    const idx = parseInt(q.get('root') || '0', 10);
    const root = ROOTS[idx];
    if (!root) return send(res, 400, { error: 'invalid root' });
    const dir = resolveSafe(root.path, q.get('path') || '');
    if (!dir) return send(res, 403, { error: 'path traversal blocked' });
    let entries = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true })
        .map(e => {
          const full = path.join(dir, e.name);
          let size = 0, mtime = 0;
          try {
            const st = fs.statSync(full);
            size = st.size; mtime = st.mtimeMs;
          } catch (e2) {}
          return {
            name: e.name,
            isDir: e.isDirectory(),
            size,
            sizeText: e.isDirectory() ? '' : fmtSize(size),
            mtime,
            mtimeText: mtime ? fmtTime(mtime) : '',
          };
        })
        .sort((a, b) => (a.isDir === b.isDir)
          ? a.name.localeCompare(b.name)
          : (a.isDir ? -1 : 1));
    } catch (e) {
      return send(res, 500, { error: e.message });
    }
    return send(res, 200, {
      root: { index: idx, name: root.name, writable: root.writable },
      path: q.get('path') || '',
      entries,
    });
  }

  if (p === '/api/files/download' && method === 'GET') {
    const idx = parseInt(q.get('root') || '0', 10);
    const root = ROOTS[idx];
    if (!root) return send(res, 400, { error: 'invalid root' });
    const file = resolveSafe(root.path, q.get('path') || '');
    if (!file) return send(res, 403, { error: 'path traversal blocked' });
    let st;
    try { st = fs.statSync(file); } catch (e) {
      return send(res, 404, { error: 'not found' });
    }
    if (!st.isFile()) return send(res, 400, { error: 'not a file' });
    res.writeHead(200, {
      'Content-Type': 'application/octet-stream',
      'Content-Length': st.size,
      'Content-Disposition': `attachment; filename="${encodeURIComponent(path.basename(file))}"`,
      'Access-Control-Allow-Origin': '*',
    });
    return fs.createReadStream(file).pipe(res);
  }

  if (p === '/api/files/upload' && method === 'POST') {
    const idx = parseInt(q.get('root') || '0', 10);
    const root = ROOTS[idx];
    if (!root) return send(res, 400, { error: 'invalid root' });
    if (!root.writable) return send(res, 403, { error: 'root not writable' });
    const dir = resolveSafe(root.path, q.get('path') || '');
    if (!dir) return send(res, 403, { error: 'path traversal blocked' });
    const name = path.basename(q.get('name') || '');
    if (!name) return send(res, 400, { error: 'name required' });
    const target = path.join(dir, name);
    const MAX = 2 * 1024 * 1024 * 1024;
    let received = 0;
    const ws = fs.createWriteStream(target);
    req.on('data', c => {
      received += c.length;
      if (received > MAX) { req.destroy(); ws.close(); }
    });
    req.pipe(ws);
    ws.on('finish', () => {
      send(res, 200, { ok: true, name, size: received, sizeText: fmtSize(received) });
    });
    ws.on('error', e => send(res, 500, { error: e.message }));
    return;
  }

  if (p === '/api/files/delete' && method === 'POST') {
    if (q.get('confirm') !== 'YES') {
      return send(res, 400, { error: 'confirm=YES required' });
    }
    const idx = parseInt(q.get('root') || '0', 10);
    const root = ROOTS[idx];
    if (!root) return send(res, 400, { error: 'invalid root' });
    if (!root.writable) return send(res, 403, { error: 'root not writable' });
    const target = resolveSafe(root.path, q.get('path') || '');
    if (!target) return send(res, 403, { error: 'path traversal blocked' });
    if (target === path.resolve(root.path)) {
      return send(res, 403, { error: 'cannot delete root' });
    }
    try {
      const st = fs.statSync(target);
      if (st.isDirectory()) fs.rmSync(target, { recursive: true });
      else fs.unlinkSync(target);
      log(`deleted ${target}`);
      return send(res, 200, { ok: true });
    } catch (e) {
      return send(res, 500, { error: e.message });
    }
  }

  // ── 日志 ──
  if (p === '/api/logs' && method === 'GET') {
    const max = parseInt(q.get('max') || '400000', 10);
    return send(res, 200, {
      status: logStatus(),
      content: readLogTail(max),
    });
  }

  if (p === '/api/logs/stream') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
      'X-Accel-Buffering': 'no',
    });
    const push = obj => {
      try { res.write('data: ' + JSON.stringify(obj) + '\n\n'); } catch (e) {}
    };
    push({ type: 'init', content: readLogTail(400000) });
    push({ type: 'status', data: logStatus() });

    let lastSize = 0;
    try { lastSize = fs.statSync(LOG_FILE).size; } catch (e) {}

    const timer = setInterval(() => {
      try {
        const st = fs.statSync(LOG_FILE);
        if (st.size > lastSize) {
          const fd = fs.openSync(LOG_FILE, 'r');
          const len = st.size - lastSize;
          const buf = Buffer.alloc(len);
          fs.readSync(fd, buf, 0, len, lastSize);
          fs.closeSync(fd);
          lastSize = st.size;
          push({ type: 'append', content: buf.toString('utf8') });
        } else if (st.size < lastSize) {
          lastSize = st.size;
          push({ type: 'init', content: readLogTail(400000) });
        }
        push({ type: 'status', data: logStatus() });
      } catch (e) {}
    }, 1000);

    req.on('close', () => clearInterval(timer));
    return;
  }

  // ── 根：简单索引页 ──
  if (p === '/') {
    const html = `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 控制台 · 后端服务</title><style>
body{font:14px/1.7 system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 20px;color:#333}
h1{font-size:20px;border-bottom:2px solid #7AA2F7;padding-bottom:10px}
code{background:#f4f4f8;padding:2px 6px;border-radius:3px}
li{margin:4px 0}
</style></head><body>
<h1>DSH 控制台 · 后端服务</h1>
<p>服务运行中。API 清单：</p>
<ul>
<li><code>GET /api/status</code> — 系统状态</li>
<li><code>GET /api/daemons</code> — 守护进程</li>
<li><code>GET /api/diagnose</code> — 一键诊断</li>
<li><code>GET /api/tasks</code> — 任务列表</li>
<li><code>POST /api/task</code> — 上报任务</li>
<li><code>POST /api/task/done</code> — 结束任务</li>
<li><code>GET /api/messages</code> — 消息列表</li>
<li><code>POST /api/message</code> — 发送消息</li>
<li><code>POST /api/message/read-all</code> — 全部已读</li>
<li><code>GET /api/files/roots</code> — 文件根目录</li>
<li><code>GET /api/files/list?root=0&amp;path=/</code> — 列目录</li>
<li><code>GET /api/files/download?root=0&amp;path=/x</code> — 下载</li>
<li><code>POST /api/files/upload?root=0&amp;path=/&amp;name=f</code> — 上传</li>
<li><code>POST /api/files/delete?root=0&amp;path=/x&amp;confirm=YES</code> — 删除</li>
<li><code>GET /api/logs</code> — 编译日志</li>
<li><code>GET /api/logs/stream</code> — 日志 SSE 流</li>
</ul>
</body></html>`;
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(html);
  }

  return send(res, 404, { error: 'not found' });
});

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', c => {
      data += c;
      if (data.length > 10 * 1024 * 1024) req.destroy();
    });
    req.on('end', () => resolve(data));
    req.on('error', () => resolve(data));
  });
}

server.listen(PORT, HOST, () => {
  log(`DSH console backend listening on http://${HOST}:${PORT}`);
  log(`file roots: ${ROOTS.map(r => `${r.name}(${r.path}${r.writable ? ',rw' : ',ro'})`).join(', ') || '(none)'}`);
  log(`log file: ${LOG_FILE}`);
  log(`token auth: ${TOKEN ? 'enabled' : 'disabled'}`);
});
