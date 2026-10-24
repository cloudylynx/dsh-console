import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:liquid_glass_renderer/liquid_glass_renderer.dart';

import 'api/api_client.dart';
import 'pages/files_page.dart';
import 'pages/home_page.dart';
import 'pages/messages_page.dart';
import 'pages/settings_page.dart';
import 'pages/terminal_page.dart';
import 'pages/web_page.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'settings.dart';
import 'state/console_state.dart';
import 'theme.dart';
import 'widgets/glass_dock.dart';

/// 启动异常收集（显示在屏幕上，避免"白屏无从下手"）
final List<String> startupErrors = [];

/// GPU 预热状态
bool gpuReady = false;
final ValueNotifier<int> gpuReadyNotifier = ValueNotifier<int>(0);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    startupErrors.add(details.exceptionAsString());
    if (startupErrors.length > 5) startupErrors.removeAt(0);
  };
  ui.PlatformDispatcher.instance.onError = (error, stack) {
    startupErrors.add('$error');
    if (startupErrors.length > 5) startupErrors.removeAt(0);
    return true;
  };

  final settings = await AppSettings.load();
  runApp(ConsoleApp(settings: settings));

  // 首帧后再预热 GPU（避免拖慢启动）
  WidgetsBinding.instance.addPostFrameCallback((_) {
    Future<void>(() async {
      await Future<void>.delayed(const Duration(milliseconds: 300));
      try {
        await LiquidGlass.precache();
        gpuReady = true;
        gpuReadyNotifier.value++;
        debugPrint('GPU precache done');
      } catch (e) {
        startupErrors.add('precache: $e');
      }
    });
  });
}

/// 首次启动提示（只显示一次）
Future<void> _maybeShowFirstRunNotice(BuildContext context) async {
  const key = 'first_run_notice_shown_v1';
  try {
    final sp = await SharedPreferences.getInstance();
    if (sp.getBool(key) ?? false) return;
  } catch (_) {
    return;
  }
  if (!context.mounted) return;
  final nav = Navigator.maybeOf(context);
  if (nav == null) return;

  try {
    await showDialog<void>(
      context: context,
      barrierDismissible: true,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return AlertDialog(
          icon: Icon(Icons.volunteer_activism_rounded, size: 32, color: cs.primary),
          title: const Text('免费开源软件'),
          content: const SingleChildScrollView(
            child: Text(
              '本项目完全免费开源，任何人都可以自由使用、修改和分发。\n\n'
              '如果你是从某个渠道【付费购买】的，说明有人违反开源协议倒卖。'
              '建议立即申请退款，并向平台举报该商家。\n\n'
              '源码地址：github.com/cloudylynx/dsh-console（NEXT 分支）\n'
              '许可证：Apache-2.0',
              style: TextStyle(fontSize: 13.5, height: 1.5),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('我知道了'),
            ),
          ],
        );
      },
    );
    final sp = await SharedPreferences.getInstance();
    await sp.setBool(key, true);
  } catch (_) {}
}

class ConsoleApp extends StatefulWidget {
  const ConsoleApp({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<ConsoleApp> createState() => _ConsoleAppState();
}

class _ConsoleAppState extends State<ConsoleApp> {
  bool _noticeChecked = false;

  void _checkFirstRun() {
    if (_noticeChecked) return;
    _noticeChecked = true;
    Future<void>.delayed(const Duration(milliseconds: 500), () {
      if (!mounted) return;
      _maybeShowFirstRunNotice(context);
    });
  }

  @override
  Widget build(BuildContext context) {
    _checkFirstRun();
    return ListenableBuilder(
      listenable: widget.settings,
      builder: (context, _) {
        final seed = Color(widget.settings.accentColor);
        return MaterialApp(
          title: 'DSH 控制台',
          debugShowCheckedModeBanner: false,
          theme: ThemeData(
            colorSchemeSeed: seed,
            brightness: Brightness.light,
            useMaterial3: true,
            fontFamily: kTitleFont,
          ),
          darkTheme: ThemeData(
            colorSchemeSeed: seed,
            brightness: Brightness.dark,
            useMaterial3: true,
            fontFamily: kTitleFont,
          ),
          themeMode: widget.settings.materialThemeMode,
          home: MainShell(settings: widget.settings),
        );
      },
    );
  }
}

/// 主框架：内容 + 液态玻璃底栏
class MainShell extends StatefulWidget {
  const MainShell({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  late ConsoleState _state;

  int _tab = 0;

  /// 浮动标签
  int? _labelIndex;
  double _labelCenterX = 0;

  /// 二级页（消息）
  bool _showMessages = false;

  /// 底栏是否展开（false = 收缩成小胶囊）
  bool _dockExpanded = false;

  /// 底栏项目：按用户设置的顺序动态生成
  List<DockDestination> get _items {
    final order = widget.settings.tabOrder;
    return [
      for (final t in order)
        DockDestination(
          icon: AppSettings.tabIcons[t],
          label: AppSettings.tabNames[t],
        ),
    ];
  }

  @override
  void initState() {
    super.initState();
    _state = ConsoleState(widget.settings)..start();
  }

  @override
  void dispose() {
    _state.dispose();
    super.dispose();
  }

  double get _bottomReserve =>
      widget.settings.dockHeight + 40 + MediaQuery.of(context).padding.bottom;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.settings,
      builder: (context, _) => PopScope(
        // ★消息页时，系统返回键 = 关闭消息页（而不是退出 App）
        canPop: !_showMessages,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop && _showMessages) {
            setState(() => _showMessages = false);
          }
        },
        child: Scaffold(
          extendBody: true,
          body: Stack(
            children: [
              // 内容区：★展开时也正常可操作
            //   用 Listener 监听「手指按下」→ 立刻收缩底栏，但事件继续传给内容
            //   这样：一碰内容就收起底栏，同时点击/滑动照常生效
            Positioned.fill(
              child: _dockExpanded
                  ? Listener(
                      behavior: HitTestBehavior.translucent,
                      onPointerDown: (_) =>
                          setState(() => _dockExpanded = false),
                      child: _buildContent(),
                    )
                  : _buildContent(),
            ),
            Positioned(left: 0, right: 0, bottom: 0, child: _buildDock()),
            if (_labelIndex != null) _buildFloatingLabel(context),
              if (startupErrors.isNotEmpty) _buildErrorOverlay(context),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildContent() {
    if (_showMessages) {
      return MessagesPage(
        state: _state,
        bottomReserve: _bottomReserve,
        onClose: () => setState(() => _showMessages = false),
      );
    }
    switch (_tab) {
      case 1:
        // TerminalPage 自带按键条 + basic auth 应答
        return TerminalPage(
          url: widget.settings.terminalUrlWithFont,
          title: '终端',
          username: widget.settings.terminalUser,
          password: widget.settings.terminalPass,
          bottomReserve: _bottomReserve,
          reloadKey: widget.settings.terminalUrlWithFont,
        );
      case 2:
        // 原生文件列表（18802 是 JSON API 不是网页）
        return FilesPage(
          host: widget.settings.host,
          bottomReserve: _bottomReserve,
        );
      case 3:
        // ★自动拼 token（DSH 不带 token 会 401）
        return WebPage(
          url: _dshUrlWithToken(),
          title: 'DSH',
          bottomReserve: _bottomReserve,
          reloadKey: '${widget.settings.dshUrl}|${widget.settings.dshToken}',
        );
      case 4:
        return SettingsPage(
          settings: widget.settings,
          state: _state,
          bottomReserve: _bottomReserve,
          onServerChanged: () => _state.onServerChanged(),
        );
      default:
        return HomePage(
          state: _state,
          bottomReserve: _bottomReserve,
          onShowMessages: () => setState(() => _showMessages = true),
          onShowCommands: _showCommands,
          onShowLogs: _showLogs,
        );
    }
  }

  /// 给 DSH 地址拼上 token
  String _dshUrlWithToken() {
    final base = widget.settings.dshUrl.trim();
    final tok = widget.settings.dshToken.trim();
    if (tok.isEmpty) return base;
    final sep = base.contains('?') ? '&' : '?';
    return '$base${sep}token=$tok';
  }

  Widget _buildDock() {
    final s = widget.settings;
    return GlassDock(
      items: _items,
      selected: widget.settings.tabPosition(_tab),
      height: s.dockHeight,
      indicatorInset: s.indicatorInset,
      indicatorTint: Theme.of(context).colorScheme.primary,
      iconScale: s.iconScale,
      iconLift: s.iconLift,
      animDuration: s.animDuration,
      animCurve: s.animCurveValue,
      iconFadeDuration: s.iconFadeDuration,
      iconFadeScale: s.iconFadeScale,
      expanded: _dockExpanded,
      onToggleExpand: () => setState(() => _dockExpanded = !_dockExpanded),
      glass: LiquidGlassSettings(
        refractionHeight: s.refractionHeight,
        refractionAmount: s.refractionAmount,
        frost: s.frost + s.tintAmount * 10,
        highlight: s.highlight,
        contourStrength: s.contourStrength,
        contourDirectionality: 0.8,
        bevelShadowStrength: s.bevelShadow,
        tintAmount: s.tintAmount,
      ),
      onSelected: (pos) => setState(() {
        _tab = widget.settings.tabAt(pos);   // 位置索引 → 语义 tab
        _showMessages = false;
        _dockExpanded = false;
      }),
      onLabelChange: (idx, x) => setState(() {
        _labelIndex = idx;
        _labelCenterX = x;
      }),
    );
  }

  Widget _buildFloatingLabel(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final idx = _labelIndex!;
    final screenW = MediaQuery.of(context).size.width;
    final bottomInset = MediaQuery.of(context).padding.bottom;

    const labelWidth = 76.0;
    final rawX =
        (2 * _labelCenterX - labelWidth) / (screenW - labelWidth) - 1;

    return Positioned(
      left: 0,
      right: 0,
      bottom: bottomInset + 14 + widget.settings.dockHeight + 10,
      child: Align(
        alignment: Alignment(rawX.clamp(-1.0, 1.0), 0),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: cs.inverseSurface.withValues(alpha: 0.92),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Text(
            _items[idx].label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: cs.onInverseSurface,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildErrorOverlay(BuildContext context) {
    return Positioned(
      left: 8,
      right: 8,
      top: 48,
      child: Material(
        color: Colors.red.withValues(alpha: 0.92),
        borderRadius: BorderRadius.circular(10),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('启动异常',
                  style: TextStyle(
                      color: Colors.white,
                      fontSize: 12,
                      fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              ...startupErrors.map((e) => Text(
                    e.length > 160 ? '${e.substring(0, 160)}…' : e,
                    style: const TextStyle(
                        color: Colors.white, fontSize: 10, height: 1.3),
                  )),
            ],
          ),
        ),
      ),
    );
  }

  // ── 快捷命令 ──────────────────────────────
  Future<void> _showCommands() async {
    final api = _state.api;
    if (api == null) return;
    List<QuickCommand> cmds;
    try {
      cmds = await api.fetchCommands();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('拉取命令失败: $e')),
        );
      }
      return;
    }
    if (!mounted) return;

    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) {
        final cs = Theme.of(context).colorScheme;
        return DraggableScrollableSheet(
          expand: false,
          initialChildSize: 0.55,
          maxChildSize: 0.9,
          builder: (context, controller) => ListView(
            controller: controller,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              Text('快捷命令',
                  style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                      color: cs.onSurface)),
              const SizedBox(height: 12),
              if (cmds.isEmpty)
                Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text('没有可用命令',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: cs.onSurfaceVariant)),
                )
              else
                ...cmds.map((c) => Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(12),
                        onTap: () => _runCommand(c),
                        child: Container(
                          padding: const EdgeInsets.all(14),
                          decoration: BoxDecoration(
                            color: cs.surfaceContainerHigh,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Row(
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment:
                                      CrossAxisAlignment.start,
                                  children: [
                                    Text(c.name,
                                        style: TextStyle(
                                            fontSize: 14,
                                            fontWeight: FontWeight.w600,
                                            color: cs.onSurface)),
                                    if (c.desc.isNotEmpty) ...[
                                      const SizedBox(height: 3),
                                      Text(c.desc,
                                          style: TextStyle(
                                              fontSize: 11,
                                              color: cs.onSurfaceVariant)),
                                    ],
                                  ],
                                ),
                              ),
                              Icon(Icons.play_arrow_rounded,
                                  size: 20, color: cs.primary),
                            ],
                          ),
                        ),
                      ),
                    )),
            ],
          ),
        );
      },
    );
  }

  Future<void> _runCommand(QuickCommand cmd) async {
    final api = _state.api;
    if (api == null) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      final runId = await api.runCommand(cmd.cmd, id: cmd.id);
      messenger.showSnackBar(SnackBar(
        content: Text('已执行: ${cmd.name}'),
        duration: const Duration(seconds: 2),
      ));
      if (runId != null) {
        for (var i = 0; i < 30; i++) {
          await Future<void>.delayed(const Duration(milliseconds: 500));
          final r = await api.commandResult(runId);
          if (r != null && r.done) {
            final out = (r.stdout + r.stderr).trim();
            messenger.showSnackBar(SnackBar(
              content: Text(
                '完成 (exit=${r.exitCode})'
                '${out.isEmpty ? '' : '\n${out.split('\n').take(3).join('\n')}'}',
              ),
              duration: const Duration(seconds: 4),
            ));
            break;
          }
        }
      }
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('执行失败: $e')));
    }
  }

  // ── 实时日志 ──────────────────────────────
  void _showLogs() {
    final url = 'http://${widget.settings.host}:18900/';
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (context) => Scaffold(
        appBar: AppBar(title: const Text('实时日志')),
        body: WebPage(url: url, title: '实时日志', bottomReserve: 0),
      ),
    ));
  }
}
