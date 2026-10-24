import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:liquid_glass_renderer/liquid_glass_renderer.dart';

/// 悬浮液态玻璃底栏
///
/// 结构（关键，少一层就没玻璃圈）：
///   LiquidGlassLayer（采样下层像素）
///     └ LiquidGlass（画玻璃形状/折射/边缘）
///         └ Stack
///             ├ 图标 Row（随滑块接近而放大）
///             └ 选中块（独立 LiquidGlassLayer，带主题色染色）
///
/// 交互：
///   · 点击切换
///   · 左右滑动切换（拖动时实时上报标签）
///   · 滑动/点击时父级在上方浮出文字标签（文字不进玻璃，所以清晰）
class GlassDock extends StatefulWidget {
  const GlassDock({
    super.key,
    required this.items,
    required this.selected,
    required this.onSelected,
    required this.glass,
    required this.height,
    required this.indicatorInset,
    required this.indicatorTint,
    required this.onLabelChange,
    this.expanded = true,
    this.onToggleExpand,
    this.iconScale = 0.45,
    this.iconLift = 3.0,
    this.animDuration = 320,
    this.animCurve = Curves.easeOutCubic,
    this.iconFadeDuration = 220,
    this.iconFadeScale = 0.82,
    this.indicatorTintAlpha = 0.42,
    this.maxWidth = 560,
    this.bottomPadding = 14,
  });

  final List<DockDestination> items;
  final int selected;
  final ValueChanged<int> onSelected;

  /// 外层玻璃设置
  final LiquidGlassSettings glass;
  final double height;
  final double indicatorInset;
  final Color indicatorTint;
  final double maxWidth;
  final double bottomPadding;

  /// 图标放大倍率（滑块接近时）
  final double iconScale;

  /// 图标上抬距离（dp）
  final double iconLift;

  /// 展开/收缩动画时长（ms）
  final int animDuration;

  /// 展开/收缩动画曲线
  final Curve animCurve;

  /// 图标切换动画时长（ms）
  final int iconFadeDuration;

  /// 图标切换起始缩放
  final double iconFadeScale;

  /// 选中块的染色透明度
  final double indicatorTintAlpha;

  /// 滑动/点击时上报：(index, 屏幕 x)；null 表示隐藏标签
  final void Function(int? index, double centerX) onLabelChange;

  /// 是否展开（false = 收缩成小胶囊，只显示当前图标）
  final bool expanded;

  /// 点胶囊时切换展开状态
  final VoidCallback? onToggleExpand;

  @override
  State<GlassDock> createState() => _GlassDockState();
}

class DockDestination {
  const DockDestination({required this.icon, required this.label});
  final IconData icon;
  final String label;
}

class _GlassDockState extends State<GlassDock> {
  late double _lens = widget.selected.toDouble();
  bool _dragging = false;
  double _pressX = 0;
  Timer? _autoHideTimer;

  @override
  void didUpdateWidget(covariant GlassDock old) {
    super.didUpdateWidget(old);
    if (old.selected != widget.selected && !_dragging) {
      setState(() => _lens = widget.selected.toDouble());
    }
  }

  @override
  void dispose() {
    _autoHideTimer?.cancel();
    super.dispose();
  }

  /// 算出第 [index] 个格子中心在【屏幕坐标】里的 x
  ///
  /// 标签定位必须用这个，不能用手指位置（手指压边缘会让标签歪）。
  double _slotCenterGlobal(BuildContext context, int index, double pitch) {
    final box = context.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) return 0;
    final localCenter = Offset(index * pitch + pitch / 2, box.size.height / 2);
    return box.localToGlobal(localCenter).dx;
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.of(context).padding.bottom;
    final n = widget.items.length;
    final cs = Theme.of(context).colorScheme;

    // ★统一实现：用 TweenAnimationBuilder 做收缩/展开动画
    //   收缩 = 宽度变成圆胶囊大小；展开 = 全宽
    //   两者用同一个 LiquidGlass，只是宽度不同 → 天然有"拉伸"动画
    final double collapsedSize = widget.height * 0.78;

    return Padding(
      padding: EdgeInsets.only(
        bottom: bottomInset + widget.bottomPadding,
        left: 16,
        right: 16,
      ),
      child: Align(
        alignment: Alignment.bottomCenter,
        // ★展开/收缩动画：宽度在 [collapsedSize, maxWidth] 之间平滑过渡
        //   用 TweenAnimationBuilder 而不是 AnimatedContainer，
        //   因为要同时驱动内部内容（图标数量、间距）的重排
        child: TweenAnimationBuilder<double>(
          tween: Tween<double>(
            end: widget.expanded
                ? widget.maxWidth
                : collapsedSize,
          ),
          duration: Duration(milliseconds: widget.animDuration),
          curve: widget.animCurve,
          builder: (context, animatedWidth, _) {
            final isCollapsed = animatedWidth < collapsedSize + 24;
            // ★收缩时高度也跟着变成正方形（v0.6 的正确布局）
            //   用同一个 0..1 进度插值，保证宽高同步
            final t = ((animatedWidth - collapsedSize) /
                    (widget.maxWidth - collapsedSize))
                .clamp(0.0, 1.0);
            final animatedHeight =
                collapsedSize + (widget.height - collapsedSize) * t;

            // ★动画期间：玻璃本身轻微模糊+透明度过渡
            //   让「收缩成胶囊」像是液体聚拢，而不是硬切
            final transitionProgress =
                (1.0 - t).clamp(0.0, 1.0);   // 1=完全收缩, 0=完全展开
            return SizedBox(
              width: animatedWidth,
              height: animatedHeight,
              child: Opacity(
                // 收缩到最小时略微降低不透明度，像"融进去"
                opacity: 1.0 - transitionProgress * 0.06,
                child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: widget.maxWidth),
          // ★LiquidGlassLayer 只负责采样，LiquidGlass 才画玻璃本体
          child: LiquidGlassLayer(
            settings: widget.glass,
            child: SizedBox(
              height: animatedHeight,
              child: LiquidGlass(
                shape: LiquidRoundedSuperellipse(
                  borderRadius: animatedHeight / 2,
                ),
                child: LayoutBuilder(
                  builder: (context, box) {
                    final width = box.maxWidth;
                    final pitch = width / n;
                    final inset = widget.indicatorInset;

                    return GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTapUp: (d) {
                        // ★收缩态：点击任意位置 = 展开（不是切 tab）
                        if (isCollapsed) {
                          HapticFeedback.lightImpact();
                          widget.onToggleExpand?.call();
                          return;
                        }
                        final idx = (d.localPosition.dx / pitch)
                            .floor()
                            .clamp(0, n - 1);
                        if (idx != widget.selected) {
                          HapticFeedback.selectionClick();
                          widget.onSelected(idx);
                          setState(() => _lens = idx.toDouble());
                        }
                        // 点击也短暂显示名字
                        widget.onLabelChange(
                            idx, _slotCenterGlobal(context, idx, pitch));
                        _autoHideTimer?.cancel();
                        _autoHideTimer =
                            Timer(const Duration(milliseconds: 1200), () {
                          if (mounted) widget.onLabelChange(null, 0);
                        });
                      },
                      onHorizontalDragStart: (d) {
                        if (isCollapsed) return;   // 收缩态不响应拖动
                        _pressX = d.localPosition.dx;
                        _dragging = false;
                      },
                      onHorizontalDragUpdate: (d) {
                        if (!_dragging) {
                          if ((d.localPosition.dx - _pressX).abs() <
                              kTouchSlop) {
                            return;
                          }
                          _dragging = true;
                        }
                        final raw = (d.localPosition.dx / pitch) - 0.5;
                        setState(() => _lens = raw.clamp(-0.3, n - 1 + 0.3));

                        final idx = (d.localPosition.dx / pitch)
                            .floor()
                            .clamp(0, n - 1);
                        widget.onLabelChange(
                            idx, _slotCenterGlobal(context, idx, pitch));
                      },
                      onHorizontalDragEnd: (d) {
                        final wasDragging = _dragging;
                        _dragging = false;
                        widget.onLabelChange(null, 0);
                        if (!wasDragging) return;
                        final vx = d.velocity.pixelsPerSecond.dx;
                        final lead = vx / pitch * 0.18;
                        final target = (_lens + lead)
                            .round()
                            .clamp(0, n - 1)
                            .clamp(widget.selected - 1, widget.selected + 1);
                        setState(() => _lens = target.toDouble());
                        if (target != widget.selected) {
                          HapticFeedback.selectionClick();
                          widget.onSelected(target);
                        }
                      },
                      onHorizontalDragCancel: () =>
                          widget.onLabelChange(null, 0),
                      child: Stack(
                        alignment: Alignment.centerLeft,
                        children: [
                          // ═══ 图标（收缩态只显示当前图标，展开态显示全部）═══
                          //   ★用 AnimatedSwitcher 让「1个图标 ↔ 5个图标」平滑过渡
                          //     而不是瞬间切换（那样很生硬）
                          AnimatedSwitcher(
                            duration:
                                Duration(milliseconds: widget.iconFadeDuration),
                            switchInCurve: Curves.easeOut,
                            switchOutCurve: Curves.easeIn,
                            // 收缩时淡出+缩小，展开时淡入+放大
                            transitionBuilder: (child, anim) {
                              return FadeTransition(
                                opacity: anim,
                                child: ScaleTransition(
                                  scale: Tween<double>(
                                    begin: widget.iconFadeScale,
                                    end: 1.0,
                                  ).animate(anim),
                                  child: child,
                                ),
                              );
                            },
                            layoutBuilder: (current, previous) => Stack(
                              alignment: Alignment.center,
                              children: [...previous, ?current],
                            ),
                            child: Row(
                            key: ValueKey<bool>(isCollapsed),
                            mainAxisAlignment: isCollapsed
                                ? MainAxisAlignment.center
                                : MainAxisAlignment.start,
                            children: List.generate(isCollapsed ? 1 : n, (k) {
                              // 收缩时只画当前 tab 那个
                              final i = isCollapsed ? widget.selected : k;
                              final on =
                                  1.0 - (_lens - i).abs().clamp(0.0, 1.0);
                              final prox =
                                  (1.0 - (_lens - i).abs()).clamp(0.0, 1.0);
                              // ★收缩态：不放大也不抬升，图标就在胶囊正中
                              final effProx = isCollapsed ? 0.0 : prox;
                              final scale = 1.0 + effProx * widget.iconScale;

                              return SizedBox(
                                // 收缩态固定 50（正方形胶囊内），展开态用格宽
                                width: isCollapsed ? collapsedSize : pitch,
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Transform.translate(
                                      offset: Offset(
                                          0, -effProx * widget.iconLift),
                                      child: Transform.scale(
                                        scale: scale,
                                        child: Icon(
                                          widget.items[i].icon,
                                          size: 24,
                                          color: Color.lerp(
                                            cs.onSurface
                                                .withValues(alpha: 0.55),
                                            cs.onSurface,
                                            on,
                                          ),
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              );
                            }),
                            ),
                          ),
                          // ═══ 选中块（带主题色的玻璃，独立采样）═══
                          //   ★收缩态隐藏：胶囊本身就是"选中"状态，
                          //   而且 left = _lens * pitch 在收缩时会算到屏幕外
                          if (!isCollapsed)
                          AnimatedPositioned(
                            duration: _dragging
                                ? Duration.zero
                                : const Duration(milliseconds: 260),
                            curve: Curves.easeOutCubic,
                            left: _lens * pitch + inset,
                            width: pitch - inset * 2,
                            top: inset,
                            bottom: inset,
                            child: LiquidGlassLayer(
                              // ★选中块必须用【独立的、低模糊】设置
                              //   否则和外层用同一套 frost → 把底下图标糊掉
                              //   原理：外层 frost 是给"底栏后面的内容"用的（大范围模糊），
                              //        选中块只需要轻微雾化，让图标透出来
                              settings: LiquidGlassSettings(
                                // 折射：略强一点，有透镜感
                                refractionHeight:
                                    widget.glass.refractionHeight * 0.85,
                                refractionAmount:
                                    widget.glass.refractionAmount * 0.9,
                                // ★关键：模糊极低（外层可能是 3-24，这里固定 0.8）
                                frost: 0.8,
                                // 高光收敛
                                highlight: widget.glass.highlight * 0.5,
                                contourStrength:
                                    widget.glass.contourStrength * 0.6,
                                contourDirectionality: 0.8,
                                bevelShadowStrength:
                                    widget.glass.bevelShadowStrength * 0.5,
                                // 色调：不用外层的 tintAmount（那个会发白）
                                tintAmount: 0.05,
                              ),
                              child: LiquidGlass(
                                appearance: LiquidGlassAppearance.ios27Clear(
                                  tint: widget.indicatorTint.withValues(
                                      alpha: widget.indicatorTintAlpha),
                                ),
                                shape: LiquidRoundedSuperellipse(
                                  borderRadius:
                                      (widget.height - inset * 2) / 2,
                                ),
                                child: const SizedBox.expand(),
                              ),
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),
            ),
          ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
