import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../widgets/terminal_keypad.dart';

/// 终端页：ttyd（xterm.js）+ 底部辅助按键条
///
/// 与旧版 TerminalFragment 的对应关系：
///   · WebView  + basic auth + 进度条 + 出错重试
///   · buildKeypad()      → [TerminalKeypad]
///   · injectData()       → [TerminalPage._send]
///
/// 注入方式（多级降级，与旧版一致，且已在 ttyd 1.7.7 的打包产物里
/// 逐一确认这些入口真实存在）：
///   1. term.input(data, true)          → coreService.triggerDataEvent
///   2. term._core.input(data, true)
///   3. term.coreService.triggerDataEvent(data, true)
///   4. term.paste(data)
/// 上面任一条都会走到 xterm 的 onData → ttyd sendData → WebSocket，
/// 因此不需要模拟键盘事件（KeyboardEvent 对 xterm 并不可靠）。
class TerminalPage extends StatefulWidget {
  const TerminalPage({
    super.key,
    required this.url,
    this.title = '终端',
    this.username = '',
    this.password = '',
    this.bottomReserve = 90,
    this.reloadKey,
    this.showKeypad = true,
  });

  /// ttyd 地址
  final String url;

  final String title;

  /// ttyd basic auth 用户名 / 密码（空 = 不自动认证）
  final String username;
  final String password;

  /// 底部为悬浮底栏预留的高度
  final double bottomReserve;

  /// 变化时重新加载（例如设置里改了地址）
  final Object? reloadKey;

  /// 初始是否展开按键条
  final bool showKeypad;

  @override
  State<TerminalPage> createState() => _TerminalPageState();
}

class _TerminalPageState extends State<TerminalPage> {
  late final WebViewController _ctl;

  int _progress = 0;
  String? _error;

  /// 终端（xterm）是否已就绪
  bool _ready = false;

  /// 按键条是否展开
  late bool _keypadVisible = widget.showKeypad;

  /// 探测轮询的代号（换页时作废旧轮询）
  int _probeGen = 0;

  static const Color _bg = Color(0xFF101014);

  @override
  void initState() {
    super.initState();
    _ctl = _createController();
  }

  @override
  void didUpdateWidget(covariant TerminalPage old) {
    super.didUpdateWidget(old);
    if (old.url != widget.url || old.reloadKey != widget.reloadKey) {
      _ready = false;
      _error = null;
      _ctl.loadRequest(Uri.parse(widget.url));
    }
  }

  WebViewController _createController() {
    return WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(_bg)
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (p) {
            if (mounted) setState(() => _progress = p);
          },
          onPageStarted: (_) {
            _probeGen++;
            if (mounted) {
              setState(() {
                _error = null;
                _ready = false;
              });
            }
          },
          onPageFinished: (_) {
            _probeReady();
          },
          onWebResourceError: (e) {
            if (e.isForMainFrame != false && mounted) {
              setState(() => _error = '${e.description} (${e.errorCode})');
            }
          },
          onHttpAuthRequest: (req) {
            // ★ttyd 默认带 basic auth，这里必须应答，否则页面卡住
            if (widget.username.isNotEmpty || widget.password.isNotEmpty) {
              req.onProceed(WebViewCredential(
                user: widget.username,
                password: widget.password,
              ));
            } else {
              req.onCancel();
              if (mounted) {
                setState(() => _error = '终端需要登录\n请在设置里填写终端用户名/密码');
              }
            }
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.url));
  }

  // ── 就绪探测 ────────────────────────────────
  //
  // ttyd 的 window.term 是页面加载完成之后才挂上的，
  // 所以 onPageFinished 之后还要再等一小会儿。
  Future<void> _probeReady() async {
    final gen = ++_probeGen;
    for (var i = 0; i < 15; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 400));
      if (!mounted || gen != _probeGen) return;
      if (await _hasTerm()) {
        if (!mounted || gen != _probeGen) return;
        setState(() => _ready = true);
        return;
      }
    }
    if (mounted && gen == _probeGen) setState(() => _ready = false);
  }

  Future<bool> _hasTerm() async {
    try {
      final r = await _ctl.runJavaScriptReturningResult(
        'typeof window.term === "object" && window.term !== null && '
        'typeof window.term.input === "function"',
      );
      return _asText(r) == 'true';
    } catch (_) {
      return false;
    }
  }

  // ── 注入 ────────────────────────────────────
  //
  // 把数据塞进 xterm。返回 true 表示确认已交给 xterm（不是"发送成功"的
  // 网络层确认，但 xterm 拿到数据后一定会写进 ttyd 的 WebSocket）。
  Future<bool> _send(String data) async {
    // 万一探测还没成功，这里再试一次（比如用户手快）
    if (!_ready) {
      if (!await _hasTerm()) return false;
      if (mounted) setState(() => _ready = true);
    }
    try {
      final r = await _ctl.runJavaScriptReturningResult(
        '$_injectFn(${jsonEncode(data)})',
      );
      const ok = <String>{'input', 'core', 'trigger', 'paste'};
      return ok.contains(_asText(r));
    } catch (_) {
      return false;
    }
  }

  /// 多级降级注入函数（形如 `(function(d){...})`，后面直接接参数调用）
  static const String _injectFn = '(function(d){'
      'try{'
      'var t=window.term;'
      'if(!t)return "no-term";'
      'if(typeof t.input==="function"){t.input(d,true);return "input";}'
      'if(t._core&&typeof t._core.input==="function"){'
      't._core.input(d,true);return "core";}'
      'var cs=t.coreService;'
      'if(cs&&typeof cs.triggerDataEvent==="function"){'
      'cs.triggerDataEvent(d,true);return "trigger";}'
      'if(typeof t.paste==="function"){t.paste(d);return "paste";}'
      '}catch(e){return "err:"+(e&&e.message?e.message:e);}'
      'return "none";'
      '})';

  /// runJavaScriptReturningResult 在 Android 上返回的是 JSON 编码值，
  /// 字符串会带引号；这里统一剥成裸文本。
  String _asText(Object? r) {
    final s = r.toString();
    if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
      try {
        final decoded = jsonDecode(s);
        if (decoded is String) return decoded;
      } catch (_) {
        // 落到下面手工剥引号
      }
      return s.substring(1, s.length - 1);
    }
    return s;
  }

  // ── UI ──────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final topInset = MediaQuery.of(context).padding.top;

    return Column(
      children: <Widget>[
        Expanded(
          child: Padding(
            padding: EdgeInsets.only(top: topInset),
            child: Stack(
              children: <Widget>[
                Positioned.fill(
                  child: ColoredBox(
                    color: _bg,
                    child: _error == null
                        ? WebViewWidget(controller: _ctl)
                        : _buildError(cs),
                  ),
                ),
                // 顶部进度条
                if (_progress > 0 && _progress < 100)
                  Positioned(
                    top: 0,
                    left: 0,
                    right: 0,
                    child: LinearProgressIndicator(
                      value: _progress / 100,
                      minHeight: 2,
                      backgroundColor: Colors.transparent,
                    ),
                  ),
                // 右上角小工具条：刷新 / 键盘开关
                Positioned(
                  top: 6,
                  right: 6,
                  child: _buildTools(cs),
                ),
              ],
            ),
          ),
        ),
        // 底部按键条（在悬浮底栏上方留出 bottomReserve）
        Padding(
          padding: EdgeInsets.only(bottom: widget.bottomReserve),
          child: AnimatedSize(
            duration: const Duration(milliseconds: 180),
            curve: Curves.easeOutCubic,
            alignment: Alignment.topCenter,
            child: _keypadVisible
                ? Padding(
                    padding: const EdgeInsets.fromLTRB(6, 4, 6, 2),
                    child: TerminalKeypad(
                      ready: _ready,
                      onSend: _send,
                      onCollapse: () =>
                          setState(() => _keypadVisible = false),
                    ),
                  )
                : const SizedBox(width: double.infinity),
          ),
        ),
      ],
    );
  }

  Widget _buildTools(ColorScheme cs) {
    return Material(
      color: _bg.withValues(alpha: 0.72),
      borderRadius: BorderRadius.circular(10),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          _toolButton(
            icon: Icons.refresh_rounded,
            tooltip: '刷新',
            onTap: () => _ctl.loadRequest(Uri.parse(widget.url)),
          ),
          _toolButton(
            icon: _keypadVisible
                ? Icons.keyboard_hide_rounded
                : Icons.keyboard_alt_rounded,
            tooltip: _keypadVisible ? '收起按键' : '展开按键',
            onTap: () => setState(() => _keypadVisible = !_keypadVisible),
          ),
        ],
      ),
    );
  }

  Widget _toolButton({
    required IconData icon,
    required String tooltip,
    required VoidCallback onTap,
  }) {
    return Tooltip(
      message: tooltip,
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(7),
          child: Icon(icon, size: 18, color: Colors.white70),
        ),
      ),
    );
  }

  Widget _buildError(ColorScheme cs) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(Icons.terminal_rounded,
                size: 44, color: cs.error.withValues(alpha: 0.7)),
            const SizedBox(height: 12),
            Text(
              widget.title,
              style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: Colors.white,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              _error ?? '',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: Colors.white60),
            ),
            const SizedBox(height: 4),
            Text(
              widget.url,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 10, color: Colors.white30),
            ),
            const SizedBox(height: 16),
            FilledButton.tonalIcon(
              onPressed: () {
                setState(() => _error = null);
                _ctl.loadRequest(Uri.parse(widget.url));
              },
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: const Text('重试'),
            ),
          ],
        ),
      ),
    );
  }
}
