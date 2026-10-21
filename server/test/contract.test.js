#!/usr/bin/env node
/**
 * DSH 控制台后端 · 接口契约测试
 *
 * 为什么需要这个文件：
 *   客户端 Snapshot.java / FilesFragment.java 是按固定字段名消费 JSON 的。
 *   后端一旦把 `system` 改成扁平结构、把 `items` 改名成 `entries`、
 *   或者少一个 `writable`，App 不会报错，只会安静地显示全 0 / 空目录 / 只读。
 *   这种"静默失败"用肉眼看接口是看不出来的，所以必须有断言锁住契约。
 *
 * 用法：
 *   node test/contract.test.js          # 自己起一个临时实例测试
 *   TEST_PORT=19200 node test/contract.test.js
 */
'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.TEST_PORT || '19200', 10);
const HOST = '127.0.0.1';
const ROOT = path.join(__dirname, '..');

let pass = 0;
let fail = 0;
const failures = [];

function ok(name, cond, extra) {
  if (cond) {
    pass++;
    console.log('  \u2705 ' + name);
  } else {
    fail++;
    failures.push(name + (extra ? '  \u2192 ' + extra : ''));
    console.log('  \u274c ' + name + (extra ? '  \u2192 ' + extra : ''));
  }
}

function section(title) {
  console.log('\n\u3010' + title + '\u3011');
}

/**
 * 发请求。
 * ★必须收 Buffer 再一次性转字符串：逐 chunk 拼字符串会把跨 chunk 的中文切成
 *   U+FFFD，测试自己就会假阳性（这个坑踩过一次）。
 */
function req(method, urlPath, body, headers) {
  return new Promise((resolve) => {
    const r = http.request({
      host: HOST,
      port: PORT,
      path: urlPath,
      method,
      headers: Object.assign({}, headers || {}),
    }, (res) => {
      const bufs = [];
      res.on('data', c => bufs.push(c));
      res.on('end', () => resolve({
        code: res.statusCode,
        text: Buffer.concat(bufs).toString('utf8'),
        headers: res.headers,
      }));
    });
    r.on('error', e => resolve({ code: -1, text: String(e), headers: {} }));
    if (body !== undefined && body !== null) r.write(body);
    r.end();
  });
}

async function json(method, urlPath, body, headers) {
  const r = await req(method, urlPath, body, headers);
  let j = null;
  try { j = JSON.parse(r.text); } catch (e) { /* 留 null */ }
  return { code: r.code, j, text: r.text, headers: r.headers };
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// ── 测试用临时目录 ──
function makeFixtures() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-console-test-'));
  const root = path.join(base, 'root');
  fs.mkdirSync(path.join(root, 'sub'), { recursive: true });
  fs.writeFileSync(path.join(root, 'a.txt'), 'hello dsh\n');
  fs.writeFileSync(path.join(root, 'sub', 'b.txt'), 'deep\n');
  // 符号链接逃逸测试素材
  try { fs.symlinkSync('/etc', path.join(root, 'evil'), 'dir'); } catch (e) { /* 无权限就跳过 */ }
  const log = path.join(base, 'build.log');
  fs.writeFileSync(log, 'line1\n');
  const state = path.join(base, 'state');
  fs.mkdirSync(state, { recursive: true });
  return { base, root, log, state };
}

function startServer(fx) {
  const child = spawn(process.execPath, [path.join(ROOT, 'server.js')], {
    cwd: ROOT,
    env: Object.assign({}, process.env, {
      DSH_CONSOLE_PORT: String(PORT),
      DSH_CONSOLE_HOST: HOST,
      DSH_CONSOLE_ROOTS: 'Test:' + fx.root + ':rw',
      DSH_CONSOLE_LOG: fx.log,
      DSH_CONSOLE_STATE_DIR: fx.state,
      DSH_CONSOLE_DAEMONS: 'ssh:sshd:critical',
      DSH_CONSOLE_TOKEN: '',
    }),
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let out = '';
  child.stdout.on('data', d => { out += d.toString(); });
  child.stderr.on('data', d => { out += d.toString(); });
  child.getOutput = () => out;
  return child;
}

async function waitReady(timeoutMs) {
  const deadline = Date.now() + (timeoutMs || 8000);
  while (Date.now() < deadline) {
    const r = await req('GET', '/api/health');
    if (r.code === 200) return true;
    await sleep(120);
  }
  return false;
}

(async () => {
  const fx = makeFixtures();
  const child = startServer(fx);
  const ready = await waitReady();

  if (!ready) {
    console.error('服务没起来。输出：\n' + child.getOutput());
    try { child.kill('SIGKILL'); } catch (e) { /* ignore */ }
    process.exit(1);
  }

  try {
    // ══════════════════════════════════════════════════════════
    section('/progress 富格式（首页 + 后台监控靠它）');

    const pr = await json('GET', '/progress');
    ok('HTTP 200', pr.code === 200, pr.code);
    const P = pr.j || {};
    ok('顶层 ts 是数字', typeof P.ts === 'number');
    ok('顶层 tasks 是数组', Array.isArray(P.tasks));
    ok('顶层 alerts 是数组', Array.isArray(P.alerts));
    ok('顶层 messages 是数组', Array.isArray(P.messages));
    ok('顶层 daemons 是数组', Array.isArray(P.daemons));
    ok('\u2605\u2605 顶层 system 是对象（缺了它首页全 0）', !!P.system && typeof P.system === 'object');

    const S = P.system || {};
    const sysInt = ['cpu', 'cpuCores', 'mem', 'disk', 'cpuTemp'];
    const sysStr = ['cpuModel', 'memTotal', 'memUsed', 'memFree', 'swapTotal', 'swapFree',
      'diskFree', 'uptime', 'host', 'netIface', 'diskIface'];
    const sysNum = ['load1', 'load5', 'netRx', 'netTx', 'netRxSpeed', 'netTxSpeed',
      'diskRead', 'diskWrite', 'diskReadSpeed', 'diskWriteSpeed'];

    for (const k of sysInt) ok('system.' + k + ' 是整数', Number.isInteger(S[k]), JSON.stringify(S[k]));
    for (const k of sysStr) ok('system.' + k + ' 是字符串', typeof S[k] === 'string', typeof S[k]);
    for (const k of sysNum) ok('system.' + k + ' 是数字', typeof S[k] === 'number', typeof S[k]);
    ok('system.tailscale 是布尔', typeof S.tailscale === 'boolean');
    ok('cpu 在 0~100', S.cpu >= 0 && S.cpu <= 100, S.cpu);
    ok('mem 在 0~100', S.mem >= 0 && S.mem <= 100, S.mem);
    ok('disk 在 0~100', S.disk >= 0 && S.disk <= 100, S.disk);

    // ══════════════════════════════════════════════════════════
    section('daemons 字段');

    if (P.daemons && P.daemons.length) {
      const d = P.daemons[0];
      for (const k of ['name', 'label', 'active', 'enabled', 'since']) {
        ok('daemon.' + k + ' 是字符串', typeof d[k] === 'string', typeof d[k]);
      }
      ok('daemon.healthy 是布尔', typeof d.healthy === 'boolean');
      ok('daemon.critical 是布尔', typeof d.critical === 'boolean');
      ok('daemon.restarts 是数字', typeof d.restarts === 'number');
    } else {
      ok('守护进程列表非空', false, 'DSH_CONSOLE_DAEMONS 没生效');
    }

    // ══════════════════════════════════════════════════════════
    section('任务上报 / 结束');

    const t1 = await json('POST', '/task',
      JSON.stringify({ id: 't-1', name: '测试任务', progress: 0.45, state: 'running', detail: '进行中', eta: '2m' }),
      { 'Content-Type': 'application/json' });
    ok('POST /task 200', t1.code === 200, t1.code);

    const t2 = await json('GET', '/tasks');
    const task = ((t2.j || {}).tasks || []).find(x => x.id === 't-1');
    ok('任务被写入', !!task);
    if (task) {
      ok('task.name 正确', task.name === '测试任务', task.name);
      ok('task.progress 是数字', typeof task.progress === 'number');
      ok('task.state 正确', task.state === 'running');
      ok('task.detail 是字符串', typeof task.detail === 'string');
      ok('task.speed 是字符串', typeof task.speed === 'string');
      ok('task.eta 正确', task.eta === '2m');
    }

    const pr2 = await json('GET', '/progress');
    ok('/progress 里能看到上报的任务', ((pr2.j || {}).tasks || []).some(x => x.id === 't-1'));

    const td = await json('POST', '/task/done', JSON.stringify({ id: 't-1' }), { 'Content-Type': 'application/json' });
    ok('POST /task/done 成功', td.code === 200 && td.j.ok === true, JSON.stringify(td.j));
    const t3 = await json('GET', '/tasks');
    ok('任务已移除', !((t3.j || {}).tasks || []).some(x => x.id === 't-1'));

    // ══════════════════════════════════════════════════════════
    section('消息 / 中文往返');

    const big = '\u4e2d\u6587\u6d4b\u8bd5\u5185\u5bb9'.repeat(5000) + '\u7ed3\u5c3e\u6807\u8bb0\u2713';
    const m1 = await json('POST', '/message',
      JSON.stringify({ title: '标题', body: big, from: 'DSH' }),
      { 'Content-Type': 'application/json' });
    ok('POST /message 200', m1.code === 200 && m1.j.ok === true, JSON.stringify(m1.j));

    const m2 = await json('GET', '/messages');
    const msgs = (m2.j || {}).messages || [];
    const got = msgs.find(x => x.title === '标题');
    ok('/messages 含刚发的消息', !!got);
    if (got) {
      for (const k of ['id', 'title', 'body', 'from']) ok('msg.' + k + ' 是字符串', typeof got[k] === 'string');
      ok('msg.ts 是数字', typeof got.ts === 'number');
      ok('msg.read 是布尔', typeof got.read === 'boolean');
      ok('\u2605 大段中文完整往返（跨 chunk 没被切坏）', got.body === big,
        got.body ? (got.body.length + ' vs ' + big.length) : '缺失');
      ok('\u2605 没有 U+FFFD 替换字符', got.body.indexOf('\uFFFD') < 0);
    }

    const mr = await json('POST', '/message/read?id=' + encodeURIComponent((got || {}).id || ''), null);
    ok('POST /message/read 200', mr.code === 200);
    const m3 = await json('GET', '/messages');
    ok('消息已标记已读', ((m3.j || {}).messages || []).every(x => x.read === true));
    ok('POST /message/read-all 200', (await json('POST', '/message/read-all', '{}')).code === 200);

    // ══════════════════════════════════════════════════════════
    section('快捷命令');

    const c1 = await json('GET', '/cmds');
    const cmds = (c1.j || {}).commands || [];
    ok('/cmds 返回数组', Array.isArray(cmds));
    ok('命令含 id/name/desc/danger', cmds.length > 0 && cmds.every(c =>
      typeof c.id === 'string' && typeof c.name === 'string'
      && typeof c.desc === 'string' && typeof c.danger === 'boolean'));

    const target = cmds.find(c => c.id === 'uptime') || cmds[0];
    const ex = await json('POST', '/cmd?id=' + encodeURIComponent(target.id), null);
    ok('POST /cmd 返回 execId', ex.code === 200 && typeof ex.j.execId === 'string', JSON.stringify(ex.j));

    let out = null;
    for (let i = 0; i < 60; i++) {
      await sleep(120);
      const r = await json('GET', '/cmd/result?id=' + encodeURIComponent(ex.j.execId));
      if (r.j && r.j.running === false) { out = r.j; break; }
    }
    ok('/cmd/result 返回 running/code/output',
      out && typeof out.code === 'number' && typeof out.output === 'string');
    ok('命令有输出且退出码 0', !!out && out.code === 0 && out.output.trim().length > 0);

    // ══════════════════════════════════════════════════════════
    section('文件接口（FilesFragment 的契约）');

    const r1 = await json('GET', '/roots');
    ok('/roots 返回 roots 数组', Array.isArray((r1.j || {}).roots));
    ok('root 含 name/writable', ((r1.j || {}).roots || []).every(x =>
      typeof x.name === 'string' && typeof x.writable === 'boolean'));

    const l1 = await json('GET', '/list?root=0&path=');
    const L = l1.j || {};
    ok('/list 200', l1.code === 200, l1.code);
    ok('\u2605\u2605 顶层 root 是字符串（曾写成对象）', typeof L.root === 'string', typeof L.root);
    ok('\u2605\u2605 有 items 数组（曾叫 entries，导致永远空目录）', Array.isArray(L.items),
      Object.keys(L).join(','));
    ok('有 absPath 字符串', typeof L.absPath === 'string');
    ok('\u2605\u2605 顶层有 writable（缺了会导致全部显示只读）', typeof L.writable === 'boolean');
    ok('有 count 数字', typeof L.count === 'number');
    ok('没有残留的 entries 字段', L.entries === undefined);

    const item = (L.items || [])[0] || {};
    ok('item.name 是字符串', typeof item.name === 'string');
    ok('item.isDir 是布尔', typeof item.isDir === 'boolean');
    ok('item.sizeText 是字符串', typeof item.sizeText === 'string');
    ok('item.mtime 是数字', typeof item.mtime === 'number');
    ok('item.writable 是布尔', typeof item.writable === 'boolean');
    ok('目录排在文件前面', (L.items || [])[0].isDir === true,
      JSON.stringify((L.items || []).map(x => x.name)));
    ok('子目录可列出', ((await json('GET', '/list?root=0&path=/sub')).j || {}).count === 1);

    const dl = await req('GET', '/download?root=0&path=/a.txt');
    ok('下载内容正确', dl.text === 'hello dsh\n', JSON.stringify(dl.text));
    const rng = await req('GET', '/download?root=0&path=/a.txt', null, { Range: 'bytes=0-4' });
    ok('Range 请求返回 206', rng.code === 206, rng.code);
    ok('Range 内容正确', rng.text === 'hello', JSON.stringify(rng.text));

    const upName = encodeURIComponent('中文 文件.txt');
    const up = await req('POST', '/upload?root=0&path=/&name=' + upName, '上传的中文内容',
      { 'Content-Type': 'application/octet-stream' });
    ok('上传 200', up.code === 200, up.code + ' ' + up.text);
    const upJ = JSON.parse(up.text || '{}');
    ok('上传返回 ok/name/size', upJ.ok === true && typeof upJ.size === 'number');

    const l2 = await json('GET', '/list?root=0&path=');
    ok('上传的文件出现在列表', ((l2.j || {}).items || []).some(i => i.name === '中文 文件.txt'));
    const dl2 = await req('GET', '/download?root=0&path=' + encodeURIComponent('/中文 文件.txt'));
    ok('上传内容能原样下载回来', dl2.text === '上传的中文内容', JSON.stringify(dl2.text));

    ok('删除不带 confirm 被拒 400',
      (await json('POST', '/delete?root=0&path=/a.txt', null)).code === 400);
    const del = await json('POST',
      '/delete?root=0&path=' + encodeURIComponent('/中文 文件.txt') + '&confirm=YES', null);
    ok('删除 200 且返回 deleted', del.code === 200 && typeof del.j.deleted === 'string', JSON.stringify(del.j));
    ok('删根目录被拒 403', (await json('POST', '/delete?root=0&path=/&confirm=YES', null)).code === 403);

    // ══════════════════════════════════════════════════════════
    section('安全：目录穿越 / 符号链接');

    ok('../../ 穿越被拦 403',
      (await json('GET', '/list?root=0&path=' + encodeURIComponent('../../etc'))).code === 403);
    ok('绝对路径被限制在根内',
      [403, 404].includes((await json('GET', '/list?root=0&path=' + encodeURIComponent('/etc'))).code));
    ok('\u2605 符号链接目录逃逸被拦 403',
      (await json('GET', '/list?root=0&path=' + encodeURIComponent('evil'))).code === 403);
    ok('\u2605 通过符号链接下载 /etc/passwd 被拦',
      (await req('GET', '/download?root=0&path=' + encodeURIComponent('evil/passwd'))).code === 403);
    ok('上传路径穿越被拦 403',
      [403, 400].includes((await req('POST', '/upload?root=0&path=' + encodeURIComponent('../'),
        'x', { 'Content-Type': 'application/octet-stream' })).code));

    // ══════════════════════════════════════════════════════════
    section('诊断');

    const dg = await json('GET', '/diagnose');
    ok('/diagnose 200', dg.code === 200, dg.code);
    ok('有 sections 数组', Array.isArray((dg.j || {}).sections));
    ok('有 problems 数组', Array.isArray((dg.j || {}).problems));
    const sec = ((dg.j || {}).sections || [])[0] || {};
    ok('section 有 title', typeof sec.title === 'string');
    ok('section.items 含 label/ok/text', Array.isArray(sec.items) && sec.items.every(i =>
      typeof i.label === 'string' && typeof i.ok === 'boolean' && typeof i.text === 'string'));

    // ══════════════════════════════════════════════════════════
    section('日志页面 / JSON / SSE');

    const html = await req('GET', '/api/logs', null, { Accept: 'text/html,application/xhtml+xml' });
    ok('Accept: text/html 返回 HTML', html.code === 200 && /<html/i.test(html.text), html.code);
    ok('页面 charset 是 utf-8', /charset=utf-8/i.test(html.headers['content-type'] || ''));
    ok('\u2605 页面里有 id="log"（App 自动滚动依赖它）', /id="log"/.test(html.text));
    ok('页面连的是 /api/logs/stream', /api\/logs\/stream/.test(html.text));

    const lgj = await json('GET', '/api/logs', null, { Accept: 'application/json' });
    ok('Accept: json 返回 JSON', lgj.code === 200 && !!lgj.j && typeof lgj.j.content === 'string');
    ok('JSON 里有 status.size', typeof ((lgj.j || {}).status || {}).size === 'string');

    const sseOk = await new Promise((resolve) => {
      let buf = '', appendSeen = false, destroyed = false;
      const r = http.request({ host: HOST, port: PORT, path: '/api/logs/stream' }, (res) => {
        res.on('data', (c) => {
          buf += c.toString('utf8');
          if (!appendSeen && buf.indexOf('"type":"init"') >= 0) {
            fs.appendFileSync(fx.log, 'APPEND_MARKER\n');
          }
          if (buf.indexOf('APPEND_MARKER') >= 0 && !destroyed) {
            appendSeen = true;
            destroyed = true;
            r.destroy();
          }
        });
      });
      r.on('error', () => { /* destroy 会触发 */ });
      r.end();
      setTimeout(() => { try { r.destroy(); } catch (e) { /* ignore */ } resolve(appendSeen); }, 5000);
    });
    ok('\u2605 SSE 能实时推送新增日志行', sseOk);

    // ══════════════════════════════════════════════════════════
    section('其他');

    ok('/health 正常', (await json('GET', '/health')).j.ok === true);
    ok('未知路径 404', (await json('GET', '/definitely-not-a-route')).code === 404);
    ok('30 个并发 /progress 全部 200',
      (await Promise.all(Array.from({ length: 30 }, () => req('GET', '/progress'))))
        .every(r => r.code === 200));

  } finally {
    try { child.kill('SIGTERM'); } catch (e) { /* ignore */ }
    await sleep(400);
    try { child.kill('SIGKILL'); } catch (e) { /* ignore */ }
    try { fs.rmSync(fx.base, { recursive: true, force: true }); } catch (e) { /* ignore */ }
  }

  console.log('\n' + '='.repeat(46));
  console.log('通过 ' + pass + ' 项，失败 ' + fail + ' 项');
  if (fail) {
    console.log('\n失败清单：');
    failures.forEach(f => console.log('  · ' + f));
  }
  process.exit(fail === 0 ? 0 : 1);
})();
