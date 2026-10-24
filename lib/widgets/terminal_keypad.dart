import 'package:flutter/material.dart';

/// 终端辅助按键条（对应旧版 TerminalFragment.buildKeypad()）
///
/// 设计要点：
///   · 一行横向可滚动，每个键是圆角小按钮
///   · CTRL / ALT 是「粘滞修饰键」，点亮后只对下一次按键生效
///   · 按键最终会拼成原始字节序列（转义序列），交给 [onSend] 注入终端
///   · 若 [onSend] 返回 false（终端未就绪），这里负责弹一次轻提示，
///     绝不假装已经发送成功
class TerminalKeypad extends StatefulWidget {
  const TerminalKeypad({
    super.key,
    required this.onSend,
    this.ready = true,
    this.onCollapse,
    this.padding = EdgeInsets.zero,
  });

  /// 发送原始数据（可能含 ESC 等控制字符）到终端。
  /// 返回 true 表示已确认送达；false 表示终端未就绪 / 注入失败。
  final Future<bool> Function(String data) onSend;

  /// 终端是否已就绪（xterm 是否已加载）。未就绪时按键变暗，但仍可点（点了给提示）。
  final bool ready;

  /// 收起按键条（右上角的小箭头）
  final VoidCallback? onCollapse;

  final EdgeInsets padding;

  @override
  State<TerminalKeypad> createState() => _TerminalKeypadState();
}

class _TerminalKeypadState extends State<TerminalKeypad> {
  /// 粘滞 CTRL
  bool _ctrl = false;

  /// 粘滞 ALT
  bool _alt = false;

  /// 提示节流（避免连点狂弹 SnackBar）
  DateTime? _lastHintAt;

  static const String _esc = '\x1b';

  /// 按键表：label + 行为
  static final List<_KeySpec> _keys = <_KeySpec>[
    const _KeySpec('ESC', seq: _esc),
    const _KeySpec('TAB', seq: '\x09'),
    const _KeySpec('CTRL', toggle: _Modifier.ctrl),
    const _KeySpec('ALT', toggle: _Modifier.alt),
    const _KeySpec('↑', seq: '$_esc[A'),
    const _KeySpec('↓', seq: '$_esc[B'),
    const _KeySpec('←', seq: '$_esc[D'),
    const _KeySpec('→', seq: '$_esc[C'),
    const _KeySpec('HOME', seq: '$_esc[H'),
    const _KeySpec('END', seq: '$_esc[F'),
    const _KeySpec('PGUP', seq: '$_esc[5~'),
    const _KeySpec('PGDN', seq: '$_esc[6~'),
    const _KeySpec('|', text: '|'),
    const _KeySpec('~', text: '~'),
    const _KeySpec('/', text: '/'),
    const _KeySpec('-', text: '-'),
  ];

  /// 特殊键在 CTRL 下的组合（xterm 修饰码 5 = Ctrl）
  static const Map<String, String> _ctrlSeq = <String, String>{
    '$_esc[A': '$_esc[1;5A',
    '$_esc[B': '$_esc[1;5B',
    '$_esc[C': '$_esc[1;5C',
    '$_esc[D': '$_esc[1;5D',
    '$_esc[H': '$_esc[1;5H',
    '$_esc[F': '$_esc[1;5F',
    '$_esc[5~': '$_esc[5;5~',
    '$_esc[6~': '$_esc[6;5~',
  };

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;

    final bg = dark
        ? cs.surfaceContainerHighest.withValues(alpha: 0.96)
        : cs.surface.withValues(alpha: 0.98);

    return Padding(
      padding: widget.padding,
      child: Material(
        color: bg,
        elevation: 6,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 5, 4, 5),
          child: Row(
            children: [
              Expanded(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 2),
                  child: Row(
                    children: <Widget>[
                      for (final k in _keys) _buildKey(context, k, cs, dark),
                    ],
                  ),
                ),
              ),
              if (widget.onCollapse != null) ...[
                const SizedBox(width: 2),
                _buildCollapse(cs),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildKey(
    BuildContext context,
    _KeySpec spec,
    ColorScheme cs,
    bool dark,
  ) {
    final active = (spec.toggle == _Modifier.ctrl && _ctrl) ||
        (spec.toggle == _Modifier.alt && _alt);

    final Color fg;
    final Color fill;
    if (active) {
      fg = cs.onPrimary;
      fill = cs.primary;
    } else {
      fg = dark
          ? cs.onSurface.withValues(alpha: 0.92)
          : cs.onSurface.withValues(alpha: 0.88);
      fill = dark
          ? Colors.white.withValues(alpha: 0.10)
          : cs.surfaceContainerHighest.withValues(alpha: 0.9);
    }

    final double opacity = widget.ready ? 1.0 : 0.55;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 1),
      child: Opacity(
        opacity: opacity,
        child: Material(
          color: fill,
          borderRadius: BorderRadius.circular(7),
          child: InkWell(
            borderRadius: BorderRadius.circular(7),
            onTap: () => _press(spec),
            child: Container(
              constraints: const BoxConstraints(minWidth: 38, minHeight: 34),
              alignment: Alignment.center,
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
              child: Text(
                spec.label,
                style: TextStyle(
                  fontSize: 12,
                  height: 1.1,
                  fontWeight: FontWeight.w600,
                  fontFamily: 'monospace',
                  color: fg,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildCollapse(ColorScheme cs) {
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(7),
      child: InkWell(
        borderRadius: BorderRadius.circular(7),
        onTap: widget.onCollapse,
        child: Container(
          constraints: const BoxConstraints(minWidth: 32, minHeight: 34),
          alignment: Alignment.center,
          child: Icon(
            Icons.keyboard_hide_rounded,
            size: 18,
            color: cs.onSurfaceVariant,
          ),
        ),
      ),
    );
  }

  Future<void> _press(_KeySpec spec) async {
    // 修饰键：只切换状态，不发数据
    if (spec.toggle != null) {
      setState(() {
        if (spec.toggle == _Modifier.ctrl) {
          _ctrl = !_ctrl;
          if (_ctrl) _alt = false;
        } else {
          _alt = !_alt;
          if (_alt) _ctrl = false;
        }
      });
      return;
    }

    final data = _compose(spec);
    if (data == null) return;

    // 修饰键是「一次性」的：用完就复位
    final hadModifier = _ctrl || _alt;
    if (hadModifier) {
      setState(() {
        _ctrl = false;
        _alt = false;
      });
    }

    final ok = await widget.onSend(data);
    if (!mounted) return;
    if (!ok) _hint('终端未就绪，按键暂不可用');
  }

  /// 组合最终字节序列；返回 null 表示这次什么都不发
  String? _compose(_KeySpec spec) {
    String? out;

    if (spec.text != null) {
      out = spec.text;
      if (_ctrl) {
        // CTRL 对这些符号没有标准映射 → 退化为原字符
        out = _ctrlChar(out!) ?? out;
      }
    } else if (spec.seq != null) {
      out = spec.seq;
      if (_ctrl) out = _ctrlSeq[out!] ?? out;
    }

    if (out == null) return null;
    if (_alt) out = _esc + out;
    return out;
  }

  /// Ctrl+字符 → 控制字符（与旧版 ctrlChars() 一致）
  String? _ctrlChar(String s) {
    if (s.isEmpty) return null;
    final int c = s.codeUnitAt(0);
    if (c >= 0x61 && c <= 0x7a) return String.fromCharCode(c - 0x60);
    if (c >= 0x41 && c <= 0x5a) return String.fromCharCode(c - 0x40);
    switch (s) {
      case '[':
        return _esc;
      case r'\':
        return '\x1c';
      case ']':
        return '\x1d';
      case '^':
        return '\x1e';
      case '_':
        return '\x1f';
      case ' ':
        return '\x00';
      default:
        return null;
    }
  }

  void _hint(String msg) {
    final now = DateTime.now();
    if (_lastHintAt != null &&
        now.difference(_lastHintAt!) < const Duration(seconds: 2)) {
      return;
    }
    _lastHintAt = now;
    final messenger = ScaffoldMessenger.of(context);
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(msg),
          duration: const Duration(milliseconds: 1400),
          behavior: SnackBarBehavior.floating,
        ),
      );
  }
}

enum _Modifier { ctrl, alt }

/// 一个按键的定义
class _KeySpec {
  const _KeySpec(this.label, {this.seq, this.text, this.toggle})
      : assert(
          (seq != null ? 1 : 0) +
                  (text != null ? 1 : 0) +
                  (toggle != null ? 1 : 0) ==
              1,
          '按键必须且只能有 seq / text / toggle 之一',
        );

  final String label;

  /// 固定转义序列（特殊键）
  final String? seq;

  /// 普通字符
  final String? text;

  /// 修饰键
  final _Modifier? toggle;
}
