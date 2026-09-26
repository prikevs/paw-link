import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'pet_action.dart';

class PawLinkPet extends StatefulWidget {
  const PawLinkPet({required this.action, this.size = 300, super.key});

  final PetAction action;
  final double size;

  @override
  State<PawLinkPet> createState() => _PawLinkPetState();
}

class _PawLinkPetState extends State<PawLinkPet>
    with SingleTickerProviderStateMixin {
  late final AnimationController _animation;

  @override
  void initState() {
    super.initState();
    _animation = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2400),
    )..repeat();
  }

  @override
  void dispose() {
    _animation.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: 'Paw Link 宠物，当前动作：${widget.action.label}',
      image: true,
      child: RepaintBoundary(
        child: AnimatedBuilder(
          animation: _animation,
          builder: (context, child) {
            return CustomPaint(
              size: Size.square(widget.size),
              painter: _CatPainter(
                action: widget.action,
                phase: _animation.value * math.pi * 2,
              ),
            );
          },
        ),
      ),
    );
  }
}

class _CatPainter extends CustomPainter {
  const _CatPainter({required this.action, required this.phase});

  final PetAction action;
  final double phase;

  static const orange = Color(0xffff843d);
  static const orangeDark = Color(0xffdd5c2b);
  static const cream = Color(0xffffd18f);
  static const ink = Color(0xff392727);
  static const blue = Color(0xff4fa9bd);

  double get slow => math.sin(phase);
  double get fast => math.sin(phase * 3.2);

  @override
  void paint(Canvas canvas, Size size) {
    final scale = size.shortestSide / 300;
    canvas.save();
    canvas.scale(scale);

    final sleeping = action == PetAction.sleep;
    final bodyBob = switch (action) {
      PetAction.walk => -4.5 * fast.abs(),
      PetAction.idle => -2.2 * slow,
      PetAction.eat => 1.5 * fast.abs(),
      _ => 0.0,
    };
    final shakeAngle = action == PetAction.shake ? fast * 0.105 : 0.0;

    canvas.translate(150, 166 + bodyBob);
    canvas.rotate(shakeAngle);

    _drawGround(canvas, sleeping);
    _drawTail(canvas, sleeping);
    _drawLegs(canvas, sleeping);
    _drawBody(canvas, sleeping);
    if (action == PetAction.eat) _drawBowl(canvas);
    _drawHead(canvas, sleeping);
    if (action == PetAction.groom) _drawGroomingPaw(canvas);
    if (sleeping) _drawSleepMarks(canvas);

    canvas.restore();
  }

  void _drawGround(Canvas canvas, bool sleeping) {
    canvas.drawOval(
      Rect.fromCenter(
        center: const Offset(0, 92),
        width: sleeping ? 176 : 146,
        height: 20,
      ),
      Paint()..color = ink.withValues(alpha: 0.10),
    );
  }

  void _drawTail(Canvas canvas, bool sleeping) {
    final tailSwing = slow * (sleeping ? 0.04 : 0.18);
    canvas.save();
    canvas.translate(-57, 45);
    canvas.rotate(-0.72 + tailSwing);
    final tailPaint = Paint()
      ..color = orange
      ..strokeWidth = 25
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final path = Path()
      ..moveTo(0, 38)
      ..quadraticBezierTo(-29, 2, -5, -52);
    canvas.drawPath(path, tailPaint);
    canvas.drawLine(
      const Offset(-5, -52),
      const Offset(-1, -37),
      Paint()
        ..color = cream
        ..strokeWidth = 25
        ..strokeCap = StrokeCap.round,
    );
    canvas.restore();
  }

  void _drawBody(Canvas canvas, bool sleeping) {
    final rect = Rect.fromCenter(
      center: const Offset(0, 23),
      width: sleeping ? 150 : 126,
      height: sleeping ? 82 : 128,
    );
    canvas.drawOval(rect, Paint()..color = orange);
    canvas.drawOval(
      Rect.fromCenter(
        center: const Offset(14, 38),
        width: sleeping ? 76 : 64,
        height: sleeping ? 48 : 83,
      ),
      Paint()..color = cream.withValues(alpha: 0.75),
    );
  }

  void _drawLegs(Canvas canvas, bool sleeping) {
    if (sleeping) return;
    final stride = action == PetAction.walk ? fast * 9 : 0.0;
    _drawPaw(canvas, const Offset(-37, 71), stride);
    _drawPaw(canvas, const Offset(37, 71), -stride);
    _drawPaw(canvas, const Offset(-15, 75), -stride);
    _drawPaw(canvas, const Offset(15, 75), stride);
  }

  void _drawPaw(Canvas canvas, Offset origin, double stride) {
    final rect = RRect.fromRectAndRadius(
      Rect.fromCenter(
        center: origin.translate(0, stride),
        width: 25,
        height: 55,
      ),
      const Radius.circular(14),
    );
    canvas.drawRRect(rect, Paint()..color = orangeDark);
    canvas.drawOval(
      Rect.fromCenter(
        center: origin.translate(0, 21 + stride),
        width: 25,
        height: 17,
      ),
      Paint()..color = cream,
    );
  }

  void _drawHead(Canvas canvas, bool sleeping) {
    final eating = action == PetAction.eat;
    final y = sleeping ? 5.0 : (eating ? -10 + fast.abs() * 7 : -51.0);
    final rotation = switch (action) {
      PetAction.groom => -0.12 + slow * 0.05,
      PetAction.eat => fast * 0.035,
      PetAction.sleep => -0.10,
      _ => 0.0,
    };

    canvas.save();
    canvas.translate(5, y);
    canvas.rotate(rotation);

    _drawEar(canvas, const Offset(-34, -38), false);
    _drawEar(canvas, const Offset(34, -38), true);
    canvas.drawOval(
      Rect.fromCenter(center: Offset.zero, width: 108, height: 96),
      Paint()..color = orange,
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromCenter(center: const Offset(0, -31), width: 12, height: 35),
        const Radius.circular(7),
      ),
      Paint()..color = cream,
    );

    final blink =
        action == PetAction.sleep ||
        action == PetAction.groom ||
        (action == PetAction.idle && slow > 0.93);
    _drawEye(canvas, const Offset(-20, -5), blink);
    _drawEye(canvas, const Offset(20, -5), blink);
    _drawMuzzle(canvas);
    canvas.restore();
  }

  void _drawEar(Canvas canvas, Offset center, bool mirrored) {
    canvas.save();
    canvas.translate(center.dx, center.dy);
    if (mirrored) canvas.scale(-1, 1);
    final outer = Path()
      ..moveTo(-19, 16)
      ..lineTo(-7, -25)
      ..lineTo(20, 14)
      ..close();
    canvas.drawPath(outer, Paint()..color = orange);
    final inner = Path()
      ..moveTo(-10, 10)
      ..lineTo(-5, -11)
      ..lineTo(11, 10)
      ..close();
    canvas.drawPath(inner, Paint()..color = cream);
    canvas.restore();
  }

  void _drawEye(Canvas canvas, Offset center, bool closed) {
    if (closed) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(center: center, width: 18, height: 3),
          const Radius.circular(2),
        ),
        Paint()..color = ink,
      );
      return;
    }
    canvas.drawOval(
      Rect.fromCenter(center: center, width: 13, height: 17),
      Paint()..color = ink,
    );
    canvas.drawCircle(
      center.translate(-2, -4),
      2,
      Paint()..color = Colors.white,
    );
  }

  void _drawMuzzle(Canvas canvas) {
    final nose = Path()
      ..moveTo(-7, 15)
      ..lineTo(7, 15)
      ..lineTo(0, 23)
      ..close();
    canvas.drawPath(nose, Paint()..color = const Color(0xffc84f4b));
    final mouth = Path()
      ..moveTo(0, 23)
      ..quadraticBezierTo(-8, 33, -17, 24)
      ..moveTo(0, 23)
      ..quadraticBezierTo(8, 33, 17, 24);
    canvas.drawPath(
      mouth,
      Paint()
        ..color = ink
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round,
    );
  }

  void _drawGroomingPaw(Canvas canvas) {
    canvas.save();
    canvas.translate(41 + slow * 5, -18 - slow * 7);
    canvas.rotate(-0.38 + slow * 0.13);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(-12, -32, 24, 65),
        const Radius.circular(13),
      ),
      Paint()..color = orange,
    );
    canvas.drawCircle(const Offset(0, -29), 12, Paint()..color = cream);
    canvas.restore();
  }

  void _drawBowl(Canvas canvas) {
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(-34, 76, 82, 29),
        const Radius.circular(13),
      ),
      Paint()..color = blue,
    );
    canvas.drawOval(
      const Rect.fromLTWH(-30, 74, 74, 16),
      Paint()..color = const Color(0xff346b76),
    );
    for (final x in [-16.0, -4.0, 8.0, 20.0]) {
      canvas.drawCircle(
        Offset(x, 81),
        4,
        Paint()..color = const Color(0xff8b5c3c),
      );
    }
  }

  void _drawSleepMarks(Canvas canvas) {
    final painter = TextPainter(
      text: TextSpan(
        text: 'z\n Z',
        style: TextStyle(
          color: blue,
          fontSize: 24,
          fontWeight: FontWeight.w800,
          height: 0.85,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, Offset(61, -85 - slow * 4));
  }

  @override
  bool shouldRepaint(covariant _CatPainter oldDelegate) {
    return action != oldDelegate.action || phase != oldDelegate.phase;
  }
}
