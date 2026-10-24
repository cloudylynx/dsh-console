import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// 通用 WebView 页面（终端 / 文件 / DSH 共用）
///
/// 特性：
///   · 自动处理 basic auth（ttyd 用）
///   · 加载进度条
///   · 出错重试
///   · 底部留白（给悬浮底栏让位）
class WebPage extends StatefulWidget {
  const WebPage({
    super.key,
    required this.url,
    required this.title,
    this.bottomReserve = 90,
    this.allowHttp = true,
    this.reloadKey,
  });

  final String url;
  final String title;
  final double bottomReserve;
  final bool allowHttp;

  /// 变化时重新加载（例如设置里改了地址）
  final Object? reloadKey;

  @override
  State<WebPage> createState() => _WebPageState();
}

class _WebPageState extends State<WebPage> {
  late WebViewController _ctl;
  int _progress = 0;
  bool _failed = false;
  String _error = '';

  @override
  void initState() {
    super.initState();
    // ★诊断：如果切页时看到 "INIT" 重复出现，说明 State 被销毁重建了
    debugPrint('[WebPage:${widget.title}] INIT  ${DateTime.now().toIso8601String()}');
    _build();
  }

  @override
  void dispose() {
    debugPrint('[WebPage:${widget.title}] DISPOSE  ${DateTime.now().toIso8601String()}');
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant WebPage old) {
    super.didUpdateWidget(old);
    if (old.url != widget.url || old.reloadKey != widget.reloadKey) {
      debugPrint('[WebPage:${widget.title}] RELOAD(参数变化)  ${DateTime.now().toIso8601String()}');
      _ctl.loadRequest(Uri.parse(widget.url));
    }
  }

  void _build() {
    _ctl = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(_bgColor(context))
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (p) {
            if (mounted) setState(() => _progress = p);
          },
          onPageStarted: (_) {
            if (mounted) {
              setState(() {
                _failed = false;
                _error = '';
              });
            }
          },
          onWebResourceError: (e) {
            // 只报主文档错误，忽略资源加载失败
            if (e.isForMainFrame != false) {
              if (mounted) {
                setState(() {
                  _failed = true;
                  _error = '${e.description} (${e.errorCode})';
                });
              }
            }
          },
          onPageFinished: (url) async {
            // ★检测 401：DSH 不带 token 会返回纯文本 401，页面看起来是空白
            try {
              final r = await _ctl.runJavaScriptReturningResult(
                'document.body ? document.body.innerText.length : 0',
              );
              final len = int.tryParse(r.toString().replaceAll('"', '')) ?? 0;
              if (len > 0 && len < 200) {
                final text = await _ctl.runJavaScriptReturningResult(
                  'document.body.innerText',
                );
                final body = text.toString().toLowerCase();
                if (body.contains('authentication required') ||
                    body.contains('401') ||
                    body.contains('unauthorized')) {
                  if (mounted) {
                    setState(() {
                      _failed = true;
                      _error = '需要登录（401）\n'
                          '如果是 DSH，请在设置里填写 Token\n'
                          '如果是终端，需要 basic auth';
                    });
                  }
                }
              }
            } catch (_) {
              // JS 执行失败忽略
            }
          },
          onHttpAuthRequest: (req) {
            // ttyd basic auth 会自动弹系统框，这里不处理
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.url));
  }

  /// WebView 背景色跟随主题（原来写死黑色，浅色模式下显脏）
  Color _bgColor(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return dark
        ? const Color(0xFF101014)
        : const Color(0xFFFAFAFA);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Stack(
      children: [
        // ★全屏：内容铺到屏幕最底，底栏浮在上面
        //   但顶部要让开状态栏（否则网页顶部被状态栏遮住）
        Positioned.fill(
          top: MediaQuery.of(context).padding.top,
          child: ColoredBox(
            color: _bgColor(context),
            child: _failed
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.cloud_off_rounded,
                          size: 48, color: cs.error.withValues(alpha: 0.7)),
                      const SizedBox(height: 12),
                      Text(widget.title,
                          style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                              color: cs.onSurface)),
                      const SizedBox(height: 6),
                      Text(_error,
                          style: TextStyle(
                              fontSize: 11, color: cs.onSurfaceVariant),
                          textAlign: TextAlign.center),
                      const SizedBox(height: 4),
                      Text(widget.url,
                          style: TextStyle(
                              fontSize: 10,
                              color: cs.onSurfaceVariant.withValues(alpha: 0.6)),
                          textAlign: TextAlign.center),
                      const SizedBox(height: 16),
                      FilledButton.tonalIcon(
                        onPressed: () =>
                            _ctl.loadRequest(Uri.parse(widget.url)),
                        icon: const Icon(Icons.refresh_rounded, size: 18),
                        label: const Text('重试'),
                      ),
                    ],
                  ),
                )
              : WebViewWidget(controller: _ctl),
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
      ],
    );
  }
}
