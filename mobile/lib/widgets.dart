// High Bytes POS - the pieces every screen is made of, in the client's design (claude.ai design "iWarehouse Mobile POS
// Redesign"): the blue header with its wave, the network as a pill and an illustration; cards, notes with their icon,
// fields, tiles, list rows of 68 px, chips and the bottom bar of the sale.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'art.dart';
import 'controller.dart';
import 'theme.dart';

/// A screen: the header (title, network pill, illustration), its content in a scrolling column (640 px at most) on the
/// page's ground, and an optional bottom bar.
class PosPage extends StatelessWidget {
  const PosPage({
    super.key,
    required this.c,
    required this.title,
    this.sub,
    this.eyebrow,
    this.onBack,
    this.art,
    this.monoTitle = false,
    this.monoSub = false,
    required this.children,
    this.bottom,
  });

  final PosController c;
  final String title;
  final String? sub;
  final String? eyebrow;
  final VoidCallback? onBack;
  final Art? art;
  final bool monoTitle;
  final bool monoSub;
  final List<Widget> children;
  final Widget? bottom;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light, // white status bar icons on the blue header
      child: Scaffold(
        backgroundColor: p.bg,
        body: ListView(
          padding: EdgeInsets.zero,
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          children: [
            PosHeader(c: c, title: title, sub: sub, eyebrow: eyebrow, onBack: onBack, art: art, monoTitle: monoTitle, monoSub: monoSub),
            Padding(
              padding: EdgeInsets.fromLTRB(20, 2, 20, bottom == null ? 24 + MediaQuery.paddingOf(context).bottom : 24),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 640),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: gaps(children, 14)),
                ),
              ),
            ),
          ],
        ),
        bottomNavigationBar: bottom,
      ),
    );
  }
}

List<Widget> gaps(List<Widget> children, double gap) {
  final shown = children.where((w) => w is! SizedBox || w.width != 0 || w.height != 0).toList();
  return [
    for (var i = 0; i < shown.length; i++) ...[if (i > 0) SizedBox(height: gap), shown[i]],
  ];
}

/// The design's header: the blue ground with two darker blobs, the logo tile (or Back), the titles in white and the menu;
/// under them the network pill and the screen's illustration; a wave into the page.
class PosHeader extends StatelessWidget {
  const PosHeader({
    super.key,
    required this.c,
    required this.title,
    this.sub,
    this.eyebrow,
    this.onBack,
    this.art,
    this.monoTitle = false,
    this.monoSub = false,
  });

  final PosController c;
  final String title;
  final String? sub;
  final String? eyebrow;
  final VoidCallback? onBack;
  final Art? art;
  final bool monoTitle;
  final bool monoSub;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final top = MediaQuery.paddingOf(context).top;
    final titleStyle = monoTitle
        ? monoStyle(size: 19, weight: FontWeight.w700, color: Colors.white)
        : const TextStyle(fontSize: 22, height: 28 / 22, fontWeight: FontWeight.w800, letterSpacing: -0.22, color: Colors.white);
    final subStyle = monoSub ? monoStyle(size: 13, weight: FontWeight.w500, color: p.hdrSub) : TextStyle(fontSize: 14, height: 20 / 14, color: p.hdrSub);
    return HeaderGround(
      wave: p.bg,
      child: Padding(
        padding: EdgeInsets.fromLTRB(20, 18 + top, 20, 46),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(crossAxisAlignment: eyebrow == null ? CrossAxisAlignment.center : CrossAxisAlignment.start, children: [
            if (onBack != null) HeaderButton(icon: Icons.chevron_left, label: c.t('mobile.back'), onTap: onBack!) else const LogoTile(),
            const SizedBox(width: 12),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                if (eyebrow != null) ...[
                  Text(eyebrow!.toUpperCase(), style: TextStyle(fontSize: 12, height: 16 / 12, fontWeight: FontWeight.w600, letterSpacing: 0.6, color: p.hdrSub)),
                  const SizedBox(height: 4),
                ],
                Text(title, maxLines: 2, overflow: TextOverflow.ellipsis, style: titleStyle),
                if (sub != null && sub!.isNotEmpty) ...[
                  SizedBox(height: eyebrow == null ? 2 : 4),
                  Text(sub!, maxLines: 2, overflow: TextOverflow.ellipsis, style: subStyle),
                ],
              ]),
            ),
            if (c.session != null) ...[
              const SizedBox(width: 12),
              HeaderButton(icon: Icons.menu, label: c.t('mobile.menu'), onTap: () => showMenuSheet(context, c)),
            ],
          ]),
          if (c.session != null || art != null) ...[
            const SizedBox(height: 16),
            Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Expanded(child: Align(alignment: Alignment.bottomLeft, child: c.session != null ? NetPill(c: c) : const SizedBox.shrink())),
              if (art != null) ...[const SizedBox(width: 8), ArtView(art!)],
            ]),
          ],
        ]),
      ),
    );
  }
}

/// The header's blue ground: two blobs a step darker, and the wave into the page (its colour [wave]).
class HeaderGround extends StatelessWidget {
  const HeaderGround({super.key, required this.child, required this.wave, this.waveHeight = 40, this.signIn = false});

  final Widget child;
  final Color wave;
  final double waveHeight;
  final bool signIn;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return ClipRect(
      child: CustomPaint(
        painter: _GroundPainter(ground: p.hdr, blob: p.blob, wave: wave, waveHeight: waveHeight, signIn: signIn),
        child: child,
      ),
    );
  }
}

class _GroundPainter extends CustomPainter {
  _GroundPainter({required this.ground, required this.blob, required this.wave, required this.waveHeight, required this.signIn});

  final Color ground, blob, wave;
  final double waveHeight;
  final bool signIn;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = ground);
    final w = size.width;
    if (signIn) {
      canvas.drawCircle(const Offset(40, 20), 110, Paint()..color = blob.withValues(alpha: .55));
      canvas.drawCircle(Offset(w + 10, 250), 120, Paint()..color = blob.withValues(alpha: .45));
    } else {
      canvas.drawCircle(const Offset(30, 0), 100, Paint()..color = blob.withValues(alpha: .55));
      canvas.drawCircle(Offset(w + 20, 180), 110, Paint()..color = blob.withValues(alpha: .45));
    }
    // The design's wave, drawn on 390 px and stretched to the width
    final sx = w / 390;
    final y0 = size.height - waveHeight + 1;
    final path = Path();
    if (signIn) {
      path
        ..moveTo(0, y0 + 26)
        ..cubicTo(64 * sx, y0 + 2, 132 * sx, y0, 204 * sx, y0 + 20)
        ..cubicTo(276 * sx, y0 + 40, 338 * sx, y0 + 40, w, y0 + 16);
    } else {
      path
        ..moveTo(0, y0 + 24)
        ..cubicTo(70 * sx, y0, 140 * sx, y0 + 2, 210 * sx, y0 + 18)
        ..cubicTo(280 * sx, y0 + 34, 340 * sx, y0 + 34, w, y0 + 12);
    }
    path
      ..lineTo(w, size.height)
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(path, Paint()..color = wave);
  }

  @override
  bool shouldRepaint(_GroundPainter old) => old.ground != ground || old.blob != blob || old.wave != wave || old.signIn != signIn;
}

/// A 44 px translucent button on the header: Back, the menu.
class HeaderButton extends StatelessWidget {
  const HeaderButton({super.key, required this.icon, required this.label, required this.onTap});

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        label: label,
        excludeSemantics: true,
        child: Material(
          color: Colors.white.withValues(alpha: .12),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10), side: BorderSide(color: Colors.white.withValues(alpha: .28))),
          child: InkWell(
            borderRadius: BorderRadius.circular(10),
            onTap: onTap,
            child: SizedBox(width: 44, height: 44, child: Icon(icon, size: 24, color: Colors.white)),
          ),
        ),
      );
}

/// The High Bytes mark, shipped with the app (the web app's static/images/logo-mark.png).
const brandMark = 'assets/brand/logo-mark.png';

/// The mark on its 44 px white tile, at the head of the header.
class LogoTile extends StatelessWidget {
  const LogoTile({super.key});

  @override
  Widget build(BuildContext context) => Container(
        width: 44,
        height: 44,
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(10)),
        child: Image.asset(brandMark, fit: BoxFit.contain),
      );
}

/// The mark on a white tile, larger (the splash screen).
class BrandMark extends StatelessWidget {
  const BrandMark({super.key, this.size = 72});

  final double size;

  @override
  Widget build(BuildContext context) => Center(
        child: Container(
          width: size,
          height: size,
          padding: EdgeInsets.all(size / 12),
          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(size * 0.22), border: Border.all(color: Palette.of(context).border)),
          child: Image.asset(brandMark, fit: BoxFit.contain),
        ),
      );
}

/// "Online · everything synced" / "Offline · 3 waiting to sync" (SYNC-02) as a pill; tapping it sends what waits.
class NetPill extends StatelessWidget {
  const NetPill({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final waiting = c.pendingCount;
    final kind = !c.online ? 'offline' : waiting > 0 ? 'info' : 'online';
    final label = !c.online
        ? (waiting > 0 ? c.t('mobile.net.offlinePending', [waiting]) : c.t('mobile.net.offline'))
        : c.syncing
            ? c.t('mobile.net.syncing', [waiting])
            : waiting > 0
                ? c.t('mobile.net.pending', [waiting])
                : c.t('mobile.net.online');
    final canSync = c.online && waiting > 0 && !c.syncing;
    final (bg, fg, dot) = p.tone(kind);
    final style = TextStyle(color: fg, fontSize: 13, height: 18 / 13, fontWeight: FontWeight.w600);
    final pill = Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 8, height: 8, decoration: BoxDecoration(color: dot, shape: BoxShape.circle)),
        const SizedBox(width: 8),
        Flexible(child: Text(label, style: style)),
        if (canSync) ...[
          const SizedBox(width: 8),
          Text(c.t('mobile.net.syncNow'), style: style.copyWith(fontWeight: FontWeight.w800, decoration: TextDecoration.underline, decorationColor: fg)),
        ],
      ]),
    );
    return Semantics(
      liveRegion: true,
      button: canSync,
      child: canSync ? InkWell(borderRadius: BorderRadius.circular(999), onTap: c.syncNow, child: pill) : pill,
    );
  }
}

/// A card: white (navy in dark), its edge and the design's one shadow.
class CardBox extends StatelessWidget {
  const CardBox({super.key, required this.children, this.title, this.padding = 16, this.gap = 12});

  final String? title;
  final List<Widget> children;
  final double padding;
  final double gap;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Container(
      decoration: BoxDecoration(color: p.card, border: Border.all(color: p.border), borderRadius: BorderRadius.circular(10), boxShadow: p.cardShadow),
      padding: EdgeInsets.all(padding),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (title != null) ...[CardTitle(title!), SizedBox(height: gap)],
        ...children,
      ]),
    );
  }
}

/// A card's or a section's heading (card-title).
class CardTitle extends StatelessWidget {
  const CardTitle(this.text, {super.key, this.trailing});

  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final title = Text(text, style: TextStyle(fontSize: 16, height: 22 / 16, fontWeight: FontWeight.w700, color: Palette.of(context).heading));
    return trailing == null ? title : Row(children: [title, const SizedBox(width: 8), trailing!]);
  }
}

/// The small line in capitals above a figure (eyebrow).
class Eyebrow extends StatelessWidget {
  const Eyebrow(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Text(text.toUpperCase(),
      style: TextStyle(fontSize: 12, height: 16 / 12, fontWeight: FontWeight.w600, letterSpacing: 0.6, color: Palette.of(context).muted));
}

/// A note: info, ok, warn or danger, with its icon.
class NoteBox extends StatelessWidget {
  const NoteBox(this.kind, this.text, {super.key, this.monoLead});

  final String kind;
  final String text;
  final String? monoLead; // a code the note starts with, in mono

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final (bg, fg, dot) = p.tone(kind);
    final ok = kind == 'ok' || kind == 'success';
    final icon = ok
        ? Container(
            width: 22,
            height: 22,
            decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
            child: const Icon(Icons.check_rounded, size: 15, color: Colors.white))
        : Icon(switch (kind) { 'warn' => Icons.error_outline, 'danger' => Icons.highlight_off, _ => Icons.info_outline }, size: 20, color: fg);
    final style = TextStyle(color: fg, fontSize: 14, height: 20 / 14, fontWeight: FontWeight.w500);
    return Semantics(
      liveRegion: true,
      child: Container(
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10)),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(padding: const EdgeInsets.only(top: 0), child: icon),
          const SizedBox(width: 10),
          Expanded(
            child: monoLead == null || !text.startsWith(monoLead!)
                ? Text(text, style: style)
                : Text.rich(TextSpan(style: style, children: [
                    TextSpan(text: monoLead, style: monoStyle(size: 14, weight: FontWeight.w600, color: fg)),
                    TextSpan(text: text.substring(monoLead!.length)),
                  ])),
          ),
        ]),
      ),
    );
  }
}

/// The notice of the controller, when there is one; a code it starts with in mono ("U-WH-000002 added to the sale").
Widget noticeOf(PosController c) {
  final n = c.notice;
  if (n == null) return const SizedBox.shrink();
  final code = RegExp(r'^[A-Z]+-[A-Z]+-[0-9-]+').firstMatch(n.text)?.group(0);
  return NoteBox(n.kind, n.text, monoLead: code);
}

/// A field: its label, the input, then its error or hint.
class FieldBox extends StatelessWidget {
  const FieldBox({super.key, required this.label, required this.child, this.hint, this.error});

  final String label;
  final Widget child;
  final String? hint;
  final String? error;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(label, style: TextStyle(fontSize: 13, height: 18 / 13, fontWeight: FontWeight.w600, color: p.heading)),
      const SizedBox(height: 6),
      child,
      if (error != null) ...[
        const SizedBox(height: 6),
        Text(error!, style: TextStyle(fontSize: 13, color: p.danger, fontWeight: FontWeight.w600)),
      ] else if (hint != null) ...[
        const SizedBox(height: 6),
        Text(hint!, style: TextStyle(fontSize: 12, height: 16 / 12, color: p.muted)),
      ],
    ]);
  }
}

/// A text input whose text follows a value the controller may change (all in cash, the customer's TIN), the cursor kept.
class BoundField extends StatefulWidget {
  const BoundField({
    super.key,
    required this.value,
    required this.onChanged,
    this.keyboard,
    this.invalid = false,
    this.hint,
    this.maxLength,
    this.obscure = false,
    this.caps = TextCapitalization.none,
    this.mono = false,
    this.autofill,
    this.onSubmitted,
    this.action,
    this.semantics,
    this.icon,
    this.underline = false,
    this.suffix,
    this.formatters,
    this.style,
    this.height,
  });

  final String value;
  final ValueChanged<String> onChanged;
  final TextInputType? keyboard;
  final bool invalid;
  final String? hint;
  final int? maxLength;
  final bool obscure;
  final TextCapitalization caps;
  final bool mono;
  final Iterable<String>? autofill;
  final ValueChanged<String>? onSubmitted;
  final TextInputAction? action;
  final String? semantics;
  final IconData? icon; // leading icon
  final bool underline; // the sign-in screen's underlined fields
  final Widget? suffix;
  final List<TextInputFormatter>? formatters;
  final TextStyle? style;
  final double? height;

  @override
  State<BoundField> createState() => _BoundFieldState();
}

class _BoundFieldState extends State<BoundField> {
  late final TextEditingController _text = TextEditingController(text: _shown(widget.value));

  /// What the field shows for the value: the value through the formatters (grouped digits for the declared cash).
  String _shown(String value) {
    var v = TextEditingValue(text: value, selection: TextSelection.collapsed(offset: value.length));
    for (final f in widget.formatters ?? const <TextInputFormatter>[]) {
      v = f.formatEditUpdate(TextEditingValue.empty, v);
    }
    return v.text;
  }

  @override
  void didUpdateWidget(BoundField old) {
    super.didUpdateWidget(old);
    final shown = _shown(widget.value);
    if (shown != _text.text) {
      _text.value = TextEditingValue(text: shown, selection: TextSelection.collapsed(offset: shown.length));
    }
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    UnderlineInputBorder line(Color c, double w) => UnderlineInputBorder(borderSide: BorderSide(color: c, width: w));
    OutlineInputBorder box(Color c, double w) => OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: BorderSide(color: c, width: w));
    final base = widget.style ??
        (widget.mono ? monoStyle(size: 15, weight: FontWeight.w500, color: p.heading) : TextStyle(fontSize: 15, color: p.heading));
    final field = TextField(
      controller: _text,
      onChanged: widget.onChanged,
      onSubmitted: widget.onSubmitted,
      keyboardType: widget.keyboard,
      textInputAction: widget.action,
      obscureText: widget.obscure,
      autocorrect: false,
      enableSuggestions: !widget.obscure && widget.keyboard == null,
      textCapitalization: widget.caps,
      maxLength: widget.maxLength,
      autofillHints: widget.autofill,
      inputFormatters: widget.formatters,
      style: base,
      decoration: InputDecoration(
        hintText: widget.hint,
        hintStyle: TextStyle(color: p.muted, fontSize: 15, fontFamily: sans),
        counterText: '',
        semanticCounterText: '',
        prefixIcon: widget.icon == null ? null : Icon(widget.icon, size: 20, color: p.muted),
        prefixIconConstraints: widget.underline ? const BoxConstraints(minWidth: 30, minHeight: 44) : null,
        suffixIcon: widget.suffix == null ? null : Align(alignment: Alignment.center, widthFactor: 1, child: widget.suffix),
        suffixIconConstraints: widget.height == null ? null : BoxConstraints(minHeight: widget.height! - 4),
        filled: !widget.underline,
        contentPadding: widget.underline
            ? const EdgeInsets.symmetric(vertical: 12)
            // a taller field (the declared cash, 68 px): its larger text centred
            : EdgeInsets.symmetric(horizontal: 16, vertical: widget.height == null ? 13 : (widget.height! - (base.fontSize ?? 15) * 1.2) / 2 - 2),
        enabledBorder: widget.underline
            ? line(widget.invalid ? p.danger : p.ctrl, 1.5)
            : widget.invalid
                ? box(p.danger, 1.5)
                : null,
        focusedBorder: widget.underline ? line(widget.invalid ? p.danger : p.primary, 2) : null,
        border: widget.underline ? line(p.ctrl, 1.5) : null,
      ),
    );
    return field;
  }
}

/// Digits grouped by thousands as they are typed ("45,920"); the controller keeps the digits alone.
class GroupedDigits extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final digits = newValue.text.replaceAll(RegExp(r'[^0-9]'), '');
    final out = StringBuffer();
    for (var i = 0; i < digits.length; i++) {
      if (i > 0 && (digits.length - i) % 3 == 0) out.write(',');
      out.write(digits[i]);
    }
    final text = out.toString();
    return TextEditingValue(text: text, selection: TextSelection.collapsed(offset: text.length));
  }
}

class PrimaryButton extends StatelessWidget {
  const PrimaryButton(this.label, {super.key, this.onPressed, this.big = false});

  final String label;
  final VoidCallback? onPressed;
  final bool big; // the design's 52 px action with 10 px corners

  @override
  Widget build(BuildContext context) => FilledButton(
        onPressed: onPressed,
        style: big
            ? FilledButton.styleFrom(
                minimumSize: const Size(48, 52),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                textStyle: const TextStyle(fontFamily: sans, fontSize: 17, fontWeight: FontWeight.w700))
            : null,
        child: Text(label, textAlign: TextAlign.center),
      );
}

class OutlineButton extends StatelessWidget {
  const OutlineButton(this.label, {super.key, this.onPressed, this.danger = false, this.icon});

  final String label;
  final VoidCallback? onPressed;
  final bool danger;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final style = danger ? OutlinedButton.styleFrom(foregroundColor: p.danger, iconColor: p.danger, side: BorderSide(color: p.danger, width: 1.5)) : null;
    final text = Text(label, textAlign: TextAlign.center);
    return icon == null
        ? OutlinedButton(onPressed: onPressed, style: style, child: text)
        : OutlinedButton.icon(onPressed: onPressed, style: style, icon: Icon(icon, size: 20), label: text);
  }
}

/// A 48 px square outlined button with an icon in the action colour (the camera beside Add, a line's remove).
class IconBox extends StatelessWidget {
  const IconBox({super.key, required this.icon, required this.label, required this.onTap, this.size = 48});

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final double size;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Semantics(
      button: true,
      label: label,
      excludeSemantics: true,
      child: Material(
        color: p.card,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8), side: BorderSide(color: p.ctrl, width: 1.5)),
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: onTap,
          child: SizedBox(width: size, height: size, child: Icon(icon, size: 24, color: p.primary)),
        ),
      ),
    );
  }
}

/// Buttons side by side that wrap on a narrow phone.
class ButtonRow extends StatelessWidget {
  const ButtonRow({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, box) {
        final perRow = box.maxWidth >= 300 ? children.length : 1;
        final width = (box.maxWidth - 10 * (perRow - 1)) / perRow;
        return Wrap(spacing: 10, runSpacing: 10, children: [for (final c in children) SizedBox(width: width, child: c)]);
      });
}

/// A state as a chip with its dot: pending, accepted, signed, conflict (or a note kind).
class PosBadge extends StatelessWidget {
  const PosBadge(this.kind, this.label, {super.key});

  final String kind;
  final String label;

  @override
  Widget build(BuildContext context) {
    final (bg, fg, dot) = Palette.of(context).tone(kind);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 7, height: 7, decoration: BoxDecoration(color: dot, shape: BoxShape.circle)),
        const SizedBox(width: 6),
        Text(label, style: TextStyle(color: fg, fontSize: 12, height: 16 / 12, fontWeight: FontWeight.w600)),
      ]),
    );
  }
}

/// A count beside a heading ("On board 3").
class CountChip extends StatelessWidget {
  const CountChip(this.count, {super.key});

  final int count;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 1),
      decoration: BoxDecoration(color: p.tile, borderRadius: BorderRadius.circular(999)),
      child: Text('$count', style: monoStyle(size: 13, weight: FontWeight.w600, color: p.info)),
    );
  }
}

/// A figure on a tinted tile: its name, then the figure in mono (info: the blue tile; warn, ok: their colours).
class StatTile extends StatelessWidget {
  const StatTile(this.label, this.value, {super.key, this.kind = 'tile'});

  final String label;
  final String value;
  final String kind;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final (bg, label0, value0) = kind == 'tile' ? (p.tile, p.text, p.heading) : (p.tone(kind).$1, p.tone(kind).$2, p.tone(kind).$2);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(8)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, maxLines: 2, style: TextStyle(fontSize: 13, height: 18 / 13, color: label0)),
        const SizedBox(height: 2),
        FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(value, style: monoStyle(size: 20, weight: FontWeight.w700, color: value0))),
      ]),
    );
  }
}

/// Tiles side by side, as the design's two-column grid.
class TileRow extends StatelessWidget {
  const TileRow({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => IntrinsicHeight(
        child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (var i = 0; i < children.length; i++) ...[if (i > 0) const SizedBox(width: 10), Expanded(child: children[i])],
        ]),
      );
}

/// An amount in mono with "RWF" after it in smaller grey letters.
class Amount extends StatelessWidget {
  const Amount(this.value, {super.key, this.size = 24, this.weight = FontWeight.w700});

  final String value;
  final double size;
  final FontWeight weight;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Text.rich(TextSpan(children: [
      TextSpan(text: value, style: monoStyle(size: size, weight: weight, color: p.heading, letterSpacing: -size * .02)),
      TextSpan(text: ' RWF', style: TextStyle(fontFamily: sans, fontSize: size * .5 + 2, fontWeight: FontWeight.w600, color: p.muted)),
    ]));
  }
}

/// The rows of a list in one bordered box.
class ListBox extends StatelessWidget {
  const ListBox({super.key, required this.children, this.empty});

  final List<Widget> children;
  final String? empty;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final rows = children.isEmpty && empty != null
        ? [Padding(padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24), child: Text(empty!, textAlign: TextAlign.center, style: TextStyle(color: p.muted)))]
        : children;
    return Container(
      decoration: BoxDecoration(color: p.card, border: Border.all(color: p.border), borderRadius: BorderRadius.circular(10)),
      clipBehavior: Clip.antiAlias,
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        for (var i = 0; i < rows.length; i++) ...[if (i > 0) Divider(height: 1, thickness: 1, color: p.divider), rows[i]],
      ]),
    );
  }
}

/// A row of a list: an optional leading mark, a code in mono, a line under it, something on the right; tappable when
/// [onTap] is given.
class ItemRow extends StatelessWidget {
  const ItemRow({super.key, required this.code, required this.what, this.trailing, this.onTap, this.selected = false, this.extra = const [], this.leading});

  final String code;
  final String what;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool selected;
  final List<Widget> extra;
  final Widget? leading;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final body = Container(
      constraints: const BoxConstraints(minHeight: 68),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      color: selected ? p.sel : null,
      child: Row(children: [
        if (leading != null) ...[leading!, const SizedBox(width: 12)],
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(code, style: monoStyle(size: 14, weight: FontWeight.w600, color: p.heading)),
            const SizedBox(height: 3),
            Text(what, maxLines: onTap == null ? 3 : 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13, height: 18 / 13, color: p.muted)),
            ...extra,
          ]),
        ),
        if (trailing != null) ...[const SizedBox(width: 12), trailing!],
      ]),
    );
    return onTap == null
        ? body
        : Semantics(selected: selected, button: true, child: Material(color: Colors.transparent, child: InkWell(onTap: onTap, child: body)));
  }
}

/// The round check of a unit in the sale, or its empty ring.
class CheckMark extends StatelessWidget {
  const CheckMark({super.key, required this.on});

  final bool on;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return on
        ? Container(
            width: 26,
            height: 26,
            decoration: BoxDecoration(color: p.successFill, shape: BoxShape.circle),
            child: const Icon(Icons.check_rounded, size: 17, color: Colors.white))
        : Container(width: 26, height: 26, decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: p.ctrl, width: 2)));
  }
}

/// An amount on a list row: the figure in mono, "RWF" under it.
class RowAmount extends StatelessWidget {
  const RowAmount(this.value, {super.key, this.weight = FontWeight.w600, this.below});

  final String value;
  final FontWeight weight;
  final Widget? below;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.end, mainAxisSize: MainAxisSize.min, children: [
      Text(value, style: monoStyle(size: 15, weight: weight, color: p.heading)),
      if (below != null) ...[const SizedBox(height: 6), below!] else Text('RWF', style: TextStyle(fontSize: 11, height: 14 / 11, fontWeight: FontWeight.w600, color: p.muted)),
    ]);
  }
}

/// A coloured square with an icon, at the head of a row (a sale's state).
class IconTile extends StatelessWidget {
  const IconTile({super.key, required this.icon, required this.kind, this.size = 40, this.round = false});

  final IconData icon;
  final String kind;
  final double size;
  final bool round;

  @override
  Widget build(BuildContext context) {
    final (bg, fg, _) = Palette.of(context).tone(kind);
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: bg, borderRadius: round ? null : BorderRadius.circular(10), shape: round ? BoxShape.circle : BoxShape.rectangle),
      child: Icon(icon, size: size / 2, color: fg),
    );
  }
}

/// A label and a value on one line; [big] for the total.
class TotalRow extends StatelessWidget {
  const TotalRow(this.label, this.value, {super.key, this.big = false, this.muted = false});

  final String label;
  final String value;
  final bool big;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final color = muted ? p.muted : null;
    final labelStyle = TextStyle(fontSize: big ? 18 : 14, height: 21 / 14, fontWeight: big ? FontWeight.w800 : FontWeight.w400, color: color ?? (big ? p.heading : p.text));
    final valueStyle = monoStyle(size: big ? 20 : 14, weight: big ? FontWeight.w700 : FontWeight.w600, color: color ?? p.heading);
    final row = Row(children: [Expanded(child: Text(label, style: labelStyle)), const SizedBox(width: 12), Text(value, style: valueStyle)]);
    return big
        ? Container(padding: const EdgeInsets.only(top: 8), decoration: BoxDecoration(border: Border(top: BorderSide(color: p.divider))), child: row)
        : row;
  }
}

/// The bottom bar: what is in the sale, its total, and the way on.
class CartBar extends StatelessWidget {
  const CartBar({super.key, required this.count, required this.amount, required this.action, this.onAction});

  final String count;
  final String amount; // the figure; "RWF" follows it
  final String action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Container(
      decoration: BoxDecoration(
        color: p.card,
        border: Border(top: BorderSide(color: p.border)),
        boxShadow: const [BoxShadow(color: Color(0x14073B69), blurRadius: 16, offset: Offset(0, -4))],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
          // heightFactor 1: as tall as the bar's row, not the whole screen the bottom bar slot offers
          child: Center(
            heightFactor: 1,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: Row(children: [
                Expanded(
                  flex: 2,
                  child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(count, style: TextStyle(fontSize: 13, height: 18 / 13, color: p.muted)),
                    const SizedBox(height: 2),
                    FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: Amount(amount)),
                  ]),
                ),
                const SizedBox(width: 16),
                // At most three fifths of the bar: a long label ("Complete the sale") wraps rather than pushing out
                Flexible(
                  flex: 3,
                  child: FilledButton(
                    onPressed: onAction,
                    style: FilledButton.styleFrom(
                        minimumSize: const Size(140, 52),
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                        textStyle: const TextStyle(fontFamily: sans, fontSize: 17, fontWeight: FontWeight.w700)),
                    child: Text(action, textAlign: TextAlign.center, maxLines: 2),
                  ),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

/// The menu: the screens, refresh the trip, the language, close the trip, sign out.
Future<void> showMenuSheet(BuildContext context, PosController c) async {
  final hasTrip = c.bundle != null;
  final items = <(String, IconData, VoidCallback, bool)>[
    if (hasTrip) (c.t('mobile.menu.day'), Icons.home_outlined, () => c.go(PosView.day), false),
    if (hasTrip) (c.t('mobile.menu.sell'), Icons.qr_code_scanner, () => c.go(PosView.sell), false),
    if (hasTrip) (c.t('mobile.menu.sales'), Icons.receipt_long_outlined, () => c.go(PosView.sales), false),
    if (hasTrip) (c.t('mobile.menu.breakage'), Icons.report_gmailerrorred, () => c.go(PosView.breakage), false),
    if (hasTrip) (c.t('mobile.menu.declare'), Icons.payments_outlined, () => c.go(PosView.declare), false),
    (c.t('mobile.menu.refresh'), Icons.sync, c.downloadTrip, false),
    (c.t('mobile.menu.language'), Icons.language, c.toggleLanguage, false),
    if (hasTrip)
      (c.t('mobile.menu.closeTrip'), Icons.inventory_2_outlined, () => confirmThen(context, c, c.t('mobile.close.confirm'), c.t('mobile.menu.closeTrip'), c.closeTrip), true),
    (c.t('mobile.menu.signOut'), Icons.logout, () => confirmThen(context, c, c.t('mobile.signOut.confirm'), c.t('mobile.menu.signOut'), c.signOut), true),
  ];
  final chosen = await showModalBottomSheet<VoidCallback>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true, // as tall as its items (else 9/16 of the screen, and Sign out below the fold); scrolls on a small phone
    builder: (sheet) {
      final p = Palette.of(sheet);
      return SafeArea(
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            for (final (label, icon, action, isDanger) in items)
              InkWell(
                onTap: () => Navigator.pop(sheet, action),
                child: Container(
                  constraints: const BoxConstraints(minHeight: 56),
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  decoration: BoxDecoration(border: Border(bottom: BorderSide(color: p.divider))),
                  child: Row(children: [
                    Icon(icon, size: 22, color: isDanger ? p.danger : p.primary),
                    const SizedBox(width: 14),
                    Expanded(child: Text(label, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: isDanger ? p.danger : p.heading))),
                  ]),
                ),
              ),
          ]),
        ),
      );
    },
  );
  chosen?.call();
}

/// Asks before something that cannot be undone; runs [action] on yes.
Future<void> confirmThen(BuildContext context, PosController c, String question, String yes, Future<void> Function() action) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (dialog) => AlertDialog(
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(question, style: TextStyle(fontSize: 16, height: 1.4, color: Palette.of(dialog).heading)),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(child: OutlineButton(c.t('mobile.notNow'), onPressed: () => Navigator.pop(dialog, false))),
          const SizedBox(width: 10),
          Expanded(child: OutlineButton(yes, danger: true, onPressed: () => Navigator.pop(dialog, true))),
        ]),
      ]),
    ),
  );
  if (ok == true) await action();
}

/// "13/10/2026 14:05" in the phone's time (NFR-15).
String dateTime(String iso) {
  final d = DateTime.tryParse(iso)?.toLocal();
  if (d == null) return iso;
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(d.day)}/${two(d.month)}/${d.year} ${two(d.hour)}:${two(d.minute)}';
}
