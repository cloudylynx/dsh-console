#!/usr/bin/env node
/**
 * ============================================================================
 * DSH 控制台 · 统一后端服务
 * ============================================================================
 *
 * 一个进程、一个端口，同时提供三套原先分散的接口：
 *
 *   ① 状态 / 任务 / 消息 / 诊断   →  /progress  /tasks  /messages  /diagnose
 *   ② 文件浏览 / 上传 / 下载 / 删除 →  /roots  /list  /upload  /download  /delete
 *   ③ 编译日志（HTML + SSE 实时）  →  /api/logs  /api/logs/stream
 *
 * ★接口契约说明
 *   /progress 返回的是 Android 客户端直接消费的「富格式」：
 *     {
 *       ts, tasks[], system{}, alerts[], messages[], daemons[]
 *     }
 *   system 里每个字段（cpu/mem/disk/netRxSpeed/diskReadSpeed…）都被客户端
 *   的 Snapshot.java 逐个读取，字段名和类型不能随便改。
 *   /list 同理：客户端读 items[].{name,isDir,sizeText,mtime,writable}
 *   以及顶层 {root(字符串), path, absPath, writable, count}。
 *
 * ----------------------------------------------------------------------------
 * ★环境变量
 *   DSH_CONSOLE_PORT       监听端口              默认 8080
 *   DSH_CONSOLE_HOST       监听地址              默认 0.0.0.0
 *   DSH_CONSOLE_TOKEN      可选访问令牌           启用后 /api 均需 X-Auth-Token
 *   DSH_CONSOLE_ROOTS      文件白名单 "名称:路径[:rw|ro],..."  默认 Home/Tmp
 *   DSH_CONSOLE_LOG        编译日志文件路径        默认 <本目录>/build.log
 *   DSH_CONSOLE_STATE_DIR  状态目录(tasks/messages) 默认 <本目录>/state
 *   DSH_CONSOLE_DAEMONS    要监控的 systemd 单元 "名称:单元,..."
 *   DSH_CONSOLE_DISK_PATH  磁盘统计路径            默认 /
 *   DSH_CONSOLE_MAX_UPLOAD 上传大小上限(字节)      默认 2 GiB
 *   DSH_CONSOLE_CMDS_FILE  自定义快捷命令 JSON     默认内置安全命令集
 *
 * ----------------------------------------------------------------------------
 * ★安全设计
 *   · 文件访问受根目录白名单约束，路径穿越 + 符号链接逃逸都会被拦
 *   · 删除必须显式带 confirm=YES，且只能删可写根内的条目（根本身不可删）
 *   · 可选 Token 认证；上传有大小上限
 *   · 所有外部命令都用 execFile/spawn 传参数数组，不拼 shell 字符串
 *   · 默认只提供只读的快捷命令，危险命令需使用者自己写命令文件
 *   · 本服务只做局域网/内网用途，对外请自行加防火墙或反向代理鉴权
 * ============================================================================
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { execFileSync, spawn } = require('child_process');

// ────────────────────────────── 配置 ──────────────────────────────

const PORT = parseInt(process.env.DSH_CONSOLE_PORT || '8080', 10);
const HOST = process.env.DSH_CONSOLE_HOST || '0.0.0.0';
const TOKEN = process.env.DSH_CONSOLE_TOKEN || '';
const BASE_DIR = __dirname;
const LOG_FILE = process.env.DSH_CONSOLE_LOG || path.join(BASE_DIR, 'build.log');
const STATE_DIR = process.env.DSH_CONSOLE_STATE_DIR || path.join(BASE_DIR, 'state');
const DISK_PATH = process.env.DSH_CONSOLE_DISK_PATH || '/';
const MAX_UPLOAD = parseInt(process.env.DSH_CONSOLE_MAX_UPLOAD || String(2 * 1024 * 1024 * 1024), 10);
const MAX_BODY = 10 * 1024 * 1024;

const TASK_FILE = path.join(STATE_DIR, 'tasks.json');
const MSG_FILE = path.join(STATE_DIR, 'messages.json');
const TASK_TTL_MS = 15 * 60 * 1000;   // 15 分钟没更新才过期（编译任务可能很长）
const MSG_KEEP = 200;

// 告警阈值（可按需改代码或提 PR 做成环境变量）
const TH = {
  memWarn: 82, memCrit: 92,
  diskWarn: 85, diskCrit: 92,
  tempWarn: 75, tempCrit: 85,
  cpuHot: 95,
  swapWarn: 80,
};

// ────────────────────────────── 小工具 ──────────────────────────────

function log(msg) {
  console.log('[' + new Date().toISOString() + '] ' + msg);
}

/** 同步执行外部命令（参数数组，不经过 shell → 无注入面） */
function runFile(file, args, timeout) {
  try {
    return execFileSync(file, args || [], {
      encoding: 'utf8',
      timeout: timeout || 4000,
      stdio: ['ignore', 'pipe', 'ignore'],
      maxBuffer: 4 * 1024 * 1024,
    }).trim();
  } catch (e) {
    return '';
  }
}

/** 命令是否存在 */
function hasCmd(name) {
  return !!runFile('which', [name]);
}

function fmtBytes(bytes) {
  if (!bytes || bytes < 0 || !isFinite(bytes)) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0, v = bytes;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return v.toFixed(i >= 2 ? 1 : 0) + ' ' + u[i];
}

function fmtDuration(sec) {
  if (!isFinite(sec) || sec < 0) return '--';
  sec = Math.floor(sec);
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (d > 0) return d + '天' + h + '小时';
  if (h > 0) return h + 'h' + m + 'm';
  if (m > 0) return m + 'm' + s + 's';
  return s + 's';
}

function fmtTime(ms) {
  const d = new Date(ms);
  const p = n => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
}

function send(res, status, payload, extraHeaders) {
  if (res.writableEnded) return;
  const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
  res.writeHead(status, Object.assign({
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
  }, extraHeaders || {}));
  res.end(body);
}

/** 原子读 JSON */
function readJson(file, fallback) {
  try {
    const v = JSON.parse(fs.readFileSync(file, 'utf8'));
    return (v === null || v === undefined) ? fallback : v;
  } catch (e) {
    return fallback;
  }
}

/** 原子写 JSON：先写临时文件再 rename，避免并发读到半截 */
function writeJson(file, value) {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = file + '.tmp-' + process.pid + '-' + Math.random().toString(36).slice(2, 8);
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2), 'utf8');
    fs.renameSync(tmp, file);
    return true;
  } catch (e) {
    log('writeJson failed ' + file + ': ' + e.message);
    return false;
  }
}

/** 读请求体：先收 Buffer 再一次性转 UTF-8（逐 chunk 转会把中文切成乱码） */
function readBody(req, limit) {
  const max = limit || MAX_BODY;
  return new Promise((resolve) => {
    const chunks = [];
    let size = 0;
    let over = false;
    req.on('data', (c) => {
      if (over) return;
      size += c.length;
      if (size > max) { over = true; chunks.length = 0; resolve(''); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(over ? '' : Buffer.concat(chunks).toString('utf8')));
    req.on('error', () => resolve(''));
    req.on('aborted', () => resolve(''));
  });
}

/** 读 JSON 请求体（解析失败返回 null） */
async function readJsonBody(req, limit) {
  const raw = await readBody(req, limit);
  if (!raw) return null;
  try {
    const v = JSON.parse(raw);
    return (v && typeof v === 'object') ? v : null;
  } catch (e) {
    return null;
  }
}

function uid(prefix) {
  return (prefix || '') + Date.now().toString(36) + crypto.randomBytes(3).toString('hex');
}

// ────────────────────────────── 文件根目录 ──────────────────────────────

function expandHome(p) {
  return p.replace(/^~(?=\/|$)/, os.homedir())
    .replace(/^\$HOME(?=\/|$)/, os.homedir())
    .replace(/^\$\{HOME\}(?=\/|$)/, os.homedir());
}

/**
 * 解析 "名称:路径[:rw|ro]" 列表。
 * 路径本身可能含冒号，所以从右往左识别 mode，中间段重新拼回路径。
 */
function parseRoots(spec) {
  const defs = (spec && spec.trim())
    ? spec.split(',').map(s => s.trim()).filter(Boolean)
    : ['Home:' + os.homedir() + ':rw', 'Tmp:/tmp:rw'];

  return defs.map((def) => {
    let raw = def.split(':');
    if (raw.length === 1) {
      const p = expandHome(raw[0]);
      return { name: path.basename(p) || 'root', path: p, writable: true };
    }
    let writable = true;
    if (raw.length >= 3) {
      const last = raw[raw.length - 1].toLowerCase();
      if (last === 'rw' || last === 'ro') {
        writable = last === 'rw';
        raw = raw.slice(0, -1);
      }
    }
    const name = raw[0] || 'root';
    const p = expandHome(raw.slice(1).join(':'));
    return { name, path: p, writable };
  }).filter((r) => {
    try { return r.path && fs.statSync(r.path).isDirectory(); } catch (e) { return false; }
  });
}

const ROOTS = parseRoots(process.env.DSH_CONSOLE_ROOTS);

/**
 * 把用户给的相对路径解析到根目录内。
 * 两道防线：① 字面路径前缀检查（挡 ../）② realpath 检查（挡符号链接逃逸）
 * 返回 null 表示越界。
 */
function resolveSafe(rootPath, relPath) {
  let root, target;
  try {
    root = fs.realpathSync(path.resolve(rootPath));
  } catch (e) {
    return null;
  }
  target = path.resolve(root, '.' + path.sep + (relPath || ''));
  if (target !== root && !target.startsWith(root + path.sep)) return null;

  // ② realpath：目标存在就解析它本身，不存在（如上传目标）就解析父目录
  let real;
  try {
    real = fs.realpathSync(target);
  } catch (e) {
    try {
      real = path.join(fs.realpathSync(path.dirname(target)), path.basename(target));
    } catch (e2) {
      return null;
    }
  }
  if (real !== root && !real.startsWith(root + path.sep)) return null;
  return target;
}

/** 目标是否可写（在某个可写根内） */
function isWritable(target) {
  for (const r of ROOTS) {
    if (!r.writable) continue;
    let root;
    try { root = fs.realpathSync(path.resolve(r.path)); } catch (e) { continue; }
    if (target === root || target.startsWith(root + path.sep)) return true;
  }
  return false;
}

function mimeOf(name) {
  const ext = path.extname(name).toLowerCase();
  const m = {
    '.txt': 'text/plain; charset=utf-8',
    '.md': 'text/markdown; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.html': 'text/html; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.svg': 'image/svg+xml',
    '.apk': 'application/vnd.android.package-archive',
    '.zip': 'application/zip',
    '.img': 'application/octet-stream',
    '.tar': 'application/x-tar',
    '.gz': 'application/gzip',
    '.mp4': 'video/mp4',
    '.mp3': 'audio/mpeg',
    '.pdf': 'application/pdf',
  };
  return m[ext] || 'application/octet-stream';
}

// ────────────────────────────── 系统状态 ──────────────────────────────

const HAS_TAILSCALE = hasCmd('tailscale');
const HAS_SYSTEMCTL = hasCmd('systemctl');

function readProcMeminfo() {
  try {
    return fs.readFileSync('/proc/meminfo', 'utf8');
  } catch (e) {
    return '';
  }
}

function getMeminfo() {
  const raw = readProcMeminfo();
  const pick = (k) => {
    const m = raw.match(new RegExp('^' + k + ':\\s+(\\d+)', 'm'));
    return m ? parseInt(m[1], 10) * 1024 : 0;
  };
  const total = pick('MemTotal') || os.totalmem();
  const available = pick('MemAvailable') || os.freemem();
  const swapTotal = pick('SwapTotal');
  const swapFree = pick('SwapFree');
  return { total, available, swapTotal, swapFree };
}

function getCpuTemp() {
  const candidates = [
    '/sys/class/thermal/thermal_zone0/temp',
    '/sys/class/hwmon/hwmon0/temp1_input',
    '/sys/class/hwmon/hwmon0/temp',
  ];
  for (const f of candidates) {
    try {
      const v = parseInt(fs.readFileSync(f, 'utf8').trim(), 10);
      if (v > 1000) return Math.round(v / 1000);
      if (v > 0) return Math.round(v);
    } catch (e) { /* next */ }
  }
  return -1;
}

function getDiskUsage() {
  const out = runFile('df', ['-B1', DISK_PATH], 3000);
  if (!out) return { percent: 0, free: '0 B', total: '0 B', used: '0 B' };
  const lines = out.split('\n');
  const parts = (lines[1] || '').trim().split(/\s+/);
  if (parts.length < 5) return { percent: 0, free: '0 B', total: '0 B', used: '0 B' };
  const total = parseInt(parts[1], 10) || 0;
  const used = parseInt(parts[2], 10) || 0;
  const free = parseInt(parts[3], 10) || 0;
  return {
    percent: parseInt(String(parts[4]).replace('%', ''), 10) || 0,
    free: fmtBytes(free),
    total: fmtBytes(total),
    used: fmtBytes(used),
  };
}

function getNonLoopbackIpv4() {
  const ifs = os.networkInterfaces();
  for (const name of Object.keys(ifs)) {
    for (const it of ifs[name] || []) {
      if (it.family === 'IPv4' && !it.internal) return it.address;
    }
  }
  return '';
}

function isTailscaleUp() {
  if (!HAS_TAILSCALE) return false;
  const out = runFile('tailscale', ['status', '--json'], 3000);
  return /"BackendState"\s*:\s*"Running"/.test(out);
}

/**
 * 在线判定：
 *   装了 tailscale → 以 tailscale 的后端状态为准（它是唯一远程通道）
 *   没装          → 退化成「有没有非回环 IPv4」，避免开源部署误报离线
 */
function getOnlineStatus() {
  if (HAS_TAILSCALE) return isTailscaleUp();
  return getNonLoopbackIpv4() !== '';
}

// 网络 / 磁盘 IO 每秒采样一次，请求时直接读上次结果 → 速度值不会因为
// 请求频率变化而抖成 0，也不用每次请求都去 fork 命令。
const netState = { ts: 0, rx: 0, tx: 0, rxSpeed: 0, txSpeed: 0, iface: '' };
const diskState = { ts: 0, read: 0, write: 0, readSpeed: 0, writeSpeed: 0, dev: '' };

function sampleNet() {
  let iface = '', rx = 0, tx = 0;
  try {
    const lines = fs.readFileSync('/proc/net/dev', 'utf8').split('\n');
    for (let i = 2; i < lines.length; i++) {
      const ln = lines[i].trim();
      if (!ln) continue;
      const m = ln.match(/^([^:]+):\s*(\d+)\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+(\d+)/);
      if (!m) continue;
      const name = m[1].trim();
      if (name === 'lo') continue;
      if (/^(docker|br-|veth|virbr|tun|tap|wg)/.test(name)) continue;
      const r = parseInt(m[2], 10) || 0;
      const t = parseInt(m[3], 10) || 0;
      if (r > rx) { rx = r; tx = t; iface = name; }
    }
  } catch (e) { /* ignore */ }

  const now = Date.now();
  if (netState.ts > 0) {
    const dt = (now - netState.ts) / 1000;
    if (dt > 0.2) {
      netState.rxSpeed = Math.max(0, Math.round((rx - netState.rx) / dt));
      netState.txSpeed = Math.max(0, Math.round((tx - netState.tx) / dt));
    }
  }
  netState.ts = now; netState.rx = rx; netState.tx = tx; netState.iface = iface;
}

function sampleDiskIo() {
  let dev = '', rb = 0, wb = 0;
  try {
    const lines = fs.readFileSync('/proc/diskstats', 'utf8').split('\n');
    for (const line of lines) {
      const f = line.trim().split(/\s+/);
      if (f.length < 14) continue;
      const name = f[2];
      if (!/^(sd[a-z]+|nvme\d+n\d+|vd[a-z]+|mmcblk\d+|xvd[a-z]+)$/.test(name)) continue;
      const r = (parseInt(f[5], 10) || 0) * 512;
      const w = (parseInt(f[9], 10) || 0) * 512;
      if (r + w > rb + wb) { dev = name; rb = r; wb = w; }
    }
  } catch (e) { /* ignore */ }

  const now = Date.now();
  if (diskState.ts > 0) {
    const dt = (now - diskState.ts) / 1000;
    if (dt > 0.2) {
      diskState.readSpeed = Math.max(0, Math.round((rb - diskState.read) / dt));
      diskState.writeSpeed = Math.max(0, Math.round((wb - diskState.write) / dt));
    }
  }
  diskState.ts = now; diskState.read = rb; diskState.write = wb; diskState.dev = dev;
}

/** ★客户端 Snapshot.Sys 逐字段消费，字段名/类型不要改 */
function getSystemStatus() {
  const cpus = os.cpus() || [];
  const cores = cpus.length || 1;
  const load = os.loadavg();
  const cpuPct = Math.min(100, Math.round((load[0] / cores) * 100));

  const mem = getMeminfo();
  const memPct = mem.total > 0 ? Math.round(((mem.total - mem.available) / mem.total) * 100) : 0;

  const disk = getDiskUsage();
  const online = getOnlineStatus();

  return {
    cpu: cpuPct,
    cpuCores: cores,
    cpuModel: (cpus[0] && cpus[0].model) ? cpus[0].model.trim() : '',
    load1: Math.round(load[0] * 100) / 100,
    load5: Math.round(load[1] * 100) / 100,
    cpuTemp: getCpuTemp(),
    mem: memPct,
    memTotal: fmtBytes(mem.total),
    memUsed: fmtBytes(mem.total - mem.available),
    memFree: fmtBytes(mem.available),
    swapTotal: fmtBytes(mem.swapTotal),
    swapFree: fmtBytes(mem.swapFree),
    disk: disk.percent,
    diskFree: disk.free,
    diskTotal: disk.total,
    tailscale: online,
    uptime: fmtDuration(os.uptime()),
    host: os.hostname(),
    // 网络
    netRx: netState.rx,
    netTx: netState.tx,
    netRxSpeed: netState.rxSpeed,
    netTxSpeed: netState.txSpeed,
    netIface: netState.iface,
    // 磁盘 IO
    diskRead: diskState.read,
    diskWrite: diskState.write,
    diskReadSpeed: diskState.readSpeed,
    diskWriteSpeed: diskState.writeSpeed,
    diskIface: diskState.dev,
  };
}

// ────────────────────────────── 守护进程 ──────────────────────────────

/**
 * 解析 "名称:单元[:critical]" 列表。
 * 第三段可选，写 critical 表示这是关键服务（客户端会单独标记）。
 */
function parsePairs(spec) {
  return (spec || '').split(',').map(s => s.trim()).filter(Boolean).map((item) => {
    const parts = item.split(':').map(s => s.trim()).filter(Boolean);
    if (parts.length === 1) return { name: parts[0], unit: parts[0], critical: false };
    if (parts.length === 2) return { name: parts[0], unit: parts[1], critical: false };
    const last = parts[parts.length - 1].toLowerCase();
    if (last === 'critical' || last === 'optional') {
      return {
        name: parts[0],
        unit: parts.slice(1, -1).join(':'),
        critical: last === 'critical',
      };
    }
    return { name: parts[0], unit: parts.slice(1).join(':'), critical: false };
  });
}

const DAEMON_DEFS = parsePairs(process.env.DSH_CONSOLE_DAEMONS);

const daemonCache = { at: 0, data: [] };

/**
 * 一次性批量查询所有单元，避免每个单元 fork 两次 systemctl。
 * 缓存 10 秒，系统状态接口 3 秒轮询也扛得住。
 */
function getDaemons() {
  if (!DAEMON_DEFS.length) return [];
  const now = Date.now();
  if (now - daemonCache.at < 10000) return daemonCache.data;

  const units = DAEMON_DEFS.map(d => d.unit);
  let activeLines = [];
  let enabledLines = [];
  let showOut = '';

  if (HAS_SYSTEMCTL) {
    activeLines = runFile('systemctl', ['is-active'].concat(units), 5000).split('\n');
    enabledLines = runFile('systemctl', ['is-enabled'].concat(units), 5000).split('\n');
    showOut = runFile('systemctl',
      ['show'].concat(units).concat(['-p', 'Id', '-p', 'NRestarts', '-p', 'ActiveEnterTimestamp']), 6000);
  }

  // systemctl show 对多个单元按「块」输出，块顺序与入参一致；
  // 用顺序对齐而不是用 Id= 对齐 —— sshd 这类别名单元的 Id 会变成 ssh.service。
  const showBlocks = [];
  let cur = null;
  for (const line of showOut.split('\n')) {
    if (!line.trim()) { cur = null; continue; }
    const eq = line.indexOf('=');
    if (eq < 0) continue;
    const k = line.slice(0, eq);
    const v = line.slice(eq + 1);
    if (k === 'Id') { cur = {}; showBlocks.push(cur); }
    if (cur) cur[k] = v;
  }

  const out = DAEMON_DEFS.map((d, i) => {
    const active = (activeLines[i] || 'unknown').trim() || 'unknown';
    const enabled = (enabledLines[i] || 'unknown').trim() || 'unknown';
    const s = showBlocks[i] || {};
    return {
      name: d.name,
      unit: d.unit,
      label: d.name,
      active: active,
      enabled: enabled,
      healthy: active === 'active' || active === 'activating',
      critical: !!d.critical,
      since: s.ActiveEnterTimestamp || '',
      restarts: parseInt(s.NRestarts || '0', 10) || 0,
    };
  });

  daemonCache.at = now;
  daemonCache.data = out;
  return out;
}

// ────────────────────────────── 任务 ──────────────────────────────

function loadReportedTasks() {
  const arr = readJson(TASK_FILE, []);
  if (!Array.isArray(arr)) return [];
  const now = Date.now();
  return arr.filter(t => t && t.id && (now - (t.updatedAt || t.ts || 0)) < TASK_TTL_MS);
}

function saveReportedTasks(arr) {
  writeJson(TASK_FILE, arr.slice(-20));
}

/** 上报任务 → API 输出格式（客户端 Snapshot.Task 的字段） */
function getReportedTasks() {
  return loadReportedTasks().map(t => ({
    id: t.id,
    name: t.name || 'DSH 任务',
    progress: typeof t.progress === 'number' ? Math.max(0, Math.min(1, t.progress)) : 0,
    state: t.state || 'running',
    detail: t.detail || '',
    speed: t.speed || '',
    eta: t.eta || '',
    source: 'reported',
  }));
}

function upsertTask(task) {
  const arr = loadReportedTasks();
  const idx = arr.findIndex(t => t.id === task.id);
  if (idx >= 0) {
    const merged = arr[idx];
    for (const k of Object.keys(task)) {
      if (task[k] !== undefined && task[k] !== null && task[k] !== '') merged[k] = task[k];
    }
    merged.updatedAt = Date.now();
    arr[idx] = merged;
  } else {
    if (!task.name) task.name = 'DSH 任务';
    arr.push(task);
  }
  saveReportedTasks(arr);
  return task;
}

function removeTask(id) {
  const arr = loadReportedTasks();
  const kept = arr.filter(t => t.id !== id);
  saveReportedTasks(kept);
  return arr.length - kept.length;
}

/**
 * 可选：根据编译日志文件推导一个「编译中」任务。
 * 只有配置了 DSH_CONSOLE_LOG 且文件在最近 3 分钟内被写过才算在编译。
 */
function getBuildTask() {
  let st;
  try {
    st = fs.statSync(LOG_FILE);
  } catch (e) {
    return null;
  }
  const ageMs = Date.now() - st.mtimeMs;
  if (ageMs > 3 * 60 * 1000) return null;
  return {
    id: 'build-log',
    name: '编译',
    progress: 0,
    state: 'running',
    detail: '日志更新于 ' + fmtDuration(ageMs / 1000) + ' 前 · ' + fmtBytes(st.size),
    speed: '',
    eta: '',
    source: 'log',
  };
}

let lastTasks = [];

function getAllTasks() {
  const reported = getReportedTasks();
  const tasks = reported.slice();
  const b = getBuildTask();
  if (b && !tasks.some(t => t.id === b.id)) tasks.push(b);

  const active = tasks.filter(t => t.state === 'running' || t.state === 'queued');
  if (active.length > 0) {
    lastTasks = active;
    return tasks;
  }
  // 没有进行中的任务时，把刚完成的保留 60 秒，避免界面显示"瞬间空掉"
  lastTasks = lastTasks.filter(t => t.state === 'done' && (Date.now() - (t.doneAt || 0) < 60000));
  return tasks.length > 0 ? tasks : lastTasks;
}

// ────────────────────────────── 消息 ──────────────────────────────

function loadMessages() {
  const arr = readJson(MSG_FILE, []);
  return Array.isArray(arr) ? arr : [];
}

function saveMessages(arr) {
  writeJson(MSG_FILE, arr.slice(-MSG_KEEP));
}

function pushMessage(msg) {
  const arr = loadMessages();
  arr.push(msg);
  saveMessages(arr);
}

function markMessageRead(id) {
  const arr = loadMessages();
  let changed = false;
  for (const m of arr) {
    if ((!id || m.id === id) && !m.read) { m.read = true; changed = true; }
  }
  if (changed) saveMessages(arr);
}

/** 最近 50 条，按时间倒序（客户端直接按返回顺序渲染） */
function getMessages() {
  return loadMessages().slice(-50).reverse();
}

// ────────────────────────────── 告警 ──────────────────────────────

function getAlerts(sys) {
  const alerts = [];
  const minute = Math.floor(Date.now() / 60000);
  const add = (level, kind, title, detail) => {
    alerts.push({ id: kind + '-' + minute, level, kind, title, detail, ts: Date.now() });
  };

  if (sys.mem >= TH.memCrit) add('critical', 'mem', '内存告急', '已用 ' + sys.mem + '%（' + sys.memUsed + ' / ' + sys.memTotal + '）');
  else if (sys.mem >= TH.memWarn) add('warning', 'mem', '内存偏高', '已用 ' + sys.mem + '%');

  if (sys.disk >= TH.diskCrit) add('critical', 'disk', '磁盘告急', '已用 ' + sys.disk + '%，剩余 ' + sys.diskFree);
  else if (sys.disk >= TH.diskWarn) add('warning', 'disk', '磁盘偏高', '已用 ' + sys.disk + '%，剩余 ' + sys.diskFree);

  if (sys.cpuTemp > 0 && sys.cpuTemp >= TH.tempCrit) add('critical', 'temp', '温度过高', sys.cpuTemp + '°C');
  else if (sys.cpuTemp > 0 && sys.cpuTemp >= TH.tempWarn) add('warning', 'temp', '温度偏高', sys.cpuTemp + '°C');

  if (sys.cpu >= TH.cpuHot) add('warning', 'cpu', 'CPU 满载', sys.cpu + '%');

  if (!sys.tailscale) add('critical', 'online', '主机离线', '网络不可达');

  const down = getDaemons().filter(d => !d.healthy).map(d => d.label);
  if (down.length > 0) {
    add(down.length > 2 ? 'critical' : 'warning', 'daemon', '守护进程异常', down.join('、') + ' 未运行');
  }

  return alerts;
}

// ────────────────────────────── 快捷命令 ──────────────────────────────

const DEFAULT_COMMANDS = [
  { id: 'mem-info', name: '内存详情', desc: '内存 / swap 使用情况', cmd: 'free -h && echo && swapon --show', danger: false },
  { id: 'top-cpu', name: 'CPU 占用', desc: 'CPU 占用前 10 进程', cmd: 'ps aux --sort=-%cpu | head -11', danger: false },
  { id: 'disk-usage', name: '磁盘占用', desc: '各挂载点使用率', cmd: 'df -h', danger: false },
  { id: 'net-info', name: '网络接口', desc: '网卡地址与路由', cmd: 'ip -br a 2>/dev/null || ifconfig', danger: false },
  { id: 'sys-log', name: '系统日志', desc: '最近 50 行 journal', cmd: 'journalctl -n 50 --no-pager 2>/dev/null || tail -n 50 /var/log/syslog', danger: false },
  { id: 'uptime', name: '运行时长', desc: '负载与在线时长', cmd: 'uptime', danger: false },
  { id: 'sysinfo', name: '系统信息', desc: '内核 / 发行版信息', cmd: 'uname -a && echo && (cat /etc/os-release 2>/dev/null | head -4)', danger: false },
];

function loadCommands() {
  const file = process.env.DSH_CONSOLE_CMDS_FILE;
  if (file) {
    const arr = readJson(file, null);
    if (Array.isArray(arr)) {
      return arr.filter(c => c && c.id && c.cmd && c.name)
        .map(c => ({ id: String(c.id), name: String(c.name), desc: String(c.desc || ''), cmd: String(c.cmd), danger: !!c.danger }));
    }
    log('commands file invalid: ' + file);
  }
  return DEFAULT_COMMANDS;
}

const COMMANDS = loadCommands();
const cmdHistory = {};
const CMD_MAX_OUT = 20000;
const CMD_TTL_MS = 10 * 60 * 1000;

/** 执行快捷命令：命令体来自配置（不是 HTTP 入参），URL 只能选 id */
function runQuickCmd(id, cb) {
  const found = COMMANDS.find(c => c.id === id);
  if (!found) { cb({ error: 'unknown command: ' + id }); return; }

  const execId = found.id + '-' + Date.now();
  cmdHistory[execId] = { id: found.id, name: found.name, at: Date.now(), running: true, output: '', code: null };

  let child;
  try {
    child = spawn('/bin/bash', ['-c', found.cmd], {
      cwd: os.homedir(),
      env: Object.assign({}, process.env, {
        PATH: (process.env.PATH || '/usr/local/bin:/usr/bin:/bin') + ':' + os.homedir() + '/.local/bin',
      }),
    });
  } catch (e) {
    cmdHistory[execId].running = false;
    cmdHistory[execId].code = -1;
    cmdHistory[execId].output = String(e);
    cb({ execId, error: String(e) });
    return;
  }

  let out = '';
  const append = (d) => {
    if (out.length < CMD_MAX_OUT) out += d.toString('utf8');
  };
  child.stdout.on('data', append);
  child.stderr.on('data', append);
  child.on('close', (code) => {
    const rec = cmdHistory[execId];
    if (!rec) return;
    rec.running = false;
    rec.code = code;
    rec.output = out.slice(0, CMD_MAX_OUT);
    log('[cmd] ' + found.id + ' exited ' + code);
  });
  child.on('error', (e) => {
    const rec = cmdHistory[execId];
    if (!rec) return;
    rec.running = false;
    rec.code = -1;
    rec.output = String(e);
  });

  // 兜底超时：防止子进程卡死
  setTimeout(() => {
    const rec = cmdHistory[execId];
    if (rec && rec.running) {
      try { child.kill('SIGKILL'); } catch (e) { /* ignore */ }
      rec.running = false;
      rec.code = -1;
      rec.output = (rec.output || '') + '\n[超时 60 秒，已强制结束]';
    }
  }, 60000).unref();

  cb({ execId, name: found.name });
}

/** 清理过期命令记录，防止内存无限增长 */
function gcCmdHistory() {
  const now = Date.now();
  for (const k of Object.keys(cmdHistory)) {
    if (now - cmdHistory[k].at > CMD_TTL_MS) delete cmdHistory[k];
  }
}

// ────────────────────────────── 诊断 ──────────────────────────────

function buildDiagnose() {
  const report = { ts: Date.now(), problems: [], sections: [] };
  const addSection = (title, items) => report.sections.push({ title, items });

  const daemons = getDaemons();
  if (daemons.length) {
    addSection('守护进程', daemons.map(d => ({
      label: d.label,
      ok: d.healthy,
      text: d.active + (d.enabled === 'enabled' ? ' / 开机自启' : '')
        + (d.restarts > 0 ? ' (重启' + d.restarts + '次)' : ''),
    })));
  }

  const sys = getSystemStatus();
  addSection('系统资源', [
    { label: 'CPU', ok: sys.cpu < 90, text: sys.cpu + '% · 负载 ' + sys.load1 + ' / ' + sys.cpuCores + ' 核' },
    { label: '内存', ok: sys.mem < 90, text: sys.mem + '% (' + sys.memUsed + '/' + sys.memTotal + ')' },
    { label: '磁盘', ok: sys.disk < 90, text: sys.disk + '% 剩余 ' + sys.diskFree },
    { label: '温度', ok: !(sys.cpuTemp > 0) || sys.cpuTemp < 80, text: (sys.cpuTemp > 0 ? sys.cpuTemp : '?') + '°C' },
    { label: 'SWAP', ok: true, text: sys.swapFree + ' 空闲 / ' + sys.swapTotal },
  ]);

  const ip = getNonLoopbackIpv4();
  addSection('网络', [
    { label: HAS_TAILSCALE ? 'Tailscale' : '在线状态', ok: sys.tailscale, text: sys.tailscale ? '在线' : '离线' },
    { label: '网卡地址', ok: !!ip, text: ip || '(无)' },
    { label: '主机名', ok: true, text: sys.host },
  ]);

  // 最近一小时错误日志
  const errs = runFile('journalctl', ['-p', 'err', '-n', '10', '--no-pager', '--since', '1 hour ago'], 6000);
  const lines = errs.split('\n').map(l => l.trim()).filter(l => l && l.indexOf('No entries') < 0 && l.indexOf('-- ') !== 0);
  addSection('最近错误（1 小时）', lines.length === 0
    ? [{ label: '无错误', ok: true, text: '日志干净' }]
    : lines.slice(0, 5).map(l => ({ label: '⚠', ok: false, text: l.slice(0, 120) })));

  // 文件根目录可达性
  if (ROOTS.length) {
    addSection('文件根目录', ROOTS.map(r => {
      let ok = false;
      try { ok = fs.statSync(r.path).isDirectory(); } catch (e) { ok = false; }
      return { label: r.name, ok, text: r.path + (r.writable ? ' (rw)' : ' (ro)') };
    }));
  }

  for (const s of report.sections) {
    for (const it of s.items) if (!it.ok) report.problems.push(s.title + ' / ' + it.label);
  }
  return report;
}

// ────────────────────────────── 日志（HTML + SSE）──────────────────────────────

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
      size: fmtBytes(st.size),
      bytes: st.size,
      mtime: fmtTime(st.mtimeMs),
      ageSec: Math.floor((Date.now() - st.mtimeMs) / 1000),
    };
  } catch (e) {
    return { size: '(无日志)', bytes: 0, mtime: '', ageSec: -1 };
  }
}

/** 编译日志的 HTML 查看页（App 的 LogsFragment 用 WebView 打开它） */
function logPageHtml() {
  const s = logStatus();
  return '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
    + '<meta name="viewport" content="width=device-width,initial-scale=1">'
    + '<title>DSH 控制台 · 编译日志</title><style>'
    + 'html,body{margin:0;height:100%;background:#1b1d22;color:#c9d1d9;'
    + 'font:13px/1.55 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}'
    + '#bar{position:sticky;top:0;display:flex;gap:10px;align-items:center;'
    + 'padding:8px 12px;background:#23262e;border-bottom:1px solid #333844;font-size:12px}'
    + '#dot{width:8px;height:8px;border-radius:50%;background:#4c566a;flex:0 0 auto}'
    + '#dot.on{background:#34c759}#dot.off{background:#ff3b30}'
    + '#stat{flex:1;color:#8b949e;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}'
    + '#log{height:calc(100% - 38px);overflow-y:auto;padding:10px 12px;white-space:pre-wrap;'
    + 'word-break:break-all;box-sizing:border-box}'
    + '.err{color:#ff7b72}.warn{color:#e3b341}.ok{color:#3fb950}.info{color:#79c0ff}'
    + '</style></head><body>'
    + '<div id="bar"><span id="dot"></span><span id="stat">连接中…</span></div>'
    + '<div id="log"></div>'
    + '<script>(function(){'
    + 'var box=document.getElementById("log"),dot=document.getElementById("dot"),'
    + 'stat=document.getElementById("stat"),atBottom=true,es=null;'
    + 'function cls(line){var l=line.toLowerCase();'
    + 'if(l.indexOf("error")>=0||l.indexOf("失败")>=0||l.indexOf("error:")>=0)return "err";'
    + 'if(l.indexOf("warning")>=0||l.indexOf("警告")>=0)return "warn";'
    + 'if(l.indexOf("success")>=0||l.indexOf("完成")>=0||l.indexOf("build successful")>=0)return "ok";'
    + 'return "";}'
    + 'function paint(text){if(!text)return;var frag=document.createDocumentFragment();'
    + 'text.split("\\n").forEach(function(line){var sp=document.createElement("span");'
    + 'var c=cls(line);if(c)sp.className=c;sp.textContent=line+"\\n";frag.appendChild(sp);});'
    + 'if(box.childNodes.length>4000){for(var i=0;i<1000;i++)if(box.firstChild)box.removeChild(box.firstChild);}'
    + 'box.appendChild(frag);if(atBottom)box.scrollTop=box.scrollHeight;}'
    + 'box.addEventListener("scroll",function(){atBottom=box.scrollTop+box.clientHeight>=box.scrollHeight-40;});'
    + 'function conn(){if(es)try{es.close();}catch(e){}'
    + 'es=new EventSource("/api/logs/stream");'
    + 'es.onopen=function(){dot.className="on";};'
    + 'es.onerror=function(){dot.className="off";stat.textContent="连接断开，3 秒后重连…";'
    + 'try{es.close();}catch(e){}setTimeout(conn,3000);};'
    + 'es.onmessage=function(ev){var d={};try{d=JSON.parse(ev.data);}catch(e){return;}'
    + 'if(d.type==="init"){box.textContent="";paint(d.content);}'
    + 'else if(d.type==="append"){paint(d.content);}'
    + 'else if(d.type==="status"&&d.data){stat.textContent=d.data.size+" · 更新于 "+d.data.mtime;}};}'
    + 'conn();})();</script></body></html>';
}

function wantsHtml(req) {
  const accept = String(req.headers['accept'] || '');
  if (req.url && /[?&]format=html\b/.test(req.url)) return true;
  if (req.url && /[?&]format=json\b/.test(req.url)) return false;
  return accept.indexOf('text/html') >= 0;
}

// ────────────────────────────── 请求体辅助 ──────────────────────────────

function normPath(url) {
  const p = url.replace(/\/+$/, '');
  return p || '/';
}

// ────────────────────────────── 路由 ──────────────────────────────

const server = http.createServer(async (req, res) => {
  let parsed;
  try {
    parsed = new URL(req.url || '/', 'http://localhost');
  } catch (e) {
    return send(res, 400, { error: 'bad url' });
  }
  let p = normPath(parsed.pathname);
  const q = parsed.searchParams;
  const method = (req.method || 'GET').toUpperCase();

  if (method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type,X-Auth-Token',
    });
    return res.end();
  }

  // Token 认证
  if (TOKEN) {
    const provided = req.headers['x-auth-token'] || q.get('token') || '';
    if (provided !== TOKEN) return send(res, 401, { error: 'unauthorized' });
  }

  try {
    // ══════════════ 兼容别名：旧路径 → 新路径 ══════════════
    const ALIASES = {
      '/status': '/api/status',
      '/progress': '/api/progress',
      '/daemons': '/api/daemons',
      '/diagnose': '/api/diagnose',
      '/tasks': '/api/tasks',
      '/task': '/api/task',
      '/task/done': '/api/task/done',
      '/messages': '/api/messages',
      '/message': '/api/message',
      '/message/read': '/api/message/read',
      '/message/read-all': '/api/message/read-all',
      '/cmds': '/api/commands',
      '/cmd': '/api/command',
      '/cmd/result': '/api/command/result',
      '/roots': '/api/files/roots',
      '/list': '/api/files/list',
      '/download': '/api/files/download',
      '/upload': '/api/files/upload',
      '/delete': '/api/files/delete',
      '/info': '/api/files/info',
      '/log': '/api/logs',
      '/logs': '/api/logs',
      '/health': '/api/health',
    };
    if (ALIASES[p]) p = ALIASES[p];

    // ══════════════ 健康检查 ══════════════
    if (p === '/api/health') {
      return send(res, 200, { ok: true, ts: Date.now(), uptime: Math.floor(os.uptime()) });
    }

    // ══════════════ 核心：/api/progress（客户端首页 + 后台监控靠它）══════════════
    if (p === '/api/progress') {
      const sys = getSystemStatus();
      return send(res, 200, {
        ts: Date.now(),
        tasks: getAllTasks(),
        system: sys,
        alerts: getAlerts(sys),
        messages: getMessages(),
        daemons: getDaemons(),
      });
    }

    // ══════════════ 扁平状态（脚本用，字段与 /api/progress.system 一致）══════════════
    if (p === '/api/status') {
      const sys = getSystemStatus();
      return send(res, 200, Object.assign({ ts: Date.now() }, sys, { daemons: getDaemons() }));
    }

    if (p === '/api/daemons') {
      return send(res, 200, { daemons: getDaemons() });
    }

    if (p === '/api/diagnose') {
      return send(res, 200, buildDiagnose());
    }

    // ══════════════ 任务 ══════════════
    if (p === '/api/tasks' && method === 'GET') {
      return send(res, 200, { tasks: getReportedTasks() });
    }

    if (p === '/api/task' && method === 'POST') {
      const data = await readJsonBody(req);
      if (!data || !data.id) return send(res, 400, { error: 'id required' });
      data.updatedAt = Date.now();
      if (typeof data.progress === 'number') data.progress = Math.max(0, Math.min(1, data.progress));
      upsertTask(data);
      return send(res, 200, { ok: true, id: data.id });
    }

    if (p === '/api/task/done' && method === 'POST') {
      const data = (await readJsonBody(req)) || {};
      let removed;
      if (data.all) {
        const n = loadReportedTasks().length;
        saveReportedTasks([]);
        removed = n;
      } else {
        removed = removeTask(data.id || '');
      }
      return send(res, 200, { ok: true, removed });
    }

    // ══════════════ 消息 ══════════════
    if (p === '/api/messages' && method === 'GET') {
      return send(res, 200, { messages: getMessages() });
    }

    if (p === '/api/message' && method === 'POST') {
      const data = (await readJsonBody(req)) || {};
      if (!data.title && !data.body) return send(res, 400, { error: 'title or body required' });
      const msg = {
        id: uid('m_'),
        title: String(data.title || ''),
        body: String(data.body || ''),
        from: String(data.from || 'DSH'),
        ts: Date.now(),
        read: false,
      };
      pushMessage(msg);
      log('[message] ' + msg.title + ' - ' + msg.body);
      return send(res, 200, { ok: true, id: msg.id });
    }

    if (p === '/api/message/read' && method === 'POST') {
      const id = q.get('id') || ((await readJsonBody(req)) || {}).id || '';
      markMessageRead(id);
      return send(res, 200, { ok: true });
    }

    if (p === '/api/message/read-all' && method === 'POST') {
      const total = loadMessages().length;
      markMessageRead('');
      return send(res, 200, { ok: true, count: total });
    }

    // ══════════════ 快捷命令 ══════════════
    if (p === '/api/commands' && method === 'GET') {
      return send(res, 200, {
        commands: COMMANDS.map(c => ({ id: c.id, name: c.name, desc: c.desc, danger: !!c.danger })),
      });
    }

    if (p === '/api/command' && method === 'POST') {
      const id = q.get('id') || '';
      if (!id) return send(res, 400, { error: 'id required' });
      let result = null;
      runQuickCmd(id, (r) => { result = r; });
      if (!result) return send(res, 500, { error: 'command did not start' });
      return send(res, result.error ? 400 : 200, result);
    }

    if (p === '/api/command/result' && method === 'GET') {
      const execId = q.get('id') || '';
      const rec = cmdHistory[execId];
      if (!rec) return send(res, 404, { error: 'not found' });
      return send(res, 200, { running: rec.running, code: rec.code, output: rec.output });
    }

    // ══════════════ 文件：根目录 ══════════════
    if (p === '/api/files/roots' && method === 'GET') {
      return send(res, 200, {
        roots: ROOTS.map((r, i) => ({ index: i, name: r.name, writable: r.writable })),
      });
    }

    // ══════════════ 文件：列目录（客户端读 items/absPath/count/writable）══════════════
    if (p === '/api/files/list' && method === 'GET') {
      const root = ROOTS[parseInt(q.get('root') || '0', 10)];
      if (!root) return send(res, 400, { error: 'invalid root' });
      const relPath = q.get('path') || '';
      const target = resolveSafe(root.path, relPath);
      if (!target) return send(res, 403, { error: 'path traversal blocked' });

      let st;
      try { st = fs.statSync(target); } catch (e) { return send(res, 404, { error: e.message }); }
      if (!st.isDirectory()) return send(res, 400, { error: 'not a directory' });

      let entries;
      try {
        entries = fs.readdirSync(target, { withFileTypes: true });
      } catch (e) {
        return send(res, 500, { error: e.message });
      }

      const items = entries.map((e) => {
        const full = path.join(target, e.name);
        let size = 0, mtime = 0;
        try {
          const s = fs.statSync(full);
          size = s.size;
          mtime = s.mtimeMs;
        } catch (ex) { /* 无权限的条目保持 0 */ }
        return {
          name: e.name,
          isDir: e.isDirectory(),
          size,
          sizeText: e.isDirectory() ? '' : fmtBytes(size),
          mtime,
          writable: isWritable(full),
        };
      }).sort((a, b) => {
        if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
        return a.name.localeCompare(b.name);
      });

      return send(res, 200, {
        root: root.name,
        path: relPath || '/',
        absPath: target,
        writable: isWritable(target),
        items,
        count: items.length,
      });
    }

    // ══════════════ 文件：详情 ══════════════
    if (p === '/api/files/info' && method === 'GET') {
      const root = ROOTS[parseInt(q.get('root') || '0', 10)];
      if (!root) return send(res, 400, { error: 'invalid root' });
      const target = resolveSafe(root.path, q.get('path') || '');
      if (!target) return send(res, 403, { error: 'path traversal blocked' });
      let st;
      try { st = fs.statSync(target); } catch (e) { return send(res, 404, { error: e.message }); }
      return send(res, 200, {
        name: path.basename(target),
        absPath: target,
        isDir: st.isDirectory(),
        size: st.size,
        sizeText: fmtBytes(st.size),
        mtime: st.mtimeMs,
        mode: (st.mode & parseInt('777', 8)).toString(8),
        writable: isWritable(target),
      });
    }

    // ══════════════ 文件：下载（支持 Range，方便断点续传/播放）══════════════
    if (p === '/api/files/download' && method === 'GET') {
      const root = ROOTS[parseInt(q.get('root') || '0', 10)];
      if (!root) return send(res, 400, { error: 'invalid root' });
      const target = resolveSafe(root.path, q.get('path') || '');
      if (!target) return send(res, 403, { error: 'path traversal blocked' });

      let st;
      try { st = fs.statSync(target); } catch (e) { return send(res, 404, { error: 'not found' }); }
      if (st.isDirectory()) return send(res, 400, { error: 'is a directory' });

      const name = path.basename(target);
      const range = req.headers.range;
      const disposition = 'attachment; filename="' + encodeURIComponent(name) + '"; filename*=UTF-8\'\'' + encodeURIComponent(name);

      if (range) {
        const m = /^bytes=(\d*)-(\d*)$/.exec(String(range).trim());
        if (!m) return send(res, 416, { error: 'bad range' }, { 'Content-Range': 'bytes */' + st.size });
        let start = m[1] === '' ? null : parseInt(m[1], 10);
        let end = m[2] === '' ? null : parseInt(m[2], 10);
        if (start === null && end === null) return send(res, 416, { error: 'bad range' });
        if (start === null) { start = st.size - end; end = st.size - 1; }
        else if (end === null || end >= st.size) end = st.size - 1;
        if (start < 0 || start > end || start >= st.size) {
          return send(res, 416, { error: 'range not satisfiable' }, { 'Content-Range': 'bytes */' + st.size });
        }
        res.writeHead(206, {
          'Content-Type': mimeOf(name),
          'Content-Length': end - start + 1,
          'Content-Range': 'bytes ' + start + '-' + end + '/' + st.size,
          'Accept-Ranges': 'bytes',
          'Content-Disposition': disposition,
          'Access-Control-Allow-Origin': '*',
        });
        return fs.createReadStream(target, { start, end }).pipe(res);
      }

      res.writeHead(200, {
        'Content-Type': mimeOf(name),
        'Content-Length': st.size,
        'Accept-Ranges': 'bytes',
        'Content-Disposition': disposition,
        'Access-Control-Allow-Origin': '*',
      });
      return fs.createReadStream(target).pipe(res);
    }

    // ══════════════ 文件：上传（流式，带大小上限与失败清理）══════════════
    if (p === '/api/files/upload' && method === 'POST') {
      const root = ROOTS[parseInt(q.get('root') || '0', 10)];
      if (!root) return send(res, 400, { error: 'invalid root' });
      if (!root.writable) return send(res, 403, { error: 'root not writable' });

      const dir = resolveSafe(root.path, q.get('path') || '');
      if (!dir) return send(res, 403, { error: 'path traversal blocked' });

      const rawName = q.get('name') || '';
      if (!rawName) return send(res, 400, { error: 'name required' });
      const filename = path.basename(rawName);
      if (!filename || filename === '.' || filename === '..') return send(res, 400, { error: 'bad name' });

      // 目标必须在可写根内（再确认一次，防止通过符号链接目录写入外部）
      const target = path.join(dir, filename);
      if (!isWritable(target)) return send(res, 403, { error: 'target not writable' });

      const declared = parseInt(req.headers['content-length'] || '0', 10);
      if (declared && declared > MAX_UPLOAD) {
        return send(res, 413, { error: 'file too large', limit: MAX_UPLOAD });
      }

      let received = 0;
      let finished = false;
      let ws;
      try {
        ws = fs.createWriteStream(target);
      } catch (e) {
        return send(res, 500, { error: e.message });
      }

      const fail = (code, msg) => {
        if (finished) return;
        finished = true;
        try { ws.destroy(); } catch (e) { /* ignore */ }
        try { fs.unlinkSync(target); } catch (e) { /* ignore */ }
        send(res, code, { error: msg });
      };

      req.on('data', (chunk) => {
        received += chunk.length;
        if (received > MAX_UPLOAD) { fail(413, 'file too large'); try { req.destroy(); } catch (e) { /* ignore */ } }
      });
      req.on('aborted', () => fail(400, 'client aborted'));
      req.on('error', () => fail(400, 'request error'));

      ws.on('error', (e) => fail(500, e.message));
      ws.on('finish', () => {
        if (finished) return;
        finished = true;
        log('UPLOADED ' + target + ' (' + fmtBytes(received) + ')');
        send(res, 200, {
          ok: true,
          name: filename,
          size: received,
          sizeText: fmtBytes(received),
          path: target,
        });
      });

      req.pipe(ws);
      return;
    }

    // ══════════════ 文件：删除（必须 confirm=YES）══════════════
    if (p === '/api/files/delete' && method === 'POST') {
      if (q.get('confirm') !== 'YES') {
        return send(res, 400, { error: 'confirmation required', hint: '必须传 confirm=YES 才能删除' });
      }
      const root = ROOTS[parseInt(q.get('root') || '0', 10)];
      if (!root) return send(res, 400, { error: 'invalid root' });
      if (!root.writable) return send(res, 403, { error: 'root not writable' });

      const target = resolveSafe(root.path, q.get('path') || '');
      if (!target) return send(res, 403, { error: 'path traversal blocked' });

      let realRoot;
      try { realRoot = fs.realpathSync(path.resolve(root.path)); } catch (e) { return send(res, 500, { error: 'bad root' }); }
      if (target === realRoot) return send(res, 403, { error: 'cannot delete root' });
      if (!isWritable(target)) return send(res, 403, { error: 'not writable' });

      const name = path.basename(target);
      try {
        fs.rmSync(target, { recursive: true, force: false });
      } catch (e) {
        log('DELETE FAILED ' + target + ': ' + e.message);
        return send(res, 500, { error: e.message });
      }
      log('DELETED ' + target);
      return send(res, 200, { ok: true, deleted: name });
    }

    // ══════════════ 日志：HTML 页面（WebView）或 JSON ══════════════
    if (p === '/api/logs' && method === 'GET') {
      if (wantsHtml(req)) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        return res.end(logPageHtml());
      }
      const max = Math.max(1024, Math.min(parseInt(q.get('max') || '400000', 10) || 400000, 4 * 1024 * 1024));
      return send(res, 200, { status: logStatus(), content: readLogTail(max) });
    }

    // ══════════════ 日志：SSE 实时流 ══════════════
    if (p === '/api/logs/stream' && method === 'GET') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*',
        'X-Accel-Buffering': 'no',
      });
      const push = (obj) => {
        try { res.write('data: ' + JSON.stringify(obj) + '\n\n'); } catch (e) { /* ignore */ }
      };
      push({ type: 'init', content: readLogTail(400000) });
      push({ type: 'status', data: logStatus() });

      let lastSize = 0;
      try { lastSize = fs.statSync(LOG_FILE).size; } catch (e) { lastSize = 0; }

      const timer = setInterval(() => {
        try {
          const st = fs.statSync(LOG_FILE);
          if (st.size > lastSize) {
            const len = st.size - lastSize;
            const fd = fs.openSync(LOG_FILE, 'r');
            const buf = Buffer.alloc(len);
            fs.readSync(fd, buf, 0, len, lastSize);
            fs.closeSync(fd);
            lastSize = st.size;
            push({ type: 'append', content: buf.toString('utf8') });
            push({ type: 'status', data: logStatus() });
          } else if (st.size < lastSize) {
            lastSize = st.size;
            push({ type: 'init', content: readLogTail(400000) });
          }
        } catch (e) { /* 日志文件暂时不存在，等下一轮 */ }
      }, 700);

      // 心跳，防止中间代理掐掉空闲连接
      const beat = setInterval(() => {
        try { res.write(': ping\n\n'); } catch (e) { /* ignore */ }
      }, 15000);

      const cleanup = () => { clearInterval(timer); clearInterval(beat); };
      req.on('close', cleanup);
      res.on('close', cleanup);
      res.on('error', cleanup);
      return;
    }

    // ══════════════ 根路径：简单索引页 ══════════════
    if (p === '/' && method === 'GET') {
      const rows = [
        ['GET', '/progress', '状态总览（客户端首页/监控用）'],
        ['GET', '/api/status', '扁平状态 JSON'],
        ['GET', '/daemons', '守护进程'],
        ['GET', '/diagnose', '一键诊断'],
        ['GET', '/tasks', '任务列表'],
        ['POST', '/task', '上报任务'],
        ['POST', '/task/done', '结束任务'],
        ['GET', '/messages', '消息列表'],
        ['POST', '/message', '发送消息'],
        ['POST', '/message/read-all', '全部已读'],
        ['GET', '/cmds', '快捷命令列表'],
        ['POST', '/cmd?id=', '执行命令'],
        ['GET', '/cmd/result?id=', '取命令输出'],
        ['GET', '/roots', '文件根目录'],
        ['GET', '/list?root=0&path=/', '列目录'],
        ['GET', '/download?root=0&path=/x', '下载'],
        ['POST', '/upload?root=0&path=/&name=f', '上传'],
        ['POST', '/delete?root=0&path=/x&confirm=YES', '删除'],
        ['GET', '/api/logs', '编译日志（浏览器打开是页面）'],
        ['GET', '/api/logs/stream', '日志 SSE 流'],
      ].map(r => '<tr><td><code>' + r[0] + '</code></td><td><code>' + r[1]
        + '</code></td><td>' + r[2] + '</td></tr>').join('');

      const html = '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
        + '<meta name="viewport" content="width=device-width,initial-scale=1">'
        + '<title>DSH 控制台 · 后端服务</title><style>'
        + 'body{font:14px/1.7 system-ui,sans-serif;max-width:860px;margin:36px auto;padding:0 20px;color:#222}'
        + 'h1{font-size:20px;border-bottom:2px solid #7AA2F7;padding-bottom:10px}'
        + 'table{border-collapse:collapse;width:100%;font-size:13px}'
        + 'td{padding:5px 8px;border-bottom:1px solid #eee;vertical-align:top}'
        + 'td:first-child{width:64px;color:#7AA2F7;font-weight:600}'
        + 'td:nth-child(2){width:280px}code{background:#f4f4f8;padding:1px 5px;border-radius:3px}'
        + '.meta{color:#888;font-size:12px;margin-top:18px}'
        + '</style></head><body><h1>DSH 控制台 · 后端服务</h1>'
        + '<p>服务运行中，API 清单：</p><table>' + rows + '</table>'
        + '<p class="meta">主机 ' + os.hostname() + ' · 端口 ' + PORT
        + ' · <a href="/api/logs">编译日志</a></p></body></html>';
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(html);
    }

    return send(res, 404, { error: 'not found', path: p });
  } catch (e) {
    log('REQUEST ERROR ' + method + ' ' + p + ': ' + (e && e.stack ? e.stack : e));
    return send(res, 500, { error: String(e && e.message ? e.message : e) });
  }
});

// ────────────────────────────── 启动 ──────────────────────────────

// 网络/磁盘 IO 每秒采样，供 /progress 取用
sampleNet();
sampleDiskIo();
const sampleTimer = setInterval(() => {
  sampleNet();
  sampleDiskIo();
}, 1000);
sampleTimer.unref();

// 命令历史垃圾回收
const gcTimer = setInterval(gcCmdHistory, 5 * 60 * 1000);
gcTimer.unref();

const listenHost = HOST;
server.listen(PORT, listenHost, () => {
  log('DSH console backend listening on http://' + listenHost + ':' + PORT);
  log('node ' + process.version + ' · ' + os.type() + ' ' + os.release());
  log('file roots: ' + (ROOTS.map(r => r.name + '(' + r.path + (r.writable ? ',rw' : ',ro') + ')').join(', ') || '(none)'));
  log('log file: ' + LOG_FILE);
  log('state dir: ' + STATE_DIR);
  log('daemons: ' + (DAEMON_DEFS.map(d => d.name + '=' + d.unit).join(', ') || '(none)'));
  log('quick commands: ' + COMMANDS.length);
  log('token auth: ' + (TOKEN ? 'enabled' : 'disabled'));
  if (!ROOTS.length) log('WARNING: 没有可用的文件根目录，请检查 DSH_CONSOLE_ROOTS');
});

function shutdown(sig) {
  log('received ' + sig + ', shutting down');
  try { server.close(); } catch (e) { /* ignore */ }
  setTimeout(() => process.exit(0), 200).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
