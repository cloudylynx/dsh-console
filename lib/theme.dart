import 'package:flutter/material.dart';

/// 旧版中控台使用的标题字体（见 pubspec.yaml 的 fonts 段）
const String kTitleFont = 'ConsoleHeavy';

/// 标题字体样式辅助函数
///
/// [size] 字号；[color] 可选颜色（不传则由父级 DefaultTextStyle / Theme 决定）。
TextStyle titleStyle({
  required double size,
  Color? color,
  FontWeight fontWeight = FontWeight.w700,
  double? height,
  double? letterSpacing,
}) {
  return TextStyle(
    fontFamily: kTitleFont,
    fontSize: size,
    fontWeight: fontWeight,
    color: color,
    height: height,
    letterSpacing: letterSpacing,
  );
}
