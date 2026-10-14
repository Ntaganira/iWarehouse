// High Bytes POS - the client's design (claude.ai design "iWarehouse Mobile POS Redesign", 2026-10-14) on the HIGH BYTES
// WMS design system: its colour tokens in light and dark, following the phone's setting; Inter for text and IBM Plex
// Mono for codes and amounts (shipped in assets/fonts); 48 px fields and buttons with 8 px corners, 10 px cards.

import 'package:flutter/material.dart';

/// The design system's colours (tokens.json of HIGH BYTES WMS, the mobile screens' HB_THEMES).
class Palette extends ThemeExtension<Palette> {
  const Palette({
    required this.bg,
    required this.card,
    required this.heading,
    required this.text,
    required this.muted,
    required this.border,
    required this.divider,
    required this.ctrl,
    required this.primary,
    required this.onPrimary,
    required this.info,
    required this.infoBg,
    required this.tile,
    required this.sel,
    required this.hdr,
    required this.blob,
    required this.hdrSub,
    required this.success,
    required this.successBg,
    required this.successFill,
    required this.warn,
    required this.warnBg,
    required this.warnFill,
    required this.danger,
    required this.dangerBg,
    required this.shadow,
  });

  final Color bg, card, heading, text, muted, border, divider, ctrl, primary, onPrimary;
  final Color info, infoBg, tile, sel, hdr, blob, hdrSub;
  final Color success, successBg, successFill, warn, warnBg, warnFill, danger, dangerBg, shadow;

  static const light = Palette(
    bg: Color(0xFFF4F8FC),
    card: Color(0xFFFFFFFF),
    heading: Color(0xFF0D2F65),
    text: Color(0xFF31577A),
    muted: Color(0xFF5A7086),
    border: Color(0xFFDCE8F2),
    divider: Color(0xFFE8F0F6),
    ctrl: Color(0xFF7590A8),
    primary: Color(0xFF0878D1),
    onPrimary: Color(0xFFFFFFFF),
    info: Color(0xFF1769B0),
    infoBg: Color(0xFFE4F2FF),
    tile: Color(0xFFE3F1FF),
    sel: Color(0xFFDDF6EA),
    hdr: Color(0xFF0668B5),
    blob: Color(0xFF004DA6),
    hdrSub: Color(0xFFD6E6F4),
    success: Color(0xFF0E8553),
    successBg: Color(0xFFDDF6EA),
    successFill: Color(0xFF18A66A),
    warn: Color(0xFFB8690F),
    warnBg: Color(0xFFFFF0DE),
    warnFill: Color(0xFFF28C28),
    danger: Color(0xFFD32535),
    dangerBg: Color(0xFFFFE1E4),
    shadow: Color(0x0F073B69), // 0 2px 10px rgba(7, 59, 105, .06)
  );

  static const dark = Palette(
    bg: Color(0xFF0B1C30),
    card: Color(0xFF12253D),
    heading: Color(0xFFEAF1F9),
    text: Color(0xFFCFDDED),
    muted: Color(0xFF93A8BD),
    border: Color(0xFF22405F),
    divider: Color(0xFF1B3350),
    ctrl: Color(0xFF5C7B9A),
    primary: Color(0xFF5AA9EA),
    onPrimary: Color(0xFF04162B),
    info: Color(0xFF6FB4EE),
    infoBg: Color(0xFF10314F),
    tile: Color(0xFF0F2C49),
    sel: Color(0xFF0D3A29),
    hdr: Color(0xFF0A3C6E),
    blob: Color(0xFF0A3255),
    hdrSub: Color(0xFFC7D9EA),
    success: Color(0xFF4ECF9A),
    successBg: Color(0xFF0D3A29),
    successFill: Color(0xFF2FBF85),
    warn: Color(0xFFE5A862),
    warnBg: Color(0xFF3A2A14),
    warnFill: Color(0xFFF5A54F),
    danger: Color(0xFFF2827F),
    dangerBg: Color(0xFF421D20),
    shadow: Color(0x73000000), // 0 2px 10px rgba(0, 0, 0, .45)
  );

  static Palette of(BuildContext context) => Theme.of(context).extension<Palette>()!;

  /// A state's colours: background, text, and the brighter dot or icon fill. Kinds: info, ok, warn, danger, and the
  /// states that map onto them (signed, pending, accepted, conflict; online, offline, syncing).
  (Color bg, Color fg, Color dot) tone(String kind) => switch (kind) {
        'ok' || 'signed' || 'online' || 'success' => (successBg, success, successFill),
        'warn' || 'pending' || 'offline' => (warnBg, warn, warnFill),
        'danger' || 'conflict' => (dangerBg, danger, danger),
        _ => (infoBg, info, primary),
      };

  /// The card's shadow (shadow-card).
  List<BoxShadow> get cardShadow => [BoxShadow(color: shadow, blurRadius: 10, offset: const Offset(0, 2))];

  @override
  Palette copyWith() => this;

  @override
  Palette lerp(ThemeExtension<Palette>? other, double t) => t < .5 || other is! Palette ? this : other;
}

const sans = 'Inter';
const mono = 'IBM Plex Mono';
const monoFallback = ['Menlo', 'Courier New', 'monospace'];

/// A code or an amount: IBM Plex Mono.
TextStyle monoStyle({double size = 14, FontWeight weight = FontWeight.w600, Color? color, double? letterSpacing}) =>
    TextStyle(fontFamily: mono, fontFamilyFallback: monoFallback, fontSize: size, fontWeight: weight, color: color, letterSpacing: letterSpacing);

/// [fontFamily]: other letters than Inter (kept for tests that draw with another font).
ThemeData buildTheme(Brightness brightness, {String? fontFamily}) {
  final dark = brightness == Brightness.dark;
  final p = dark ? Palette.dark : Palette.light;
  final family = fontFamily ?? sans;
  final scheme = ColorScheme(
    brightness: brightness,
    primary: p.primary,
    onPrimary: p.onPrimary,
    secondary: p.primary,
    onSecondary: p.onPrimary,
    error: p.danger,
    onError: Colors.white,
    surface: p.card,
    onSurface: p.heading,
    outline: p.ctrl,
    outlineVariant: p.border,
  );
  OutlineInputBorder border(Color c, [double w = 1.5]) =>
      OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: BorderSide(color: c, width: w));
  final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(8));
  final buttonText = TextStyle(fontFamily: family, fontSize: 16, fontWeight: FontWeight.w700);
  final typography = Typography.material2021();
  final letters = (dark ? typography.white : typography.black).merge(typography.englishLike);
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    fontFamily: family,
    colorScheme: scheme,
    scaffoldBackgroundColor: p.bg,
    extensions: [p],
    dividerColor: p.divider,
    typography: typography,
    textTheme: letters.apply(fontFamily: family, bodyColor: p.heading, displayColor: p.heading),
    textSelectionTheme: TextSelectionThemeData(cursorColor: p.primary, selectionHandleColor: p.primary),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: p.card,
      isDense: false,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
      border: border(p.ctrl),
      enabledBorder: border(p.ctrl),
      focusedBorder: border(p.primary, 2),
      errorBorder: border(p.danger),
      focusedErrorBorder: border(p.danger, 2),
      hintStyle: TextStyle(color: p.muted, fontFamily: family, fontSize: 15),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
          minimumSize: const Size(48, 48),
          shape: shape,
          textStyle: buttonText,
          backgroundColor: p.primary,
          foregroundColor: p.onPrimary,
          padding: const EdgeInsets.symmetric(horizontal: 18)),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
          minimumSize: const Size(48, 48),
          shape: shape,
          textStyle: buttonText,
          foregroundColor: p.heading,
          backgroundColor: p.card,
          iconColor: p.primary,
          side: BorderSide(color: p.ctrl, width: 1.5),
          padding: const EdgeInsets.symmetric(horizontal: 18)),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: p.primary, textStyle: buttonText)),
    bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: p.card,
        dragHandleColor: p.ctrl,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(18)))),
    dialogTheme: DialogThemeData(backgroundColor: p.card, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
    dropdownMenuTheme: DropdownMenuThemeData(
        textStyle: TextStyle(fontFamily: family, fontSize: 15, color: p.heading),
        menuStyle: MenuStyle(backgroundColor: WidgetStatePropertyAll(p.card))),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}
