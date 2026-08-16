import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Semantic colours for guard status.
///
/// These are separate from the [ColorScheme] because they mean something
/// specific rather than "primary" or "secondary": each one maps to exactly one
/// state of the guard, and the whole UI keys off them so a glance at any screen
/// tells you the same story as the big shield on the home screen.
@immutable
class BpStatusColors extends ThemeExtension<BpStatusColors> {
  const BpStatusColors({
    required this.disarmed,
    required this.calibrating,
    required this.armed,
    required this.suspicious,
    required this.pending,
    required this.alarm,
    required this.onStatus,
    required this.subtle,
    required this.hairline,
  });

  final Color disarmed;
  final Color calibrating;
  final Color armed;
  final Color suspicious;
  final Color pending;
  final Color alarm;

  /// Text/icon colour that stays legible on any of the above.
  final Color onStatus;

  /// Very low emphasis fill, for inactive chips and track backgrounds.
  final Color subtle;

  /// One-pixel separators and card outlines.
  final Color hairline;

  static const _lagoon = Color(0xFF0E9BB8);
  static const _lagoonDark = Color(0xFF22D3EE);
  static const _guard = Color(0xFF0F9D6E);
  static const _guardDark = Color(0xFF34D399);
  static const _amber = Color(0xFFB45309);
  static const _amberDark = Color(0xFFFBBF24);
  static const _rose = Color(0xFFD11A45);
  static const _roseDark = Color(0xFFF43F5E);

  static const light = BpStatusColors(
    disarmed: Color(0xFF64748B),
    calibrating: _lagoon,
    armed: _guard,
    suspicious: _amber,
    pending: _amber,
    alarm: _rose,
    onStatus: Colors.white,
    subtle: Color(0x14000000),
    hairline: Color(0x1A0F172A),
  );

  static const dark = BpStatusColors(
    disarmed: Color(0xFF8AA9BF),
    calibrating: _lagoonDark,
    armed: _guardDark,
    suspicious: _amberDark,
    pending: _amberDark,
    alarm: _roseDark,
    onStatus: Color(0xFF04121F),
    subtle: Color(0x1FFFFFFF),
    hairline: Color(0x24FFFFFF),
  );

  @override
  BpStatusColors copyWith({
    Color? disarmed,
    Color? calibrating,
    Color? armed,
    Color? suspicious,
    Color? pending,
    Color? alarm,
    Color? onStatus,
    Color? subtle,
    Color? hairline,
  }) {
    return BpStatusColors(
      disarmed: disarmed ?? this.disarmed,
      calibrating: calibrating ?? this.calibrating,
      armed: armed ?? this.armed,
      suspicious: suspicious ?? this.suspicious,
      pending: pending ?? this.pending,
      alarm: alarm ?? this.alarm,
      onStatus: onStatus ?? this.onStatus,
      subtle: subtle ?? this.subtle,
      hairline: hairline ?? this.hairline,
    );
  }

  @override
  BpStatusColors lerp(ThemeExtension<BpStatusColors>? other, double t) {
    if (other is! BpStatusColors) return this;
    return BpStatusColors(
      disarmed: Color.lerp(disarmed, other.disarmed, t)!,
      calibrating: Color.lerp(calibrating, other.calibrating, t)!,
      armed: Color.lerp(armed, other.armed, t)!,
      suspicious: Color.lerp(suspicious, other.suspicious, t)!,
      pending: Color.lerp(pending, other.pending, t)!,
      alarm: Color.lerp(alarm, other.alarm, t)!,
      onStatus: Color.lerp(onStatus, other.onStatus, t)!,
      subtle: Color.lerp(subtle, other.subtle, t)!,
      hairline: Color.lerp(hairline, other.hairline, t)!,
    );
  }
}

/// Convenience accessor so widgets can write `context.status.armed`.
extension BpThemeAccess on BuildContext {
  BpStatusColors get status =>
      Theme.of(this).extension<BpStatusColors>() ?? BpStatusColors.light;

  ColorScheme get colors => Theme.of(this).colorScheme;

  TextTheme get text => Theme.of(this).textTheme;
}

class BpTheme {
  BpTheme._();

  /// Both themes are built for direct sunlight, which is the entire point of
  /// the app. That means heavy weights, generous size, and contrast well past
  /// what a normal indoor app would need - washed-out mid greys are unreadable
  /// on a beach at midday.
  static ThemeData light() => _build(Brightness.light);

  static ThemeData dark() => _build(Brightness.dark);

  static ThemeData _build(Brightness brightness) {
    final isDark = brightness == Brightness.dark;
    final status = isDark ? BpStatusColors.dark : BpStatusColors.light;

    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF0E9BB8),
      brightness: brightness,
    ).copyWith(
      surface: isDark ? const Color(0xFF071A2B) : const Color(0xFFF6FAFC),
      onSurface: isDark ? const Color(0xFFE8F4FA) : const Color(0xFF0B2235),
      surfaceContainer: isDark ? const Color(0xFF0F2A42) : Colors.white,
      surfaceContainerHigh: isDark ? const Color(0xFF163A57) : const Color(0xFFEDF3F7),
      primary: isDark ? const Color(0xFF22D3EE) : const Color(0xFF0B7F97),
      error: status.alarm,
    );

    final base = ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
      splashFactory: InkSparkle.splashFactory,
    );

    return base.copyWith(
      extensions: <ThemeExtension<dynamic>>[status],
      textTheme: base.textTheme.apply(
        bodyColor: scheme.onSurface,
        displayColor: scheme.onSurface,
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        foregroundColor: scheme.onSurface,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: base.textTheme.titleLarge?.copyWith(
          fontWeight: FontWeight.w700,
          color: scheme.onSurface,
        ),
        systemOverlayStyle:
            isDark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
      ),
      cardTheme: CardThemeData(
        color: scheme.surfaceContainer,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: status.hairline),
        ),
      ),
      listTileTheme: const ListTileThemeData(
        contentPadding: EdgeInsets.symmetric(horizontal: 18, vertical: 4),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(54),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
          side: BorderSide(color: status.hairline),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
        ),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: SegmentedButton.styleFrom(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHigh,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: status.hairline),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: scheme.primary, width: 2),
        ),
      ),
      dividerTheme: DividerThemeData(color: status.hairline, space: 1, thickness: 1),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
    );
  }
}
