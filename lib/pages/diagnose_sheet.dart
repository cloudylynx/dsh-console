import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../state/console_state.dart';

/// 一键诊断：底部弹出面板，打开即请求 GET /diagnose 并展示报告
///
/// 用法：DiagnoseSheet.show(context, state)
class DiagnoseSheet extends StatefulWidget {
  const DiagnoseSheet({
    super.key,
    required this.state,
    this.scrollController,
  });

  /// 全局状态（用它拿 ApiClient）
  final ConsoleState state;

  /// DraggableScrollableSheet 提供的滚动控制器（不传则内部自建）
  final ScrollController? scrollController;

  /// 弹出诊断面板
  static Future<void> show(BuildContext context, ConsoleState state) {
    return showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.6,
        minChildSize: 0.35,
        maxChildSize: 0.92,
        builder: (context, controller) => DiagnoseSheet(
          state: state,
          scrollController: controller,
        ),
      ),
    );
  }

  @override
  State<DiagnoseSheet> createState() => _DiagnoseSheetState();
}

class _DiagnoseSheetState extends State<DiagnoseSheet> {
  bool _loading = true;
  String? _error;
  String _report = '';
  int _elapsedMs = 0;

  @override
  void initState() {
    super.initState();
    _run();
  }

  /// 请求诊断（打开面板时自动调用，重试按钮也调它）
  Future<void> _run() async {
    if (!mounted) return;
    setState(() {
      _loading = true;
      _error = null;
      _report = '';
    });

    final t0 = DateTime.now();
    final api = widget.state.api;
    if (api == null) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '客户端未初始化，请先检查服务器地址';
      });
      return;
    }

    try {
      final raw = await api.diagnose();
      final ms = DateTime.now().difference(t0).inMilliseconds;
      if (!mounted) return;
      setState(() {
        _report = _formatReport(raw);
        _elapsedMs = ms;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = _friendlyError(e);
        _loading = false;
      });
    }
  }

  /// 后端返回 JSON 时整理成人能读的等宽文本；不是 JSON 就原样返回
  String _formatReport(String raw) {
    final text = raw.trim();
    if (!text.startsWith('{') && !text.startsWith('[')) return raw;

    Object? decoded;
    try {
      decoded = jsonDecode(text);
    } catch (_) {
      return raw;
    }

    if (decoded is Map && decoded['sections'] is List) {
      final buf = StringBuffer();
      for (final section in decoded['sections'] as List) {
        if (section is! Map) continue;
        final title = (section['title'] ?? '').toString();
        if (buf.isNotEmpty) buf.writeln();
        if (title.isNotEmpty) buf.writeln('【$title】');
        final items = section['items'];
        if (items is List) {
          for (final item in items) {
            if (item is! Map) continue;
            final ok = item['ok'];
            final mark = ok == true ? '✓' : (ok == false ? '✗' : '·');
            final label = (item['label'] ?? '').toString();
            final value = (item['text'] ?? '').toString();
            buf.writeln(' $mark $label${value.isEmpty ? '' : '  $value'}');
          }
        }
      }
      final out = buf.toString().trimRight();
      if (out.isNotEmpty) return out;
    }

    // 其它 JSON 结构：格式化后原样显示
    return const JsonEncoder.withIndent('  ').convert(decoded);
  }

  String _friendlyError(Object e) {
    final s = e.toString();
    if (s.contains('TimeoutException')) return '诊断超时（服务器 20 秒内没返回）';
    if (s.contains('SocketException')) return '连不上服务器，检查网络或地址';
    if (s.contains('HTTP')) return s.replaceAll('HttpException: ', '');
    return s.length > 120 ? '${s.substring(0, 120)}…' : s;
  }

  Future<void> _copy() async {
    final messenger = ScaffoldMessenger.of(context);
    await Clipboard.setData(ClipboardData(text: _report));
    messenger.showSnackBar(const SnackBar(
      content: Text('诊断报告已复制'),
      duration: Duration(seconds: 2),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final controller = widget.scrollController;

    return ListView(
      controller: controller,
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                '一键诊断',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                  color: cs.onSurface,
                ),
              ),
            ),
            if (!_loading && _error == null)
              IconButton(
                tooltip: '复制',
                visualDensity: VisualDensity.compact,
                onPressed: _copy,
                icon: const Icon(Icons.copy_rounded, size: 18),
              ),
            IconButton(
              tooltip: '重新诊断',
              visualDensity: VisualDensity.compact,
              onPressed: _loading ? null : _run,
              icon: const Icon(Icons.refresh_rounded, size: 18),
            ),
          ],
        ),
        Text(
          _loading
              ? '正在采集主机状态…'
              : (_error != null
                  ? '诊断失败'
                  : '耗时 ${(_elapsedMs / 1000).toStringAsFixed(1)}s'),
          style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: 14),
        if (_loading)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 40),
            child: Column(
              children: [
                const SizedBox(
                  width: 26,
                  height: 26,
                  child: CircularProgressIndicator(strokeWidth: 2.6),
                ),
                const SizedBox(height: 14),
                Text(
                  '诊断中…',
                  style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
                ),
              ],
            ),
          )
        else if (_error != null)
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: cs.errorContainer.withValues(alpha: 0.5),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.error_outline_rounded,
                        size: 18, color: cs.error),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _error!,
                        style: TextStyle(fontSize: 13, color: cs.onSurface),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.tonalIcon(
                    onPressed: _run,
                    icon: const Icon(Icons.refresh_rounded, size: 16),
                    label: const Text('重试', style: TextStyle(fontSize: 12)),
                  ),
                ),
              ],
            ),
          )
        else
          SelectableText(
            _report.isEmpty ? '（诊断没有返回内容）' : _report,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 12,
              height: 1.5,
              color: cs.onSurface,
            ),
          ),
      ],
    );
  }
}
