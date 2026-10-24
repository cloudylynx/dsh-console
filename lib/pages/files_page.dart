import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:url_launcher/url_launcher.dart';

import '../theme.dart';

/// 文件页（原生列表）
///
/// 后端：文件服务（端口 18802）
///   GET /roots                   → {"roots":[{"name":"下载站","writable":true}, ...]}
///   GET /list?root=N&path=/xxx   → {"root","path","absPath","writable","items":[...],"count"}
///   GET /download?root=N&path=/xxx
///
/// 说明：
///   · 只做「浏览 + 下载」，**不提供删除/上传**（后端 /delete 不带 path 会删根目录，
///     风险过高，不在客户端暴露）。
///   · root 是 /roots 返回列表的下标，不是名字。
///   · 某个 root 目录不存在时后端返回 404 {"error":"ENOENT..."}，
///     这里转成友好的「目录不存在」提示，不影响其它 root。

// ─────────────────────────────────────────────────────────────
// API 层
// ─────────────────────────────────────────────────────────────

/// 白名单根目录
class FileRoot {
  const FileRoot({required this.index, required this.name, required this.writable});

  final int index;
  final String name;
  final bool writable;

  factory FileRoot.fromJson(int index, Map<String, dynamic> json) => FileRoot(
        index: index,
        name: (json['name'] as String?)?.trim().isNotEmpty == true
            ? json['name'] as String
            : '根目录 $index',
        writable: json['writable'] == true,
      );
}

/// 目录里的一个条目
class FileEntry {
  const FileEntry({
    required this.name,
    required this.isDir,
    required this.size,
    required this.sizeText,
    required this.mtime,
    required this.writable,
  });

  final String name;
  final bool isDir;
  final int size;

  /// 后端格式化好的大小（目录为空字符串）
  final String sizeText;

  /// 修改时间，后端给的是 epoch 毫秒（可能是小数）
  final DateTime? mtime;

  final bool writable;

  factory FileEntry.fromJson(Map<String, dynamic> json) {
    final rawMtime = json['mtime'];
    DateTime? mtime;
    if (rawMtime is num && rawMtime > 0) {
      mtime = DateTime.fromMillisecondsSinceEpoch(rawMtime.round());
    }
    return FileEntry(
      name: (json['name'] as String?) ?? '',
      isDir: json['isDir'] == true,
      size: (json['size'] as num?)?.toInt() ?? 0,
      sizeText: (json['sizeText'] as String?) ?? '',
      mtime: mtime,
      writable: json['writable'] == true,
    );
  }
}

/// 一次列目录的结果
class DirListing {
  const DirListing({
    required this.rootName,
    required this.path,
    required this.absPath,
    required this.writable,
    required this.items,
  });

  final String rootName;
  final String path;
  final String absPath;
  final bool writable;
  final List<FileEntry> items;

  factory DirListing.fromJson(Map<String, dynamic> json) => DirListing(
        rootName: (json['root'] as String?) ?? '',
        path: (json['path'] as String?) ?? '/',
        absPath: (json['absPath'] as String?) ?? '',
        writable: json['writable'] == true,
        items: ((json['items'] as List<dynamic>?) ?? const <dynamic>[])
            .whereType<Map<String, dynamic>>()
            .map(FileEntry.fromJson)
            .toList(growable: false),
      );
}

/// 文件服务异常（带「是不是目录不存在」的语义）
class FilesException implements Exception {
  FilesException(this.message, {this.missing = false});

  final String message;

  /// true = 目录/文件不存在（ENOENT），UI 上给更友好的提示
  final bool missing;

  @override
  String toString() => message;
}

/// 18802 文件服务的客户端
class FilesApi {
  FilesApi({required this.host, required this.port});

  final String host;
  final int port;

  static const Duration _timeout = Duration(seconds: 8);

  String get base => 'http://$host:$port';

  Future<Map<String, dynamic>> _getJson(String path,
      {Map<String, String>? query}) async {
    final uri = Uri.parse('$base$path').replace(queryParameters: query);
    final http.Response resp;
    try {
      resp = await http
          .get(uri, headers: const {'Accept': 'application/json'})
          .timeout(_timeout);
    } on Exception catch (e) {
      throw FilesException('连接失败：$e');
    }

    final body = utf8.decode(resp.bodyBytes, allowMalformed: true);

    // 后端错误统一是 {"error":"..."}
    Map<String, dynamic>? json;
    try {
      final decoded = jsonDecode(body);
      if (decoded is Map<String, dynamic>) json = decoded;
    } on FormatException {
      json = null;
    }

    if (resp.statusCode == 200 && json != null) return json;

    final err = (json?['error'] as String?) ?? body;
    final missing = resp.statusCode == 404 ||
        err.contains('ENOENT') ||
        err.contains('no such file');
    throw FilesException(
      missing ? '目录不存在' : (err.isEmpty ? 'HTTP ${resp.statusCode}' : err),
      missing: missing,
    );
  }

  /// 白名单根目录
  Future<List<FileRoot>> fetchRoots() async {
    final json = await _getJson('/roots');
    final raw = (json['roots'] as List<dynamic>?) ?? const <dynamic>[];
    final out = <FileRoot>[];
    for (var i = 0; i < raw.length; i++) {
      final item = raw[i];
      if (item is Map<String, dynamic>) out.add(FileRoot.fromJson(i, item));
    }
    if (out.isEmpty) throw FilesException('后端没有返回任何根目录');
    return out;
  }

  /// 列目录
  Future<DirListing> list({required int root, required String path}) async {
    final json = await _getJson('/list', query: {
      'root': '$root',
      'path': path,
    });
    return DirListing.fromJson(json);
  }

  /// 下载直链（交给系统浏览器/下载器）
  Uri downloadUri({required int root, required String path}) =>
      Uri.parse('$base/download').replace(queryParameters: {
        'root': '$root',
        'path': path,
      });
}

// ─────────────────────────────────────────────────────────────
// 页面
// ─────────────────────────────────────────────────────────────

/// 文件页：浏览白名单根目录 + 点击下载
class FilesPage extends StatefulWidget {
  const FilesPage({
    super.key,
    required this.host,
    this.port = 18802,
    this.bottomReserve = 90,
  });

  final String host;
  final int port;

  /// 底部留白（给悬浮底栏让位）
  final double bottomReserve;

  @override
  State<FilesPage> createState() => _FilesPageState();
}

class _FilesPageState extends State<FilesPage> {
  late FilesApi _api;

  List<FileRoot> _roots = const [];
  int _rootIndex = 0;
  String _path = '/';

  List<FileEntry> _items = const [];
  String _absPath = '';

  bool _loadingRoots = true;
  bool _loadingDir = false;
  String? _rootsError;
  String? _dirError;
  bool _dirMissing = false;

  /// 防止旧请求覆盖新结果
  int _reqSeq = 0;

  @override
  void initState() {
    super.initState();
    _api = FilesApi(host: widget.host, port: widget.port);
    _loadRoots();
  }

  @override
  void didUpdateWidget(covariant FilesPage old) {
    super.didUpdateWidget(old);
    if (old.host != widget.host || old.port != widget.port) {
      _api = FilesApi(host: widget.host, port: widget.port);
      _path = '/';
      _loadRoots();
    }
  }

  // ── 数据加载 ──────────────────────────────────

  Future<void> _loadRoots() async {
    setState(() {
      _loadingRoots = true;
      _rootsError = null;
    });
    try {
      final roots = await _api.fetchRoots();
      if (!mounted) return;
      setState(() {
        _roots = roots;
        if (_rootIndex >= roots.length) _rootIndex = 0;
        _loadingRoots = false;
      });
      await _loadDir(showSpinner: true);
    } on FilesException catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingRoots = false;
        _rootsError = e.message;
        _items = const [];
      });
    }
  }

  Future<void> _loadDir({bool showSpinner = false}) async {
    final seq = ++_reqSeq;
    if (showSpinner) {
      setState(() {
        _loadingDir = true;
        _dirError = null;
        _dirMissing = false;
      });
    }

    try {
      final listing =
          await _api.list(root: _rootIndex, path: _path);
      if (!mounted || seq != _reqSeq) return;
      setState(() {
        _items = listing.items;
        _absPath = listing.absPath;
        _loadingDir = false;
        _dirError = null;
        _dirMissing = false;
      });
    } on FilesException catch (e) {
      if (!mounted || seq != _reqSeq) return;
      setState(() {
        _loadingDir = false;
        _items = const [];
        _absPath = '';
        _dirError = e.message;
        _dirMissing = e.missing;
      });
    }
  }

  // ── 导航 ──────────────────────────────────────

  void _selectRoot(int index) {
    if (index == _rootIndex) return;
    setState(() {
      _rootIndex = index;
      _path = '/';
      _items = const [];
      _dirError = null;
      _dirMissing = false;
    });
    _loadDir(showSpinner: true);
  }

  void _openDir(FileEntry entry) {
    setState(() {
      _path = _joinPath(_path, entry.name);
      _items = const [];
    });
    _loadDir(showSpinner: true);
  }

  void _jumpTo(String path) {
    setState(() {
      _path = path;
      _items = const [];
    });
    _loadDir(showSpinner: true);
  }

  void _goUp() {
    if (_path == '/' || _path.isEmpty) return;
    final parts = _segments(_path);
    if (parts.isEmpty) return;
    parts.removeLast();
    _jumpTo(parts.isEmpty ? '/' : '/${parts.join('/')}');
  }

  // ── 下载 ──────────────────────────────────────

  Future<void> _download(FileEntry entry) async {
    final messenger = ScaffoldMessenger.of(context);
    final uri = _api.downloadUri(
      root: _rootIndex,
      path: _joinPath(_path, entry.name),
    );
    try {
      final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
      if (!ok) {
        messenger.showSnackBar(
          SnackBar(content: Text('无法打开下载链接：$uri')),
        );
      }
    } on Exception catch (e) {
      messenger.showSnackBar(
        SnackBar(content: Text('下载失败：$e')),
      );
    }
  }

  // ── 构建 ──────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final topInset = MediaQuery.of(context).padding.top;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsets.fromLTRB(16, topInset + 12, 12, 0),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '文件',
                  style: titleStyle(size: 26, color: cs.onSurface),
                ),
              ),
              IconButton(
                tooltip: '刷新',
                onPressed: _loadingRoots ? null : _refresh,
                icon: const Icon(Icons.refresh_rounded, size: 20),
              ),
            ],
          ),
        ),
        if (_roots.length > 1)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: _buildRootChips(cs),
          ),
        if (!_loadingRoots && _rootsError == null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: _buildBreadcrumb(cs),
          ),
        Expanded(child: _buildBody(cs)),
      ],
    );
  }

  Future<void> _refresh() async {
    if (_roots.isEmpty) {
      await _loadRoots();
    } else {
      await _loadDir(showSpinner: true);
    }
  }

  Widget _buildRootChips(ColorScheme cs) {
    return SizedBox(
      height: 34,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: _roots.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, i) {
          final r = _roots[i];
          final selected = i == _rootIndex;
          return ChoiceChip(
            label: Text(r.name),
            selected: selected,
            showCheckmark: false,
            visualDensity: VisualDensity.compact,
            labelStyle: TextStyle(
              fontSize: 12,
              fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
              color: selected ? cs.onPrimaryContainer : cs.onSurfaceVariant,
            ),
            selectedColor: cs.primaryContainer,
            backgroundColor: cs.surfaceContainerHigh,
            side: BorderSide.none,
            onSelected: (_) => _selectRoot(i),
          );
        },
      ),
    );
  }

  Widget _buildBreadcrumb(ColorScheme cs) {
    final parts = _segments(_path);
    final crumbs = <Widget>[
      _crumb(cs, label: _rootName, onTap: () => _jumpTo('/')),
    ];
    var acc = '';
    for (final p in parts) {
      acc = '$acc/$p';
      final target = acc;
      crumbs.add(Padding(
        padding: const EdgeInsets.symmetric(horizontal: 2),
        child: Icon(Icons.chevron_right_rounded,
            size: 16, color: cs.onSurfaceVariant.withValues(alpha: 0.6)),
      ));
      crumbs.add(_crumb(cs, label: p, onTap: () => _jumpTo(target)));
    }

    return Row(
      children: [
        if (_path != '/' && _path.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(right: 4),
            child: InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: _goUp,
              child: Padding(
                padding: const EdgeInsets.all(4),
                child: Icon(Icons.arrow_upward_rounded,
                    size: 16, color: cs.primary),
              ),
            ),
          ),
        Expanded(
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: crumbs),
          ),
        ),
      ],
    );
  }

  Widget _crumb(ColorScheme cs,
      {required String label, required VoidCallback onTap}) {
    return InkWell(
      borderRadius: BorderRadius.circular(6),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
        child: Text(
          label,
          style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
        ),
      ),
    );
  }

  String get _rootName =>
      (_rootIndex >= 0 && _rootIndex < _roots.length)
          ? _roots[_rootIndex].name
          : '根目录';

  Widget _buildBody(ColorScheme cs) {
    if (_loadingRoots) {
      return const Center(
        child: SizedBox(
          width: 22,
          height: 22,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      );
    }

    if (_rootsError != null) {
      return _errorView(
        cs,
        icon: Icons.cloud_off_rounded,
        title: '连不上文件服务',
        detail: '${_rootsError!}\n'
            '${widget.host}:${widget.port}',
        onRetry: _loadRoots,
      );
    }

    if (_loadingDir && _items.isEmpty) {
      return const Center(
        child: SizedBox(
          width: 22,
          height: 22,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      );
    }

    if (_dirError != null) {
      return _errorView(
        cs,
        icon: _dirMissing
            ? Icons.folder_off_rounded
            : Icons.error_outline_rounded,
        title: _dirMissing ? '目录不存在' : '打开失败',
        detail: _dirMissing
            ? '「$_rootName」这个根目录在服务器上不存在，\n'
                '可能已被移动或删除。换个根目录试试。\n\n$_dirError'
            : _dirError!,
        onRetry: () => _loadDir(showSpinner: true),
      );
    }

    if (_items.isEmpty) {
      return RefreshIndicator(
        onRefresh: () => _loadDir(showSpinner: true),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: EdgeInsets.only(bottom: widget.bottomReserve),
          children: [
            SizedBox(height: MediaQuery.of(context).size.height * 0.18),
            Icon(Icons.folder_open_rounded,
                size: 44, color: cs.onSurfaceVariant.withValues(alpha: 0.45)),
            const SizedBox(height: 10),
            Text(
              '这个目录是空的',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => _loadDir(showSpinner: true),
      child: ListView.separated(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.fromLTRB(8, 0, 8, widget.bottomReserve),
        itemCount: _items.length + (_absPath.isEmpty ? 0 : 1),
        separatorBuilder: (_, _) => const SizedBox(height: 2),
        itemBuilder: (context, i) {
          // 末行显示绝对路径（方便排查，也符合「知道自己在哪」）
          if (_absPath.isNotEmpty && i == _items.length) {
            return Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 0),
              child: Text(
                _absPath,
                style: TextStyle(
                  fontSize: 10,
                  color: cs.onSurfaceVariant.withValues(alpha: 0.55),
                ),
              ),
            );
          }
          return _buildTile(cs, _items[i]);
        },
      ),
    );
  }

  Widget _buildTile(ColorScheme cs, FileEntry e) {
    final icon = e.isDir
        ? Icons.folder_rounded
        : _fileIcon(e.name);
    final iconColor = e.isDir ? const Color(0xFF3E9BFF) : cs.onSurfaceVariant;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => e.isDir ? _openDir(e) : _download(e),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
          child: Row(
            children: [
              Icon(icon, size: 22, color: iconColor),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      e.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w500,
                        color: cs.onSurface,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      _subtitle(e),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 10.5,
                        color: cs.onSurfaceVariant.withValues(alpha: 0.8),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Icon(
                e.isDir
                    ? Icons.chevron_right_rounded
                    : Icons.download_rounded,
                size: 18,
                color: e.isDir
                    ? cs.onSurfaceVariant.withValues(alpha: 0.6)
                    : cs.primary,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _errorView(
    ColorScheme cs, {
    required IconData icon,
    required String title,
    required String detail,
    required Future<void> Function() onRetry,
  }) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: EdgeInsets.fromLTRB(28, MediaQuery.of(context).size.height * 0.14, 28, 32),
      children: [
        Icon(icon, size: 46, color: cs.onSurfaceVariant.withValues(alpha: 0.6)),
        const SizedBox(height: 14),
        Text(
          title,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w700,
            color: cs.onSurface,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          detail,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 11.5,
            height: 1.5,
            color: cs.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 18),
        Center(
          child: FilledButton.tonalIcon(
            onPressed: () => onRetry(),
            icon: const Icon(Icons.refresh_rounded, size: 18),
            label: const Text('重试'),
          ),
        ),
      ],
    );
  }

  // ── 小工具 ────────────────────────────────────

  String _subtitle(FileEntry e) {
    final parts = <String>[];
    if (e.isDir) {
      parts.add('文件夹');
    } else if (e.sizeText.isNotEmpty) {
      parts.add(e.sizeText);
    } else if (e.size > 0) {
      parts.add(_humanSize(e.size));
    }
    if (e.mtime != null) parts.add(_fmtTime(e.mtime!));
    if (!e.writable) parts.add('只读');
    return parts.isEmpty ? '—' : parts.join(' · ');
  }

  static IconData _fileIcon(String name) {
    final dot = name.lastIndexOf('.');
    final ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : '';
    switch (ext) {
      case 'apk':
        return Icons.android_rounded;
      case 'zip':
      case 'gz':
      case 'xz':
      case 'tar':
      case '7z':
      case 'img':
        return Icons.folder_zip_rounded;
      case 'png':
      case 'jpg':
      case 'jpeg':
      case 'webp':
      case 'gif':
        return Icons.image_rounded;
      case 'mp4':
      case 'mkv':
      case 'avi':
        return Icons.movie_rounded;
      case 'mp3':
      case 'flac':
      case 'wav':
        return Icons.audiotrack_rounded;
      case 'md':
      case 'txt':
      case 'log':
      case 'json':
      case 'yaml':
      case 'yml':
        return Icons.description_rounded;
      case 'sh':
      case 'mjs':
      case 'js':
      case 'dart':
      case 'py':
        return Icons.terminal_rounded;
      default:
        return Icons.insert_drive_file_rounded;
    }
  }

  static String _humanSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    var v = bytes / 1024;
    var i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    return '${v.toStringAsFixed(v >= 100 ? 0 : 1)} ${units[i]}';
  }

  static String _fmtTime(DateTime t) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${t.year}-${two(t.month)}-${two(t.day)} '
        '${two(t.hour)}:${two(t.minute)}';
  }

  /// 把 "/" 和名字拼成安全路径
  static String _joinPath(String base, String name) {
    final b = (base.isEmpty || base == '/') ? '' : base;
    return '$b/$name';
  }

  /// "/a/b" → ["a","b"]；"/" → []
  static List<String> _segments(String path) {
    return path
        .split('/')
        .where((s) => s.isNotEmpty)
        .toList(growable: true);
  }
}
