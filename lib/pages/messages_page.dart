import 'package:flutter/material.dart';

import '../models/snapshot.dart';
import '../state/console_state.dart';

/// 消息页：消息列表
class MessagesPage extends StatelessWidget {
  const MessagesPage({
    super.key,
    required this.state,
    required this.bottomReserve,
    required this.onClose,
  });

  final ConsoleState state;
  final double bottomReserve;

  /// 关闭消息页（返回首页）
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final list = state.snapshot.messages;

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 56, 16, 8),
          sliver: SliverToBoxAdapter(
            child: Row(
              children: [
                // ★返回按钮
                GestureDetector(
                  onTap: onClose,
                  child: Container(
                    width: 38,
                    height: 38,
                    margin: const EdgeInsets.only(right: 10),
                    decoration: BoxDecoration(
                      color: cs.surfaceContainerHigh,
                      shape: BoxShape.circle,
                    ),
                    child: Icon(Icons.arrow_back_rounded,
                        size: 20, color: cs.onSurface),
                  ),
                ),
                Expanded(
                  child: Text('消息',
                      style: TextStyle(
                          fontSize: 26,
                          fontWeight: FontWeight.w700,
                          color: cs.onSurface)),
                ),
                if (list.isNotEmpty)
                  Text('${list.where((m) => !m.read).length} 条未读',
                      style: TextStyle(
                          fontSize: 12, color: cs.onSurfaceVariant)),
              ],
            ),
          ),
        ),
        if (list.isEmpty)
          SliverFillRemaining(
            hasScrollBody: false,
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.inbox_rounded,
                      size: 48,
                      color: cs.onSurfaceVariant.withValues(alpha: 0.4)),
                  const SizedBox(height: 12),
                  Text('暂无消息',
                      style: TextStyle(
                          fontSize: 14, color: cs.onSurfaceVariant)),
                ],
              ),
            ),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            sliver: SliverList.builder(
              itemCount: list.length,
              itemBuilder: (context, i) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _messageCard(context, list[i]),
              ),
            ),
          ),
        SliverToBoxAdapter(child: SizedBox(height: bottomReserve)),
      ],
    );
  }

  Widget _messageCard(BuildContext context, MessageItem m) {
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: () {
        if (!m.read) state.markRead(m.id);
        _showDetail(context, m);
      },
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: m.read
              ? cs.surfaceContainerHigh.withValues(alpha: 0.6)
              : cs.surfaceContainerHigh,
          borderRadius: BorderRadius.circular(14),
          border: m.read
              ? null
              : Border.all(color: cs.primary.withValues(alpha: 0.35), width: 1),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                if (!m.read)
                  Container(
                    width: 7,
                    height: 7,
                    margin: const EdgeInsets.only(right: 6),
                    decoration: BoxDecoration(
                      color: cs.primary,
                      shape: BoxShape.circle,
                    ),
                  ),
                Expanded(
                  child: Text(
                    m.title,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: m.read ? FontWeight.w400 : FontWeight.w600,
                      color: cs.onSurface,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                Text(
                  _ago(m.ts),
                  style: TextStyle(fontSize: 10, color: cs.onSurfaceVariant),
                ),
              ],
            ),
            if (m.body.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                m.body,
                style: TextStyle(
                    fontSize: 12,
                    height: 1.45,
                    color: cs.onSurfaceVariant),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ],
            const SizedBox(height: 6),
            Row(
              children: [
                Icon(Icons.person_rounded,
                    size: 11, color: cs.onSurfaceVariant.withValues(alpha: 0.6)),
                const SizedBox(width: 3),
                Text(m.from,
                    style: TextStyle(
                        fontSize: 10,
                        color: cs.onSurfaceVariant.withValues(alpha: 0.6))),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _showDetail(BuildContext context, MessageItem m) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) {
        final cs = Theme.of(context).colorScheme;
        return DraggableScrollableSheet(
          expand: false,
          initialChildSize: 0.5,
          maxChildSize: 0.9,
          builder: (context, controller) => ListView(
            controller: controller,
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
            children: [
              Text(m.title,
                  style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface)),
              const SizedBox(height: 6),
              Text('${m.from} · ${_fullTime(m.ts)}',
                  style: TextStyle(
                      fontSize: 11, color: cs.onSurfaceVariant)),
              const SizedBox(height: 16),
              SelectableText(
                m.body,
                style: TextStyle(
                    fontSize: 14, height: 1.6, color: cs.onSurface),
              ),
            ],
          ),
        );
      },
    );
  }

  /// 相对时间
  static String _ago(int ts) {
    if (ts <= 0) return '';
    final diff = DateTime.now().millisecondsSinceEpoch - ts;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return '${diff ~/ 60000} 分钟前';
    if (diff < 86400000) return '${diff ~/ 3600000} 小时前';
    if (diff < 604800000) return '${diff ~/ 86400000} 天前';
    final d = DateTime.fromMillisecondsSinceEpoch(ts);
    return '${d.month}-${d.day}';
  }

  static String _fullTime(int ts) {
    if (ts <= 0) return '';
    final d = DateTime.fromMillisecondsSinceEpoch(ts);
    String two(int v) => v.toString().padLeft(2, '0');
    return '${d.year}-${two(d.month)}-${two(d.day)} '
        '${two(d.hour)}:${two(d.minute)}';
  }
}
