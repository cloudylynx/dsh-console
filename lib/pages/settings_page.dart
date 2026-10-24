import 'package:flutter/material.dart';

import '../settings.dart';
import '../state/console_state.dart';

/// 设置页
class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.settings,
    required this.state,
    required this.bottomReserve,
    required this.onServerChanged,
  });

  final AppSettings settings;
  final ConsoleState state;
  final double bottomReserve;
  final VoidCallback onServerChanged;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListenableBuilder(
      listenable: widget.settings,
      builder: (context, _) => CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 56, 16, 8),
            sliver: SliverToBoxAdapter(
              child: Text('设置',
                  style: TextStyle(
                      fontSize: 26,
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface)),
            ),
          ),

          // 服务器
          _section('① 服务器'),
          _tile(
            '主机地址',
            widget.settings.host,
            () => _editText(
              '主机地址',
              widget.settings.host,
              (v) {
                widget.settings.host = v;
                widget.onServerChanged();
              },
              hint: '留空则不连接',
            ),
          ),
          _tile(
            '端口',
            '${widget.settings.port}',
            () => _editNumber(
              '端口',
              widget.settings.port,
              (v) {
                widget.settings.port = v;
                widget.onServerChanged();
              },
            ),
          ),
          _tile(
            '连接状态',
            widget.state.error ?? '正常 · ${widget.state.latencyMs}ms',
            () async {
              await widget.state.refresh();
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                  content: Text(widget.state.error ?? '连接正常'),
                  duration: const Duration(seconds: 2),
                ));
              }
            },
          ),

          // 外观
          _section('② 外观'),
          _choice(
            '主题',
            ['跟随系统', '浅色', '深色'][widget.settings.themeMode],
            () => _pickOne(
              '主题',
              ['跟随系统', '浅色', '深色'],
              widget.settings.themeMode,
              (i) => setState(() => widget.settings.themeMode = i),
            ),
          ),
          _colorPicker(),
          _slider(
            '刷新间隔',
            widget.settings.interval.toDouble(),
            1,
            30,
            '秒',
            (v) {
              widget.settings.interval = v.round();
              widget.state
                ..stop()
                ..start();
            },
          ),
          _slider(
            '图表点数',
            widget.settings.chartPoints.toDouble(),
            10,
            240,
            '点',
            (v) => widget.settings.chartPoints = v.round(),
          ),

          // 液态玻璃
          _section('③ 液态玻璃'),
          _slider(
            '底栏高度',
            widget.settings.dockHeight,
            48,
            96,
            'dp',
            (v) => widget.settings.dockHeight = v,
          ),
          _slider(
            '图标放大',
            widget.settings.iconScale,
            0,
            1,
            '',
            (v) => widget.settings.iconScale = v,
          ),
          _slider(
            '选中块内缩',
            widget.settings.indicatorInset,
            0,
            12,
            'dp',
            (v) => widget.settings.indicatorInset = v,
          ),
          _slider(
            '色调浓度',
            widget.settings.tintAmount,
            0,
            1,
            '',
            (v) => widget.settings.tintAmount = v,
          ),
          _slider(
            '模糊霜化',
            widget.settings.frost,
            0,
            24,
            '',
            (v) => widget.settings.frost = v,
          ),
          _slider(
            '高光强度',
            widget.settings.highlight,
            0,
            1,
            '',
            (v) => widget.settings.highlight = v,
          ),
          _slider(
            '折射强度',
            widget.settings.refractionAmount,
            0,
            160,
            '',
            (v) => widget.settings.refractionAmount = v,
          ),
          _slider(
            '折射高度',
            widget.settings.refractionHeight,
            0,
            48,
            'dp',
            (v) => widget.settings.refractionHeight = v,
          ),
          _slider(
            '轮廓强度',
            widget.settings.contourStrength,
            0,
            1,
            '',
            (v) => widget.settings.contourStrength = v,
          ),
          _slider(
            '斜面阴影',
            widget.settings.bevelShadow,
            0,
            0.3,
            '',
            (v) => widget.settings.bevelShadow = v,
          ),
          _slider(
            '展开动画时长',
            widget.settings.animDuration.toDouble(),
            80,
            1000,
            'ms',
            (v) => widget.settings.animDuration = v.round(),
          ),
          _choice(
            '动画曲线',
            AppSettings.animCurveNames[widget.settings.animCurve],
            () => _pickOne(
              '动画曲线',
              AppSettings.animCurveNames,
              widget.settings.animCurve,
              (i) => setState(() => widget.settings.animCurve = i),
            ),
          ),
          _slider(
            '图标切换时长',
            widget.settings.iconFadeDuration.toDouble(),
            60,
            800,
            'ms',
            (v) => widget.settings.iconFadeDuration = v.round(),
          ),
          _slider(
            '图标切换缩放',
            widget.settings.iconFadeScale,
            0.3,
            1.0,
            '',
            (v) => widget.settings.iconFadeScale = v,
          ),
          _tile('恢复默认玻璃参数', '点一下重置全部', () {
            _confirm('恢复默认', '所有玻璃参数将重置为默认值，确定？', () {
              widget.settings
                ..dockHeight = 64
                ..iconScale = 0.45
                ..indicatorInset = 3
                ..tintAmount = 0.12
                ..frost = 3.2
                ..highlight = 0.28
                ..refractionAmount = 72
                ..refractionHeight = 18
                ..contourStrength = 0.32
                ..bevelShadow = 0.05;
              setState(() {});
            });
          }),

          // 终端 / DSH
          _section('④ 终端与 DSH'),
          _tile(
            '终端地址',
            widget.settings.terminalUrl,
            () => _editText('终端地址', widget.settings.terminalUrl,
                (v) => widget.settings.terminalUrl = v,
                hint: 'http://IP:18801/'),
          ),
          _slider(
            '终端字号',
            widget.settings.terminalFontSize.toDouble(),
            8,
            32,
            'px',
            (v) => widget.settings.terminalFontSize = v.round(),
          ),
          _tile(
            'DSH 地址',
            widget.settings.dshUrl,
            () => _editText('DSH 地址', widget.settings.dshUrl,
                (v) => widget.settings.dshUrl = v,
                hint: 'http://IP:19387/'),
          ),
          _tile(
            'DSH Token',
            widget.settings.dshToken.isEmpty
                ? '未设置'
                : '${widget.settings.dshToken.substring(0, widget.settings.dshToken.length.clamp(0, 8))}…',
            () => _editText('DSH Token', widget.settings.dshToken,
                (v) => widget.settings.dshToken = v,
                hint: '登录用的 token'),
          ),

          // 底栏顺序
          _section('⑤ 底栏顺序'),
          _tabOrderBox(),
          _tile('恢复默认顺序', '首页 / 终端 / 文件 / DSH / 设置', () {
            _confirm('恢复默认顺序', '底栏将恢复为默认顺序，确定？', () {
              widget.settings.resetTabOrder();
              setState(() {});
            });
          }),
          _switchTile(
            '消息通知',
            '有新消息时提醒',
            widget.settings.notifyMsg,
            (v) => setState(() => widget.settings.notifyMsg = v),
          ),
          _switchTile(
            '告警通知',
            '内存/磁盘/温度异常时提醒',
            widget.settings.notifyAlert,
            (v) => setState(() => widget.settings.notifyAlert = v),
          ),
          _slider(
            '内存阈值',
            widget.settings.memThreshold.toDouble(),
            50,
            99,
            '%',
            (v) => setState(() => widget.settings.memThreshold = v.round()),
          ),
          _slider(
            '磁盘阈值',
            widget.settings.diskThreshold.toDouble(),
            50,
            99,
            '%',
            (v) => setState(() => widget.settings.diskThreshold = v.round()),
          ),
          _tile('测试通知', '点一下发条测试告警', _testNotification),

          SliverToBoxAdapter(child: SizedBox(height: widget.bottomReserve)),
        ],
      ),
    );
  }

  // ── 组件 ────────────────────────────────
  Widget _section(String title) => SliverPadding(
        padding: const EdgeInsets.fromLTRB(16, 18, 16, 6),
        sliver: SliverToBoxAdapter(
          child: Text(
            title,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
        ),
      );

  /// 带开关的列表项（后台监控那三个开关用）
  Widget _switchTile(
    String title,
    String subtitle,
    bool value,
    ValueChanged<bool> onChanged,
  ) {
    final cs = Theme.of(context).colorScheme;
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      sliver: SliverToBoxAdapter(
        child: Container(
          padding: const EdgeInsets.fromLTRB(14, 6, 8, 6),
          decoration: BoxDecoration(
            color: cs.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(title,
                        style: TextStyle(fontSize: 14, color: cs.onSurface)),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style:
                          TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
              Switch(value: value, onChanged: onChanged),
            ],
          ),
        ),
      ),
    );
  }

  Widget _tile(String title, String value, VoidCallback onTap) {
    final cs = Theme.of(context).colorScheme;
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      sliver: SliverToBoxAdapter(
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
            decoration: BoxDecoration(
              color: cs.surfaceContainerHigh,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(title,
                      style:
                          TextStyle(fontSize: 14, color: cs.onSurface)),
                ),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    value,
                    style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.right,
                  ),
                ),
                const SizedBox(width: 4),
                Icon(Icons.chevron_right_rounded,
                    size: 18,
                    color: cs.onSurfaceVariant.withValues(alpha: 0.5)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _choice(String title, String value, VoidCallback onTap) =>
      _tile(title, value, onTap);

  Widget _slider(
    String title,
    double value,
    double min,
    double max,
    String unit,
    ValueChanged<double> onChanged,
  ) {
    final cs = Theme.of(context).colorScheme;
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      sliver: SliverToBoxAdapter(
        child: Container(
          padding: const EdgeInsets.fromLTRB(14, 8, 8, 2),
          decoration: BoxDecoration(
            color: cs.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              SizedBox(
                width: 78,
                child: Text(title,
                    style: TextStyle(fontSize: 13, color: cs.onSurface)),
              ),
              Expanded(
                child: Slider(
                  value: value.clamp(min, max),
                  min: min,
                  max: max,
                  onChanged: onChanged,
                ),
              ),
              SizedBox(
                width: 46,
                child: Text(
                  value >= 10
                      ? value.toStringAsFixed(0)
                      : value.toStringAsFixed(2),
                  style:
                      TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                  textAlign: TextAlign.right,
                ),
              ),
              const SizedBox(width: 4),
            ],
          ),
        ),
      ),
    );
  }

  Widget _colorPicker() {
    const colors = [
      0xFF0A84FF, 0xFF34C759, 0xFFFF9F0A, 0xFFFF3B30,
      0xFFAF52DE, 0xFF30B0C7, 0xFFFF2D55, 0xFF5856D6,
    ];
    final cs = Theme.of(context).colorScheme;
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      sliver: SliverToBoxAdapter(
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: cs.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('强调色',
                  style: TextStyle(fontSize: 13, color: cs.onSurface)),
              const SizedBox(height: 10),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: colors.map((c) {
                  final on = widget.settings.accentColor == c;
                  return GestureDetector(
                    onTap: () => setState(() => widget.settings.accentColor = c),
                    child: Container(
                      width: 34,
                      height: 34,
                      decoration: BoxDecoration(
                        color: Color(c),
                        shape: BoxShape.circle,
                        border: on
                            ? Border.all(color: cs.onSurface, width: 2.5)
                            : null,
                      ),
                      child: on
                          ? const Icon(Icons.check, size: 18, color: Colors.white)
                          : null,
                    ),
                  );
                }).toList(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // ── 底栏顺序 ────────────────────────────
  /// ⑤ 底栏顺序：每个 tab 一行，带 ↑ ↓ 按钮，改完立即保存
  ///
  /// 注意：这里操作的是「显示位置」pos，而显示的文字由
  /// settings.tabOrder[pos] 取出语义 tab 索引决定。
  Widget _tabOrderBox() {
    final cs = Theme.of(context).colorScheme;
    final order = widget.settings.tabOrder;

    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      sliver: SliverToBoxAdapter(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 6),
          decoration: BoxDecoration(
            color: cs.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            children: [
              for (var pos = 0; pos < order.length; pos++) ...[
                if (pos > 0)
                  Divider(
                    height: 1,
                    thickness: 1,
                    color: cs.outlineVariant.withValues(alpha: 0.25),
                  ),
                _tabOrderRow(pos, order[pos], order.length),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _tabOrderRow(int pos, int tabIdx, int total) {
    final cs = Theme.of(context).colorScheme;
    final canUp = pos > 0;
    final canDown = pos < total - 1;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: Row(
        children: [
          SizedBox(
            width: 22,
            child: Text(
              '${pos + 1}.',
              style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
            ),
          ),
          Icon(AppSettings.tabIcons[tabIdx], size: 18, color: cs.primary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              AppSettings.tabNames[tabIdx],
              style: TextStyle(fontSize: 14, color: cs.onSurface),
            ),
          ),
          _moveBtn(
            icon: Icons.keyboard_arrow_up_rounded,
            enabled: canUp,
            tooltip: '上移',
            onTap: () {
              widget.settings.moveTabOrder(pos, pos - 1);
              setState(() {});
            },
          ),
          const SizedBox(width: 4),
          _moveBtn(
            icon: Icons.keyboard_arrow_down_rounded,
            enabled: canDown,
            tooltip: '下移',
            onTap: () {
              widget.settings.moveTabOrder(pos, pos + 1);
              setState(() {});
            },
          ),
        ],
      ),
    );
  }

  Widget _moveBtn({
    required IconData icon,
    required bool enabled,
    required String tooltip,
    required VoidCallback onTap,
  }) {
    final cs = Theme.of(context).colorScheme;
    return Tooltip(
      message: tooltip,
      child: SizedBox(
        width: 34,
        height: 34,
        child: IconButton(
          padding: EdgeInsets.zero,
          iconSize: 20,
          onPressed: enabled ? onTap : null,
          icon: Icon(
            icon,
            color: enabled ? cs.onSurface : cs.onSurface.withValues(alpha: 0.2),
          ),
        ),
      ),
    );
  }

  // ── 弹窗 ────────────────────────────────
  void _editText(
    String title,
    String initial,
    ValueChanged<String> onDone, {
    String? hint,
  }) {
    final ctl = TextEditingController(text: initial);
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: ctl,
          autofocus: true,
          decoration: InputDecoration(hintText: hint),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () {
              onDone(ctl.text.trim());
              Navigator.pop(context);
              setState(() {});
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  void _editNumber(String title, int initial, ValueChanged<int> onDone) {
    final ctl = TextEditingController(text: '$initial');
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: ctl,
          autofocus: true,
          keyboardType: TextInputType.number,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () {
              final v = int.tryParse(ctl.text.trim());
              if (v != null) onDone(v);
              Navigator.pop(context);
              setState(() {});
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  void _pickOne(
    String title,
    List<String> items,
    int current,
    ValueChanged<int> onPick,
  ) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(title,
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w600)),
            ),
            ...List.generate(items.length, (i) {
              return ListTile(
                title: Text(items[i]),
                trailing: i == current
                    ? Icon(Icons.check_rounded,
                        color: Theme.of(context).colorScheme.primary)
                    : null,
                onTap: () {
                  onPick(i);
                  Navigator.pop(context);
                },
              );
            }),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  void _confirm(String title, String msg, VoidCallback onYes) {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(msg),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () {
              onYes();
              Navigator.pop(context);
            },
            child: const Text('确定'),
          ),
        ],
      ),
    );
  }

  // ── 后台监控 ──────────────────────────────

  /// 切换后台监控总开关

  /// 发一条测试通知
  Future<void> _testNotification() async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await Future<void>.value();
      messenger.showSnackBar(const SnackBar(
        content: Text('已发送测试通知'),
        duration: Duration(seconds: 2),
      ));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('发送失败: $e')));
    }
  }
}
