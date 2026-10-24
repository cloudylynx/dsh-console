import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../models/snapshot.dart';

/// 后端 API 客户端
///
/// 服务端：progress-server.js（端口 18800）
/// 路由：/progress /tasks /task /task/done /cmds /cmd /cmd/result
///       /messages /message /message/read /diagnose /health
class ApiClient {
  ApiClient({required this.host, required this.port});

  final String host;
  final int port;

  String get base => 'http://$host:$port';

  static const _timeout = Duration(seconds: 6);

  /// 拉取完整状态
  Future<({Snapshot snap, int latencyMs})> fetchProgress() async {
    final t0 = DateTime.now();
    final resp = await http
        .get(Uri.parse('$base/progress'),
            headers: {'Accept': 'application/json'})
        .timeout(_timeout);
    final ms = DateTime.now().difference(t0).inMilliseconds;
    if (resp.statusCode != 200) {
      throw HttpException('HTTP ${resp.statusCode}');
    }
    final json = jsonDecode(utf8.decode(resp.bodyBytes))
        as Map<String, dynamic>;
    return (snap: Snapshot.fromJson(json), latencyMs: ms);
  }

  /// 健康检查
  Future<bool> health() async {
    try {
      final r = await http
          .get(Uri.parse('$base/health'))
          .timeout(const Duration(seconds: 3));
      return r.statusCode == 200;
    } catch (_) {
      return false;
    }
  }

  /// 快捷命令列表
  ///
  /// 返回形如 [{"id":"...","name":"...","cmd":"...","confirm":false}, ...]
  Future<List<QuickCommand>> fetchCommands() async {
    final r = await http
        .get(Uri.parse('$base/cmds'),
            headers: {'Accept': 'application/json'})
        .timeout(_timeout);
    if (r.statusCode != 200) throw HttpException('HTTP ${r.statusCode}');
    final body = jsonDecode(utf8.decode(r.bodyBytes));
    // 服务端可能返回 {"commands":[...]} 或直接 [...]
    final list = body is List ? body : (body as Map)['commands'];
    if (list is! List) return [];
    return list
        .whereType<Map>()
        .map((e) => QuickCommand.fromJson(Map<String, dynamic>.from(e)))
        .toList();
  }

  /// 执行命令
  ///
  /// POST /cmd  body: {"cmd": "...", "id": "..."}
  /// 返回 run id（用于轮询 /cmd/result）
  Future<String?> runCommand(String cmd, {String? id}) async {
    final r = await http
        .post(
          Uri.parse('$base/cmd'),
          headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
          },
          body: jsonEncode({'cmd': cmd, 'id': ?id}),
        )
        .timeout(_timeout);
    if (r.statusCode != 200) throw HttpException('HTTP ${r.statusCode}');
    final m = jsonDecode(utf8.decode(r.bodyBytes));
    if (m is Map) {
      return (m['runId'] ?? m['id'] ?? m['run'])?.toString();
    }
    return null;
  }

  /// 取命令执行结果
  Future<CommandResult?> commandResult(String runId) async {
    final r = await http
        .get(Uri.parse('$base/cmd/result?runId=$runId'),
            headers: {'Accept': 'application/json'})
        .timeout(_timeout);
    if (r.statusCode != 200) return null;
    final m = jsonDecode(utf8.decode(r.bodyBytes));
    if (m is! Map) return null;
    return CommandResult.fromJson(Map<String, dynamic>.from(m));
  }

  /// 标记消息已读
  Future<bool> markRead(String messageId) async {
    try {
      final r = await http
          .post(
            Uri.parse('$base/message/read'),
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({'id': messageId}),
          )
          .timeout(_timeout);
      return r.statusCode == 200;
    } catch (_) {
      return false;
    }
  }

  /// 一键诊断
  Future<String> diagnose() async {
    final r = await http
        .get(Uri.parse('$base/diagnose'))
        .timeout(const Duration(seconds: 20));
    if (r.statusCode != 200) throw HttpException('HTTP ${r.statusCode}');
    return utf8.decode(r.bodyBytes);
  }

  /// 文件列表端口（18802）
  String get fileBase => 'http://$host:18802';

  /// 实时日志端口（18900，SSE）
  String get logBase => 'http://$host:18900';

  /// ttyd 终端地址（18801）
  String terminalUrl(String path) => 'http://$host:18801/$path';
}

class QuickCommand {
  const QuickCommand({
    required this.id,
    required this.name,
    required this.cmd,
    required this.confirm,
    this.desc = '',
  });

  final String id;
  final String name;
  final String cmd;
  final bool confirm;
  final String desc;

  factory QuickCommand.fromJson(Map<String, dynamic> j) => QuickCommand(
        id: (j['id'] ?? j['key'] ?? '').toString(),
        name: (j['name'] ?? j['label'] ?? j['title'] ?? '').toString(),
        cmd: (j['cmd'] ?? j['command'] ?? '').toString(),
        confirm: j['confirm'] == true,
        desc: (j['desc'] ?? j['detail'] ?? '').toString(),
      );
}

class CommandResult {
  const CommandResult({
    required this.done,
    required this.exitCode,
    required this.stdout,
    required this.stderr,
  });

  final bool done;
  final int? exitCode;
  final String stdout;
  final String stderr;

  factory CommandResult.fromJson(Map<String, dynamic> j) => CommandResult(
        done: j['done'] == true || j['state'] == 'done',
        exitCode: j['exit'] == null ? null : int.tryParse('${j['exit']}'),
        stdout: (j['stdout'] ?? j['out'] ?? '').toString(),
        stderr: (j['stderr'] ?? j['err'] ?? '').toString(),
      );
}
