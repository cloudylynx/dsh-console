import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 全局设置（对应旧版 AppSettings.java）
class AppSettings extends ChangeNotifier {
  AppSettings(this._sp);

  final SharedPreferences _sp;

  static const _kHost = 'host';
  static const _kPort = 'port';
  static const _kTheme = 'theme_mode';
  static const _kAccent = 'accent_color';
  static const _kInterval = 'refresh_sec';
  static const _kChartPoints = 'chart_points';
  static const _kTermUrl = 'terminal_url';
  static const _kTermUser = 'terminal_user';
  static const _kTermPass = 'terminal_pass';
  static const _kTermFont = 'terminal_font_size';
  static const _kDshUrl = 'dsh_url';
  static const _kDshToken = 'dsh_token';
  static const _kNavStyle = 'nav_style';
  static const _kTabOrder = 'tab_order';

  // 后台监控（key 与旧版 AppSettings.java 完全一致）
  static const _kMonitorEnabled = 'monitor_enabled';
  static const _kNotifyMsg = 'notify_msg';
  static const _kNotifyAlert = 'notify_alert';
  static const _kMemThreshold = 'mem_th';
  static const _kDiskThreshold = 'disk_th';

  // 玻璃参数
  static const _kTintAmount = 'glass_tint';
  static const _kFrost = 'glass_frost';
  static const _kHighlight = 'glass_highlight';
  static const _kRefractionAmount = 'glass_refraction_amount';
  static const _kRefractionHeight = 'glass_refraction_height';
  static const _kContourStrength = 'glass_contour';
  static const _kBevelShadow = 'glass_bevel';
  static const _kDockHeight = 'dock_height';
  static const _kIndicatorInset = 'indicator_inset';
  static const _kIconScale = 'icon_scale';
  static const _kIconLift = 'icon_lift';
  static const _kAnimDuration = 'anim_duration';
  static const _kAnimCurve = 'anim_curve';
  static const _kIconFadeDuration = 'icon_fade_duration';
  static const _kIconFadeScale = 'icon_fade_scale';

  static Future<AppSettings> load() async =>
      AppSettings(await SharedPreferences.getInstance());

  // ── 服务器 ──────────────────────────────────
  String get host => _sp.getString(_kHost) ?? '';
  set host(String v) => _set(_kHost, v);

  int get port => _sp.getInt(_kPort) ?? 18800;
  set port(int v) => _set(_kPort, v);

  String get apiUrl => 'http://$host:$port';

  // ── 外观 ────────────────────────────────────
  /// 0 跟随系统 / 1 浅色 / 2 深色
  int get themeMode => _sp.getInt(_kTheme) ?? 2;
  set themeMode(int v) => _set(_kTheme, v);

  ThemeMode get materialThemeMode => switch (themeMode) {
        1 => ThemeMode.light,
        2 => ThemeMode.dark,
        _ => ThemeMode.system,
      };

  int get accentColor => _sp.getInt(_kAccent) ?? 0xFF0A84FF;
  set accentColor(int v) => _set(_kAccent, v);

  // ── 刷新 ────────────────────────────────────
  int get interval => _sp.getInt(_kInterval) ?? 3;
  set interval(int v) => _set(_kInterval, v.clamp(1, 60));

  int get chartPoints => _sp.getInt(_kChartPoints) ?? 60;
  set chartPoints(int v) => _set(_kChartPoints, v.clamp(10, 240));

  // ── 终端 / DSH ──────────────────────────────
  String get terminalUrl => _sp.getString(_kTermUrl) ?? 'http://$host:18801/';
  set terminalUrl(String v) => _set(_kTermUrl, v);

  String get terminalUser => _sp.getString(_kTermUser) ?? '';
  set terminalUser(String v) => _set(_kTermUser, v);

  String get terminalPass => _sp.getString(_kTermPass) ?? '';
  set terminalPass(String v) => _set(_kTermPass, v);

  /// 终端字号（px）
  int get terminalFontSize => _sp.getInt(_kTermFont) ?? 14;
  set terminalFontSize(int v) => _set(_kTermFont, v.clamp(8, 32));

  /// 带字号参数的终端 URL（ttyd 支持 ?fontSize=N）
  String get terminalUrlWithFont {
    final base = terminalUrl;
    final sep = base.contains('?') ? '&' : '?';
    return '$base${sep}fontSize=$terminalFontSize';
  }

  String get dshUrl => _sp.getString(_kDshUrl) ?? 'http://$host:19387/';
  set dshUrl(String v) => _set(_kDshUrl, v);

  String get dshToken => _sp.getString(_kDshToken) ?? '';
  set dshToken(String v) => _set(_kDshToken, v);

  // ── 底栏风格 ────────────────────────────────
  /// 0 = 液态玻璃（默认），1 = MD3 标准
  int get navStyle => _sp.getInt(_kNavStyle) ?? 0;
  set navStyle(int v) => _set(_kNavStyle, v);

  // ── 底栏顺序 ────────────────────────────────
  /// tab 的语义索引 → 显示名（索引固定，不随顺序变化）
  /// 0 首页 / 1 终端 / 2 文件 / 3 DSH / 4 设置
  static const List<String> tabNames = ['首页', '终端', '文件', 'DSH', '设置'];

  /// tab 数量
  static const int tabCount = 5;

  /// tab 图标（与 tabNames 同序，供底栏和设置页共用）
  static const List<IconData> tabIcons = [
    Icons.dashboard_rounded,
    Icons.terminal_rounded,
    Icons.folder_rounded,
    Icons.cloud_rounded,
    Icons.settings_rounded,
  ];

  /// 默认顺序（语义索引的排列）
  static const List<int> defaultTabOrder = [0, 1, 2, 3, 4];

  /// 底栏顺序：列表的「下标」是显示位置，值是 tab 语义索引。
  ///
  /// 例：[4,0,1,2,3] 表示第一个位置显示「设置」(4)。
  /// 存储格式："0,1,2,3,4"（用 setString 存）
  List<int> get tabOrder {
    final raw = _sp.getString(_kTabOrder);
    if (raw == null || raw.trim().isEmpty) {
      return List<int>.of(defaultTabOrder);
    }
    final out = <int>[];
    for (final part in raw.split(',')) {
      final v = int.tryParse(part.trim());
      if (v != null && v >= 0 && v < tabCount && !out.contains(v)) {
        out.add(v);
      }
    }
    // 补齐缺失的 tab（防止旧数据/损坏数据导致少项）
    for (var i = 0; i < tabCount; i++) {
      if (!out.contains(i)) out.add(i);
    }
    return out;
  }

  set tabOrder(List<int> v) {
    final out = <int>[];
    for (final x in v) {
      if (x >= 0 && x < tabCount && !out.contains(x)) out.add(x);
    }
    for (var i = 0; i < tabCount; i++) {
      if (!out.contains(i)) out.add(i);
    }
    _set(_kTabOrder, out.join(','));
  }

  /// 语义 tab 索引 → 显示位置。找不到时回退到 0（防御性）
  int tabPosition(int tab) {
    final p = tabOrder.indexOf(tab);
    return p < 0 ? 0 : p;
  }

  /// 显示位置 → 语义 tab 索引
  int tabAt(int position) {
    final o = tabOrder;
    if (o.isEmpty) return 0;
    final i = position.clamp(0, o.length - 1);
    return o[i];
  }

  /// 把显示位置 [from] 的 tab 移到 [to] 位置，并立即保存
  void moveTabOrder(int from, int to) {
    final o = tabOrder;
    if (from < 0 || from >= o.length) return;
    if (to < 0 || to >= o.length || from == to) return;
    final v = o.removeAt(from);
    o.insert(to, v);
    tabOrder = o;
  }

  /// 恢复默认顺序
  void resetTabOrder() => tabOrder = List<int>.of(defaultTabOrder);

  // ── 后台监控 ────────────────────────────────
  /// 后台监控总开关（默认关，避免一装上就常驻通知）
  bool get monitorEnabled => _sp.getBool(_kMonitorEnabled) ?? false;
  set monitorEnabled(bool v) => _set(_kMonitorEnabled, v);

  /// 新消息通知
  bool get notifyMsg => _sp.getBool(_kNotifyMsg) ?? true;
  set notifyMsg(bool v) => _set(_kNotifyMsg, v);

  /// 告警通知（内存/磁盘/温度/后端 critical）
  bool get notifyAlert => _sp.getBool(_kNotifyAlert) ?? true;
  set notifyAlert(bool v) => _set(_kNotifyAlert, v);

  /// 内存告警阈值（百分比）
  int get memThreshold => _sp.getInt(_kMemThreshold) ?? 90;
  set memThreshold(int v) => _set(_kMemThreshold, v.clamp(50, 99));

  /// 磁盘告警阈值（百分比）
  int get diskThreshold => _sp.getInt(_kDiskThreshold) ?? 90;
  set diskThreshold(int v) => _set(_kDiskThreshold, v.clamp(50, 99));

  // ── 玻璃参数 ────────────────────────────────
  double get tintAmount => _sp.getDouble(_kTintAmount) ?? 0.12;
  set tintAmount(double v) => _setD(_kTintAmount, v);

  double get frost => _sp.getDouble(_kFrost) ?? 3.2;
  set frost(double v) => _setD(_kFrost, v);

  double get highlight => _sp.getDouble(_kHighlight) ?? 0.28;
  set highlight(double v) => _setD(_kHighlight, v);

  double get refractionAmount => _sp.getDouble(_kRefractionAmount) ?? 72;
  set refractionAmount(double v) => _setD(_kRefractionAmount, v);

  double get refractionHeight => _sp.getDouble(_kRefractionHeight) ?? 18;
  set refractionHeight(double v) => _setD(_kRefractionHeight, v);

  double get contourStrength => _sp.getDouble(_kContourStrength) ?? 0.32;
  set contourStrength(double v) => _setD(_kContourStrength, v);

  double get bevelShadow => _sp.getDouble(_kBevelShadow) ?? 0.05;
  set bevelShadow(double v) => _setD(_kBevelShadow, v);

  double get dockHeight => _sp.getDouble(_kDockHeight) ?? 64;
  set dockHeight(double v) => _setD(_kDockHeight, v);

  double get indicatorInset => _sp.getDouble(_kIndicatorInset) ?? 3;
  set indicatorInset(double v) => _setD(_kIndicatorInset, v);

  /// 图标放大倍率（滑块接近时）
  double get iconScale => _sp.getDouble(_kIconScale) ?? 0.45;
  set iconScale(double v) => _setD(_kIconScale, v.clamp(0.0, 1.5));

  /// 图标上抬距离（dp）
  double get iconLift => _sp.getDouble(_kIconLift) ?? 3.0;
  set iconLift(double v) => _setD(_kIconLift, v.clamp(0.0, 16.0));

  /// 底栏展开/收缩动画时长（ms）
  int get animDuration => _sp.getInt(_kAnimDuration) ?? 320;
  set animDuration(int v) => _set(_kAnimDuration, v.clamp(80, 1000));

  /// 动画曲线（0=线性 1=easeOut 2=easeOutBack 3=easeOutQuint 4=easeInOut）
  int get animCurve => _sp.getInt(_kAnimCurve) ?? 1;
  set animCurve(int v) => _set(_kAnimCurve, v.clamp(0, 4));

  /// 图标切换动画时长（ms）
  int get iconFadeDuration => _sp.getInt(_kIconFadeDuration) ?? 220;
  set iconFadeDuration(int v) => _set(_kIconFadeDuration, v.clamp(60, 800));

  /// 图标切换起始缩放（0.3~1.0）
  double get iconFadeScale => _sp.getDouble(_kIconFadeScale) ?? 0.82;
  set iconFadeScale(double v) => _setD(_kIconFadeScale, v.clamp(0.3, 1.0));

  /// 动画曲线名（设置页显示用）
  static const List<String> animCurveNames = [
    '线性（匀速）',
    'easeOut（先快后慢）',
    'easeOutBack（轻微过冲）',
    'easeOutQuint（利落）',
    'easeInOut（两头慢）',
  ];

  Curve get animCurveValue => switch (animCurve) {
        0 => Curves.linear,
        2 => Curves.easeOutBack,
        3 => Curves.easeOutQuint,
        4 => Curves.easeInOutCubic,
        _ => Curves.easeOutCubic,
      };

  // ── 内部 ────────────────────────────────────
  void _set(String k, Object v) {
    if (v is String) {
      _sp.setString(k, v);
    } else if (v is int) {
      _sp.setInt(k, v);
    } else if (v is bool) {
      _sp.setBool(k, v);
    }
    notifyListeners();
  }

  void _setD(String k, double v) {
    _sp.setDouble(k, v);
    notifyListeners();
  }

  /// 清空全部设置
  Future<void> reset() async {
    await _sp.clear();
    notifyListeners();
  }
}
