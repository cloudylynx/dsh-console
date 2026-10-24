import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../state/console_state.dart';

/// 折线图（支持单线 / 双线）
class SparkChart extends StatelessWidget {
  const SparkChart({
    super.key,
    required this.points,
    required this.color,
    this.color2,
    this.autoScale = true,
    this.fixedMax,
    this.unit = '',
    this.height = 60,
  });

  final List<ChartPoint> points;
  final Color color;
  /// 第二条线的颜色（为 null 则单线）
  final Color? color2;
  final bool autoScale;
  final double? fixedMax;
  final String unit;
  final double height;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(
        painter: _SparkPainter(
          points: points,
          color: color,
          color2: color2,
          autoScale: autoScale,
          fixedMax: fixedMax,
        ),
      ),
    );
  }
}

class _SparkPainter extends CustomPainter {
  _SparkPainter({
    required this.points,
    required this.color,
    required this.color2,
    required this.autoScale,
    required this.fixedMax,
  });

  final List<ChartPoint> points;
  final Color color;
  final Color? color2;
  final bool autoScale;
  final double? fixedMax;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.isEmpty) return;

    // 计算 Y 轴最大值
    double maxV = fixedMax ?? 0;
    if (fixedMax == null) {
      for (final p in points) {
        maxV = math.max(maxV, p.v1);
        if (color2 != null) maxV = math.max(maxV, p.v2);
      }
      if (maxV <= 0) maxV = 1;
      // 留 15% 余量，并取整到好看的刻度
      maxV = _niceCeil(maxV * 1.15);
    }

    void drawLine(bool second) {
      final c = second ? (color2 ?? color) : color;
      final paint = Paint()
        ..color = c
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.8
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round;

      final path = Path();
      final n = points.length;
      for (int i = 0; i < n; i++) {
        final p = points[i];
        final v = second ? p.v2 : p.v1;
        final x = n == 1 ? size.width : size.width * i / (n - 1);
        final y = size.height - (v / maxV).clamp(0.0, 1.0) * size.height;
        if (i == 0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
      }
      canvas.drawPath(path, paint);

      // 渐变填充（只给第一条线）
      if (!second) {
        final fill = Path.from(path)
          ..lineTo(size.width, size.height)
          ..lineTo(0, size.height)
          ..close();
        canvas.drawPath(
          fill,
          Paint()
            ..shader = LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [color.withValues(alpha: 0.22), color.withValues(alpha: 0)],
            ).createShader(Offset.zero & size),
        );
      }
    }

    drawLine(false);
    if (color2 != null) drawLine(true);
  }

  /// 取一个好看的刻度上限
  double _niceCeil(double v) {
    if (v <= 0) return 1;
    final exp = (math.log(v) / math.ln10).floor();
    final base = math.pow(10, exp).toDouble();
    final n = v / base;
    double nice;
    if (n <= 1) {
      nice = 1;
    } else if (n <= 2) {
      nice = 2;
    } else if (n <= 5) {
      nice = 5;
    } else {
      nice = 10;
    }
    return nice * base;
  }

  @override
  bool shouldRepaint(_SparkPainter old) =>
      old.points != points ||
      old.color != color ||
      old.color2 != color2 ||
      old.fixedMax != fixedMax;
}
