import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../core/models.dart';
import '../core/theme.dart';

/// The one control that matters.
///
/// Everything about it is deliberately oversized and single-purpose: on a
/// beach, with wet hands and glare on the screen, the user must be able to tell
/// at a glance whether their things are being watched, and arm or disarm
/// without reading anything.
class ShieldButton extends StatefulWidget {
  const ShieldButton({
    super.key,
    required this.state,
    required this.pendingRemainingMs,
    required this.onTap,
  });

  final GuardState state;
  final int pendingRemainingMs;
  final VoidCallback onTap;

  @override
  State<ShieldButton> createState() => _ShieldButtonState();
}

class _ShieldButtonState extends State<ShieldButton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2600),
  );

  @override
  void initState() {
    super.initState();
    _syncAnimation();
  }

  @override
  void didUpdateWidget(covariant ShieldButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state != widget.state) _syncAnimation();
  }

  void _syncAnimation() {
    // The ring only sweeps while the guard is actually doing something. A
    // static shield when disarmed is itself a useful signal.
    switch (widget.state) {
      case GuardState.disarmed:
        _controller.stop();
        _controller.value = 0;
      case GuardState.alarm:
      case GuardState.pending:
        _controller.duration = const Duration(milliseconds: 900);
        _controller.repeat();
      case GuardState.suspicious:
        _controller.duration = const Duration(milliseconds: 1500);
        _controller.repeat();
      case GuardState.armed:
      case GuardState.calibrating:
        _controller.duration = const Duration(milliseconds: 2600);
        _controller.repeat();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Color _colorFor(BuildContext context) {
    final status = context.status;
    return switch (widget.state) {
      GuardState.disarmed => status.disarmed,
      GuardState.calibrating => status.calibrating,
      GuardState.armed => status.armed,
      GuardState.suspicious => status.suspicious,
      GuardState.pending => status.pending,
      GuardState.alarm => status.alarm,
    };
  }

  IconData _iconFor() => switch (widget.state) {
        GuardState.disarmed => Icons.shield_outlined,
        GuardState.calibrating => Icons.radar_rounded,
        GuardState.armed => Icons.shield_rounded,
        GuardState.suspicious => Icons.search_rounded,
        GuardState.pending => Icons.pan_tool_rounded,
        GuardState.alarm => Icons.warning_rounded,
      };

  @override
  Widget build(BuildContext context) {
    final color = _colorFor(context);
    final status = context.status;
    final showCountdown =
        widget.state == GuardState.pending && widget.pendingRemainingMs > 0;

    return Semantics(
      button: true,
      label: widget.state.label,
      child: GestureDetector(
        onTap: widget.onTap,
        child: SizedBox(
          width: 236,
          height: 236,
          child: AnimatedBuilder(
            animation: _controller,
            builder: (context, child) {
              return CustomPaint(
                painter: _RingPainter(
                  progress: _controller.value,
                  color: color,
                  active: widget.state != GuardState.disarmed,
                ),
                child: child,
              );
            },
            child: Center(
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 350),
                curve: Curves.easeOut,
                width: 168,
                height: 168,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [
                      Color.lerp(color, Colors.white, 0.18)!,
                      color,
                    ],
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: color.withValues(alpha: 0.34),
                      blurRadius: 34,
                      spreadRadius: 2,
                    ),
                  ],
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (showCountdown)
                      Text(
                        '${(widget.pendingRemainingMs / 1000).ceil()}',
                        style: TextStyle(
                          fontSize: 62,
                          height: 1,
                          fontWeight: FontWeight.w800,
                          color: status.onStatus,
                        ),
                      )
                    else
                      Icon(_iconFor(), size: 62, color: status.onStatus),
                    const SizedBox(height: 8),
                    Text(
                      widget.state == GuardState.disarmed ? 'TAP TO ARM' : 'ON GUARD',
                      style: TextStyle(
                        fontSize: 13,
                        letterSpacing: 1.6,
                        fontWeight: FontWeight.w800,
                        color: status.onStatus.withValues(alpha: 0.86),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Two sweeping arcs plus an expanding halo, so movement reads as "scanning"
/// rather than "loading".
class _RingPainter extends CustomPainter {
  _RingPainter({
    required this.progress,
    required this.color,
    required this.active,
  });

  final double progress;
  final Color color;
  final bool active;

  @override
  void paint(Canvas canvas, Size size) {
    final centre = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2 - 6;

    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 6
      ..color = color.withValues(alpha: 0.16);
    canvas.drawCircle(centre, radius, track);

    if (!active) return;

    // Expanding halo.
    final haloRadius = radius * (0.72 + 0.28 * progress);
    final halo = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..color = color.withValues(alpha: (1 - progress) * 0.45);
    canvas.drawCircle(centre, haloRadius, halo);

    // Sweeping arc.
    final sweep = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 6
      ..strokeCap = StrokeCap.round
      ..color = color;
    canvas.drawArc(
      Rect.fromCircle(center: centre, radius: radius),
      progress * 2 * math.pi,
      math.pi * 0.42,
      false,
      sweep,
    );
    canvas.drawArc(
      Rect.fromCircle(center: centre, radius: radius),
      progress * 2 * math.pi + math.pi,
      math.pi * 0.42,
      false,
      sweep..color = color.withValues(alpha: 0.5),
    );
  }

  @override
  bool shouldRepaint(covariant _RingPainter oldDelegate) =>
      oldDelegate.progress != progress ||
      oldDelegate.color != color ||
      oldDelegate.active != active;
}
