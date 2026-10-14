// High Bytes POS - the web app's look (static/m/app.css): its colour tokens in light and dark, following the phone's
// setting, 48 px fields and buttons for thumbs, 12 px corners.

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

/// The tokens of app.css that Material's colour scheme has no place for.
class Palette extends ThemeExtension<Palette> {
  const Palette({
    required this.bg,
    required this.surface,
    required this.border,
    required this.text,
    required this.muted,
    required this.soft,
    required this.tintBlue,
    required this.tintGreen,
    required this.tintOrange,
    required this.tintRed,
    required this.onTintBlue,
    required this.onTintGreen,
    required this.onTintOrange,
    required this.onTintRed,
  });

  final Color bg, surface, border, text, muted, soft;
  final Color tintBlue, tintGreen, tintOrange, tintRed;
  final Color onTintBlue, onTintGreen, onTintOrange, onTintRed;

  static const light = Palette(
    bg: Color(0xFFF3F6FB),
    surface: Color(0xFFFFFFFF),
    border: Color(0xFFE3E8F1),
    text: Color(0xFF0E2A57),
    muted: Color(0xFF64748B),
    soft: Color(0xFFF7F9FC),
    tintBlue: Color(0xFFE8F0FE),
    tintGreen: Color(0xFFE3F6EE),
    tintOrange: Color(0xFFFFF1E4),
    tintRed: Color(0xFFFEE2E2),
    onTintBlue: Color(0xFF1250C4),
    onTintGreen: Color(0xFF065F46),
    onTintOrange: Color(0xFF9A3412),
    onTintRed: Color(0xFF991B1B),
  );

  static const dark = Palette(
    bg: Color(0xFF0B1220),
    surface: Color(0xFF111A2D),
    border: Color(0xFF1F2B44),
    text: Color(0xFFF1F5FB),
    muted: Color(0xFF98A6BD),
    soft: Color(0xFF0F1729),
    tintBlue: Color(0xFF172A4D),
    tintGreen: Color(0xFF12352B),
    tintOrange: Color(0xFF3A2A18),
    tintRed: Color(0xFF3D2226),
    onTintBlue: Color(0xFF93B8FB),
    onTintGreen: Color(0xFF6EE7B7),
    onTintOrange: Color(0xFFFDBA74),
    onTintRed: Color(0xFFFCA5A5),
  );

  static Palette of(BuildContext context) => Theme.of(context).extension<Palette>()!;

  /// The background and text of a note or badge: info, ok, warn, danger (and the sync states that map onto them).
  (Color, Color) tint(String kind) => switch (kind) {
        'ok' || 'signed' || 'online' => (tintGreen, onTintGreen),
        'warn' || 'pending' || 'offline' => (tintOrange, onTintOrange),
        'danger' || 'conflict' => (tintRed, onTintRed),
        _ => (tintBlue, onTintBlue),
      };

  @override
  Palette copyWith() => this;

  @override
  Palette lerp(ThemeExtension<Palette>? other, double t) => t < .5 || other is! Palette ? this : other;
}

const danger = Color(0xFFEF4444);
const mono = 'monospace';
const monoFallback = ['Menlo', 'Courier New', 'Courier'];

/// [fontFamily]: other letters than the platform's (the screens test draws with a font of the PC).
ThemeData buildTheme(Brightness brightness, {String? fontFamily}) {
  final dark = brightness == Brightness.dark;
  final p = dark ? Palette.dark : Palette.light;
  final primary = dark ? const Color(0xFF2F7CF6) : const Color(0xFF1565E0);
  final scheme = ColorScheme(
    brightness: brightness,
    primary: primary,
    onPrimary: Colors.white,
    secondary: primary,
    onSecondary: Colors.white,
    error: danger,
    onError: Colors.white,
    surface: p.surface,
    onSurface: p.text,
    outline: p.border,
    outlineVariant: p.border,
  );
  OutlineInputBorder border(Color c, [double w = 1]) =>
      OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: c, width: w));
  final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(12));
  final buttonText = TextStyle(fontFamily: fontFamily, fontSize: 16, fontWeight: FontWeight.w700);
  // The platform's own letters (Roboto on Android, the system font on iOS) in the tokens' colours
  final typography = Typography.material2021(platform: defaultTargetPlatform);
  final letters = (dark ? typography.white : typography.black).merge(typography.englishLike);
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    fontFamily: fontFamily,
    colorScheme: scheme,
    scaffoldBackgroundColor: p.bg,
    extensions: [p],
    dividerColor: p.border,
    typography: typography,
    textTheme: letters.apply(fontFamily: fontFamily, bodyColor: p.text, displayColor: p.text),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: p.surface,
      isDense: false,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      border: border(p.border),
      enabledBorder: border(p.border),
      focusedBorder: border(primary, 2),
      errorBorder: border(danger),
      focusedErrorBorder: border(danger, 2),
      hintStyle: TextStyle(color: p.muted),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
          minimumSize: const Size(48, 48), shape: shape, textStyle: buttonText, padding: const EdgeInsets.symmetric(horizontal: 18)),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
          minimumSize: const Size(48, 48),
          shape: shape,
          textStyle: buttonText,
          foregroundColor: p.text,
          backgroundColor: p.surface,
          side: BorderSide(color: p.border),
          padding: const EdgeInsets.symmetric(horizontal: 18)),
    ),
    bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: p.surface, shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(18)))),
    dialogTheme: DialogThemeData(backgroundColor: p.surface, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18))),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}
