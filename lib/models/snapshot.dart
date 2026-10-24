/// /progress 接口的数据模型
///
/// 对应服务端 progress-server.js 的返回结构
library;

class Snapshot {
  Snapshot({
    required this.ts,
    required this.tasks,
    required this.system,
    required this.alerts,
    required this.messages,
    required this.daemons,
  });

  final int ts;
  final List<TaskItem> tasks;
  final SystemInfo system;
  final List<AlertItem> alerts;
  final List<MessageItem> messages;
  final List<DaemonItem> daemons;

  /// 当前正在跑的任务（第一个 running/queued）
  TaskItem? get activeTask {
    for (final t in tasks) {
      if (t.isActive) return t;
    }
    return null;
  }

  bool get isBusy => activeTask != null;

  int get unreadCount => messages.where((m) => !m.read).length;

  factory Snapshot.empty() => Snapshot(
        ts: 0,
        tasks: const [],
        system: SystemInfo.empty(),
        alerts: const [],
        messages: const [],
        daemons: const [],
      );

  factory Snapshot.fromJson(Map<String, dynamic> json) {
    return Snapshot(
      ts: _int(json['ts']),
      tasks: _list(json['tasks'], TaskItem.fromJson),
      system: SystemInfo.fromJson(_map(json['system'])),
      alerts: _list(json['alerts'], AlertItem.fromJson),
      messages: _list(json['messages'], MessageItem.fromJson),
      daemons: _list(json['daemons'], DaemonItem.fromJson),
    );
  }
}

class TaskItem {
  const TaskItem({
    required this.id,
    required this.name,
    required this.state,
    required this.detail,
    required this.progress,
    required this.speed,
    required this.eta,
  });

  final String id;
  final String name;
  final String state;
  final String detail;
  final double progress;
  final String speed;
  final String eta;

  bool get isActive => state == 'running' || state == 'queued';

  factory TaskItem.fromJson(Map<String, dynamic> j) => TaskItem(
        id: _str(j['id']),
        name: _str(j['name']),
        state: _str(j['state'], 'idle'),
        detail: _str(j['detail']),
        progress: _dbl(j['progress']),
        speed: _str(j['speed']),
        eta: _str(j['eta']),
      );
}

class SystemInfo {
  const SystemInfo({
    required this.cpu,
    required this.cpuCores,
    required this.cpuModel,
    required this.load1,
    required this.load5,
    required this.cpuTemp,
    required this.mem,
    required this.memTotal,
    required this.memUsed,
    required this.memFree,
    required this.swapTotal,
    required this.swapFree,
    required this.disk,
    required this.diskFree,
    required this.tailscale,
    required this.uptime,
    required this.host,
    required this.netRxSpeed,
    required this.netTxSpeed,
    required this.netIface,
    required this.diskReadSpeed,
    required this.diskWriteSpeed,
    required this.diskIface,
  });

  final int cpu;
  final int cpuCores;
  final String cpuModel;
  final double load1;
  final double load5;
  final int cpuTemp;
  final int mem;
  final String memTotal;
  final String memUsed;
  final String memFree;
  final String swapTotal;
  final String swapFree;
  final int disk;
  final String diskFree;
  final bool tailscale;
  final String uptime;
  final String host;
  final int netRxSpeed;
  final int netTxSpeed;
  final String netIface;
  final int diskReadSpeed;
  final int diskWriteSpeed;
  final String diskIface;

  factory SystemInfo.empty() => const SystemInfo(
        cpu: 0,
        cpuCores: 0,
        cpuModel: '',
        load1: 0,
        load5: 0,
        cpuTemp: -1,
        mem: 0,
        memTotal: '',
        memUsed: '',
        memFree: '',
        swapTotal: '',
        swapFree: '',
        disk: 0,
        diskFree: '',
        tailscale: false,
        uptime: '',
        host: '',
        netRxSpeed: 0,
        netTxSpeed: 0,
        netIface: '',
        diskReadSpeed: 0,
        diskWriteSpeed: 0,
        diskIface: '',
      );

  factory SystemInfo.fromJson(Map<String, dynamic> j) => SystemInfo(
        cpu: _int(j['cpu']),
        cpuCores: _int(j['cpuCores']),
        cpuModel: _str(j['cpuModel']),
        load1: _dbl(j['load1']),
        load5: _dbl(j['load5']),
        cpuTemp: j['cpuTemp'] == null ? -1 : _int(j['cpuTemp']),
        mem: _int(j['mem']),
        memTotal: _str(j['memTotal']),
        memUsed: _str(j['memUsed']),
        memFree: _str(j['memFree']),
        swapTotal: _str(j['swapTotal']),
        swapFree: _str(j['swapFree']),
        disk: _int(j['disk']),
        diskFree: _str(j['diskFree']),
        tailscale: j['tailscale'] == true,
        uptime: _str(j['uptime']),
        host: _str(j['host']),
        netRxSpeed: _int(j['netRxSpeed']),
        netTxSpeed: _int(j['netTxSpeed']),
        netIface: _str(j['netIface']),
        diskReadSpeed: _int(j['diskReadSpeed']),
        diskWriteSpeed: _int(j['diskWriteSpeed']),
        diskIface: _str(j['diskIface']),
      );
}

class AlertItem {
  const AlertItem({
    required this.id,
    required this.level,
    required this.title,
    required this.detail,
    required this.ts,
  });

  final String id;
  final String level;
  final String title;
  final String detail;
  final int ts;

  factory AlertItem.fromJson(Map<String, dynamic> j) => AlertItem(
        id: _str(j['id']),
        level: _str(j['level'], 'info'),
        title: _str(j['title']),
        detail: _str(j['detail']),
        ts: _int(j['ts']),
      );
}

class MessageItem {
  const MessageItem({
    required this.id,
    required this.title,
    required this.body,
    required this.from,
    required this.ts,
    required this.read,
  });

  final String id;
  final String title;
  final String body;
  final String from;
  final int ts;
  final bool read;

  factory MessageItem.fromJson(Map<String, dynamic> j) => MessageItem(
        id: _str(j['id']),
        title: _str(j['title']),
        body: _str(j['body']),
        from: _str(j['from']),
        ts: _int(j['ts']),
        read: j['read'] == true,
      );
}

class DaemonItem {
  const DaemonItem({
    required this.name,
    required this.label,
    required this.active,
    required this.enabled,
    required this.healthy,
    required this.critical,
    required this.restarts,
  });

  final String name;
  final String label;
  final String active;
  final String enabled;
  final bool healthy;
  final bool critical;
  final int restarts;

  factory DaemonItem.fromJson(Map<String, dynamic> j) => DaemonItem(
        name: _str(j['name']),
        label: _str(j['label']),
        active: _str(j['active']),
        enabled: _str(j['enabled']),
        healthy: j['healthy'] == true,
        critical: j['critical'] == true,
        restarts: _int(j['restarts']),
      );
}

// ── 解析辅助 ──────────────────────────────────────

String _str(dynamic v, [String def = '']) =>
    v == null ? def : v.toString();

int _int(dynamic v) {
  if (v == null) return 0;
  if (v is int) return v;
  if (v is double) return v.toInt();
  return int.tryParse(v.toString()) ?? 0;
}

double _dbl(dynamic v) {
  if (v == null) return 0;
  if (v is double) return v;
  if (v is int) return v.toDouble();
  return double.tryParse(v.toString()) ?? 0;
}

Map<String, dynamic> _map(dynamic v) =>
    v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};

List<T> _list<T>(dynamic v, T Function(Map<String, dynamic>) f) {
  if (v is! List) return <T>[];
  return v
      .whereType<Map>()
      .map((e) => f(Map<String, dynamic>.from(e)))
      .toList();
}
