import 'package:flutter/material.dart';

import '../state/console_state.dart';
import '../theme.dart';
import '../widgets/spark_chart.dart';
import 'diagnose_sheet.dart';

/// 首页：主机状态仪表盘
class HomePage extends StatelessWidget {
  const HomePage({
    super.key,
    required this.state,
    required this.bottomReserve,
    required this.onShowMessages,
    required this.onShowCommands,
    required this.onShowLogs,
  });

  final ConsoleState state;
  final double bottomReserve;
  final VoidCallback onShowMessages;
  final VoidCallback onShowCommands;
  final VoidCallback onShowLogs;

  @override
  Widget build(BuildContext context) {
    // ★平板判定：可用宽度 >= 700（侧边栏占 88 后仍宽）
    final wide = MediaQuery.of(context).size.width >= 700;

    return CustomScrollView(
      slivers: [
        // ══════ 顶栏（标题 + 状态 + 消息）══════
        SliverPadding(
          padding: EdgeInsets.fromLTRB(wide ? 24 : 16, wide ? 20 : 48,
              wide ? 24 : 16, 8),
          sliver: SliverToBoxAdapter(child: _header(context, wide)),
        ),

        // ══════ 告警条 ══════
        if (state.snapshot.alerts.isNotEmpty)
          SliverPadding(
            padding: EdgeInsets.symmetric(horizontal: wide ? 24 : 16),
            sliver: SliverToBoxAdapter(child: _alertBar(context)),
          ),

        // ══════ 主内容 ══════
        SliverPadding(
          padding: EdgeInsets.fromLTRB(wide ? 24 : 16, 12, wide ? 24 : 16, 0),
          sliver: SliverToBoxAdapter(
            child: wide ? _layoutWide(context) : _layoutNarrow(context),
          ),
        ),

        SliverToBoxAdapter(child: SizedBox(height: bottomReserve)),
      ],
    );
  }

  // ── 顶栏 ────────────────────────────────
  Widget _header(BuildContext context, bool wide) {
    final cs = Theme.of(context).colorScheme;
    final sys = state.snapshot.system;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('中控台',
            style: titleStyle(size: wide ? 30 : 26, color: cs.onSurface)),
        const SizedBox(height: 4),
        Row(
          children: [
            Expanded(
              child: Text(
                '${sys.host.isEmpty ? '正在连接…' : sys.host}'
                '${sys.tailscale ? ' · Tailscale 在线' : ''}',
                style: TextStyle(
                    fontSize: wide ? 13 : 12, color: cs.onSurfaceVariant),
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (state.error != null)
              Text('⚠ ${state.error}',
                  style: TextStyle(fontSize: 11, color: cs.error))
            else
              Text('${state.latencyMs}ms',
                  style: TextStyle(
                      fontSize: wide ? 12 : 11, color: cs.onSurfaceVariant)),
          ],
        ),
      ],
    );
  }

  // ══════ 平板布局：三栏网格 ══════
  //
  //  ┌────────────┬────────────┬────────────┐
  //  │  当前任务   │  CPU 图    │  内存图     │
  //  │            ├────────────┼────────────┤
  //  │            │  磁盘IO    │  网速       │
  //  └────────────┴────────────┴────────────┘
  //  ┌──────┬──────┬──────┬──────┐
  //  │ 温度  │ SWAP │ 磁盘 │ 负载 │   ← 4 个小指标
  //  └──────┴──────┴──────┴──────┘
  //  ┌──────────────────────────────┐
  //  │  守护进程                     │
  //  └──────────────────────────────┘
  Widget _layoutWide(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 第一行：任务（占 1/3）+ 4 张图（占 2/3，2×2 网格）
        IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(flex: 4, child: _taskCard(context)),
              const SizedBox(width: 12),
              Expanded(
                flex: 7,
                child: Column(
                  children: [
                    Row(
                      children: [
                        Expanded(child: _chartCard(context, 'CPU',
                            '${state.snapshot.system.cpu}%',
                            const Color(0xFF0A84FF), state.cpuHistory,
                            fixedMax: 100)),
                        const SizedBox(width: 12),
                        Expanded(child: _chartCard(context, '内存',
                            '${state.snapshot.system.mem}%',
                            const Color(0xFF34C759), state.memHistory,
                            fixedMax: 100)),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Expanded(child: _chartCard(context, '磁盘 IO',
                            fmtMB(state.snapshot.system.diskReadSpeed /
                                1048576.0),
                            const Color(0xFFFF9F0A), state.diskIOHistory,
                            color2: const Color(0xFFFF3B30))),
                        const SizedBox(width: 12),
                        Expanded(child: _chartCard(context, '网速',
                            fmtSpeed(state.snapshot.system.netRxSpeed),
                            const Color(0xFFAF52DE), state.netHistory,
                            color2: const Color(0xFF30B0C7))),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),

        // 第二行：4 个小指标
        _miniRowWide(context),
        const SizedBox(height: 12),

        // 第三行：守护进程
        _daemonCard(context),
      ],
    );
  }

  // ══════ 手机布局：单列 ══════
  Widget _layoutNarrow(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _taskBlock(context),
        const SizedBox(height: 16),
        _chartsNarrow(context),
        const SizedBox(height: 10),
        _miniRow(context),
        const SizedBox(height: 16),
        _daemonCard(context),
      ],
    );
  }

  Widget _alertBar(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final a = state.snapshot.alerts.first;
    final isErr = a.level == 'error' || a.level == 'critical';
    final c = isErr ? cs.error : const Color(0xFFFF9F0A);
    return Container(
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: c.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: c.withValues(alpha: 0.35)),
      ),
      child: Row(
        children: [
          Icon(Icons.warning_amber_rounded, size: 16, color: c),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '${a.title}${a.detail.isEmpty ? '' : ' — ${a.detail}'}',
              style: TextStyle(fontSize: 12, color: c),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (state.snapshot.alerts.length > 1)
            Text('+${state.snapshot.alerts.length - 1}',
                style: TextStyle(fontSize: 11, color: c)),
        ],
      ),
    );
  }

  Widget _taskBlock(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final active = state.snapshot.activeTask;

    if (active == null) {
      return Container(
        padding: const EdgeInsets.symmetric(vertical: 20),
        alignment: Alignment.center,
        child: Text('暂无运行中的任务',
            style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
      );
    }

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 7,
                height: 7,
                decoration: BoxDecoration(
                  color: active.state == 'running'
                      ? const Color(0xFF34C759)
                      : const Color(0xFFFF9F0A),
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 7),
              Expanded(
                child: Text(active.name,
                    style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: cs.onSurface)),
              ),
              Text('${(active.progress * 100).toStringAsFixed(0)}%',
                  style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: cs.primary)),
            ],
          ),
          if (active.detail.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(active.detail,
                style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                maxLines: 2,
                overflow: TextOverflow.ellipsis),
          ],
          const SizedBox(height: 8),
          ClipRRect(
            borderRadius: BorderRadius.circular(3),
            child: LinearProgressIndicator(
              value: active.progress.clamp(0.0, 1.0),
              minHeight: 5,
              backgroundColor: cs.surfaceContainerHighest,
            ),
          ),
          if (active.speed.isNotEmpty || active.eta.isNotEmpty) ...[
            const SizedBox(height: 5),
            Row(
              children: [
                if (active.speed.isNotEmpty)
                  Text(active.speed,
                      style: TextStyle(
                          fontSize: 10, color: cs.onSurfaceVariant)),
                const Spacer(),
                if (active.eta.isNotEmpty)
                  Text('剩余 ${active.eta}',
                      style: TextStyle(
                          fontSize: 10, color: cs.onSurfaceVariant)),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _chartsNarrow(BuildContext context) {
    return Column(
      children: [
        _chartCard(context, 'CPU', '${state.snapshot.system.cpu}%',
            const Color(0xFF0A84FF), state.cpuHistory, fixedMax: 100),
        const SizedBox(height: 10),
        _chartCard(context, '内存', '${state.snapshot.system.mem}%',
            const Color(0xFF34C759), state.memHistory, fixedMax: 100),
        const SizedBox(height: 10),
        _chartCard(context, '磁盘 IO',
            fmtMB(state.snapshot.system.diskReadSpeed / 1048576.0),
            const Color(0xFFFF9F0A), state.diskIOHistory,
            color2: const Color(0xFFFF3B30)),
        const SizedBox(height: 10),
        _chartCard(context, '网速',
            fmtSpeed(state.snapshot.system.netRxSpeed),
            const Color(0xFFAF52DE), state.netHistory,
            color2: const Color(0xFF30B0C7)),
      ],
    );
  }

  Widget _chartCard(
    BuildContext context,
    String label,
    String value,
    Color color,
    List<ChartPoint> points, {
    Color? color2,
    double? fixedMax,
  }) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(label,
                    style: TextStyle(
                        fontSize: 12,
                        color: cs.onSurfaceVariant,
                        fontWeight: FontWeight.w500)),
              ),
              Text(value,
                  style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                      color: color)),
            ],
          ),
          const SizedBox(height: 6),
          SparkChart(
            points: points,
            color: color,
            color2: color2,
            fixedMax: fixedMax,
            height: 54,
          ),
        ],
      ),
    );
  }

  /// 平板版：任务卡片（带背景，和图表对齐）
  Widget _taskCard(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final active = state.snapshot.activeTask;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.task_alt_rounded,
                  size: 16, color: cs.onSurfaceVariant),
              const SizedBox(width: 6),
              Text('当前任务',
                  style: titleStyle(size: 15, color: cs.onSurface)),
            ],
          ),
          const SizedBox(height: 12),
          if (active == null)
            Expanded(
              child: Center(
                child: Text('暂无运行中的任务',
                    style: TextStyle(
                        fontSize: 13, color: cs.onSurfaceVariant)),
              ),
            )
          else
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Row(
                    children: [
                      Container(
                        width: 7,
                        height: 7,
                        decoration: BoxDecoration(
                          color: active.state == 'running'
                              ? const Color(0xFF34C759)
                              : const Color(0xFFFF9F0A),
                          shape: BoxShape.circle,
                        ),
                      ),
                      const SizedBox(width: 7),
                      Expanded(
                        child: Text(active.name,
                            style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                                color: cs.onSurface),
                            overflow: TextOverflow.ellipsis),
                      ),
                      Text('${(active.progress * 100).toStringAsFixed(0)}%',
                          style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                              color: cs.primary)),
                    ],
                  ),
                  if (active.detail.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(active.detail,
                        style: TextStyle(
                            fontSize: 11, color: cs.onSurfaceVariant),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis),
                  ],
                  const SizedBox(height: 10),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(3),
                    child: LinearProgressIndicator(
                      value: active.progress.clamp(0.0, 1.0),
                      minHeight: 6,
                      backgroundColor: cs.surfaceContainerHighest,
                    ),
                  ),
                  if (active.speed.isNotEmpty || active.eta.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        if (active.speed.isNotEmpty)
                          Text(active.speed,
                              style: TextStyle(
                                  fontSize: 10,
                                  color: cs.onSurfaceVariant)),
                        const Spacer(),
                        if (active.eta.isNotEmpty)
                          Text('剩余 ${active.eta}',
                              style: TextStyle(
                                  fontSize: 10,
                                  color: cs.onSurfaceVariant)),
                      ],
                    ),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }

  /// 平板版：4 个小指标（温度/负载/SWAP/磁盘）
  Widget _miniRowWide(BuildContext context) {
    final sys = state.snapshot.system;
    return Row(
      children: [
        Expanded(
            child: _miniCard(context, '温度',
                sys.cpuTemp < 0 ? '—' : '${sys.cpuTemp}°C')),
        const SizedBox(width: 10),
        Expanded(
            child: _miniCard(context, '负载',
                '${sys.load1.toStringAsFixed(2)} / ${sys.load5.toStringAsFixed(2)}')),
        const SizedBox(width: 10),
        Expanded(
            child: _miniCard(context, 'SWAP 空闲',
                sys.swapFree.isEmpty ? '—' : sys.swapFree)),
        const SizedBox(width: 10),
        Expanded(
            child: _miniCard(context, '磁盘可用',
                sys.diskFree.isEmpty ? '—' : sys.diskFree)),
      ],
    );
  }

  Widget _miniRow(BuildContext context) {
    final sys = state.snapshot.system;
    return Row(
      children: [
        Expanded(
            child: _miniCard(context, '温度',
                sys.cpuTemp < 0 ? '—' : '${sys.cpuTemp}°C')),
        const SizedBox(width: 8),
        Expanded(
            child: _miniCard(context, 'SWAP 空闲', sys.swapFree.isEmpty ? '—' : sys.swapFree)),
        const SizedBox(width: 8),
        Expanded(
            child: _miniCard(context, '磁盘可用',
                sys.diskFree.isEmpty ? '—' : sys.diskFree)),
      ],
    );
  }

  Widget _miniCard(BuildContext context, String label, String value) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant)),
          const SizedBox(height: 3),
          Text(value,
              style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w700,
                  color: cs.onSurface)),
        ],
      ),
    );
  }

  Widget _daemonCard(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final list = state.snapshot.daemons;
    final okCount = list.where((d) => d.healthy).length;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text('守护进程',
                    style: titleStyle(size: 15, color: cs.onSurface)),
              ),
              Text('$okCount/${list.length} 正常',
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
            ],
          ),
          const SizedBox(height: 8),
          if (list.isEmpty)
            Text('无数据', style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant))
          else
            ...list.map((d) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(
                    children: [
                      Icon(
                        d.healthy
                            ? Icons.check_circle_rounded
                            : Icons.error_rounded,
                        size: 13,
                        color: d.healthy
                            ? const Color(0xFF34C759)
                            : (d.critical ? cs.error : const Color(0xFFFF9F0A)),
                      ),
                      const SizedBox(width: 7),
                      Expanded(
                        child: Text(
                          d.label.isEmpty ? d.name : d.label,
                          style: TextStyle(fontSize: 11.5, color: cs.onSurface),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      Text(
                        d.active,
                        style: TextStyle(
                            fontSize: 10, color: cs.onSurfaceVariant),
                      ),
                      if (d.restarts > 0) ...[
                        const SizedBox(width: 6),
                        Text('重启${d.restarts}',
                            style: TextStyle(
                                fontSize: 9, color: cs.error)),
                      ],
                    ],
                  ),
                )),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: FilledButton.tonalIcon(
                  onPressed: onShowCommands,
                  icon: const Icon(Icons.bolt_rounded, size: 16),
                  label: const Text('快捷命令',
                      style: TextStyle(fontSize: 12)),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: FilledButton.tonalIcon(
                  onPressed: onShowLogs,
                  icon: const Icon(Icons.article_rounded, size: 16),
                  label: const Text('实时日志',
                      style: TextStyle(fontSize: 12)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: FilledButton.tonalIcon(
              onPressed: () => _showDiagnose(context),
              icon: const Icon(Icons.troubleshoot_rounded, size: 16),
              label: const Text('一键诊断', style: TextStyle(fontSize: 12)),
            ),
          ),
        ],
      ),
    );
  }

  /// 打开一键诊断面板
  void _showDiagnose(BuildContext context) {
    DiagnoseSheet.show(context, state);
  }
}
