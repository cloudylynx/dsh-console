import 'dart:async';

import 'package:flutter/foundation.dart';

import '../api/api_client.dart';
import '../models/snapshot.dart';
import '../settings.dart';

/// 一个数据点（用于折线图）
class ChartPoint {
  const ChartPoint(this.v1, [this.v2 = 0]);
  final double v1;
  final double v2;
}

/// 全局状态：轮询 /progress，维护历史曲线
class ConsoleState extends ChangeNotifier {
  ConsoleState(this.settings) {
    _rebuildClient();
  }

  final AppSettings settings;

  ApiClient? _api;
  ApiClient? get api => _api;

  Snapshot snapshot = Snapshot.empty();
  int latencyMs = 0;
  String? error;
  bool loading = false;

  /// 历史曲线（最多 settings.chartPoints 个点）
  final List<ChartPoint> cpuHistory = [];
  final List<ChartPoint> memHistory = [];
  /// 磁盘 IO：v1=读, v2=写
  final List<ChartPoint> diskIOHistory = [];
  /// 网速：v1=下行, v2=上行
  final List<ChartPoint> netHistory = [];

  Timer? _timer;
  bool _inFlight = false;

  void _rebuildClient() {
    _api = ApiClient(host: settings.host, port: settings.port);
  }

  /// 服务器地址变了 → 重建 client 并立即刷新
  void onServerChanged() {
    _rebuildClient();
    refresh();
  }

  void start() {
    _timer?.cancel();
    refresh();
    _timer = Timer.periodic(
      Duration(seconds: settings.interval),
      (_) => refresh(),
    );
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
  }

  Future<void> refresh() async {
    final a = _api;
    if (a == null || _inFlight) return;
    _inFlight = true;
    if (!loading) {
      loading = true;
      notifyListeners();
    }
    try {
      final r = await a.fetchProgress();
      snapshot = r.snap;
      latencyMs = r.latencyMs;
      error = null;
      _appendHistory();
    } catch (e) {
      error = _friendlyError(e);
    } finally {
      loading = false;
      _inFlight = false;
      notifyListeners();
    }
  }

  String _friendlyError(Object e) {
    final s = e.toString();
    if (s.contains('TimeoutException')) return '超时（服务器没响应）';
    if (s.contains('SocketException')) return '连不上服务器';
    if (s.contains('HTTP')) return s.replaceAll('HttpException: ', '');
    return s.length > 60 ? '${s.substring(0, 60)}…' : s;
  }

  void _appendHistory() {
    final sys = snapshot.system;
    final cap = settings.chartPoints;

    void push(List<ChartPoint> list, ChartPoint p) {
      list.add(p);
      while (list.length > cap) {
        list.removeAt(0);
      }
    }

    push(cpuHistory, ChartPoint(sys.cpu.toDouble()));
    push(memHistory, ChartPoint(sys.mem.toDouble()));
    push(diskIOHistory, ChartPoint(
      sys.diskReadSpeed / 1048576.0,
      sys.diskWriteSpeed / 1048576.0,
    ));
    push(netHistory, ChartPoint(
      sys.netRxSpeed / 1024.0,
      sys.netTxSpeed / 1024.0,
    ));
  }

  /// 标记消息已读
  Future<void> markRead(String id) async {
    final a = _api;
    if (a == null) return;
    final ok = await a.markRead(id);
    if (ok) {
      snapshot = Snapshot(
        ts: snapshot.ts,
        tasks: snapshot.tasks,
        system: snapshot.system,
        alerts: snapshot.alerts,
        messages: snapshot.messages
            .map((m) => m.id == id
                ? MessageItem(
                    id: m.id,
                    title: m.title,
                    body: m.body,
                    from: m.from,
                    ts: m.ts,
                    read: true,
                  )
                : m)
            .toList(),
        daemons: snapshot.daemons,
      );
      notifyListeners();
    }
  }

  @override
  void dispose() {
    stop();
    super.dispose();
  }
}

/// 磁盘/网速格式化
String fmtSpeed(num bytesPerSec) {
  if (bytesPerSec < 1024) return '${bytesPerSec.toStringAsFixed(0)} B/s';
  if (bytesPerSec < 1024 * 1024) {
    return '${(bytesPerSec / 1024).toStringAsFixed(1)} KB/s';
  }
  return '${(bytesPerSec / 1048576).toStringAsFixed(1)} MB/s';
}

String fmtMB(double mb) {
  if (mb < 1) return '${(mb * 1024).toStringAsFixed(0)} KB/s';
  return '${mb.toStringAsFixed(1)} MB/s';
}
