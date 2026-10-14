// High Bytes POS - the pieces every screen is made of, as the web app draws them (app.css): the top bar with the network
// line, cards, notes, fields with their hint or error, list rows of 60 px, badges and the bottom bar of the cart.

import 'package:flutter/material.dart';

import 'controller.dart';
import 'theme.dart';

/// A screen: the top bar and network line, its content in a scrolling column (640 px at most), and an optional bottom bar.
class PosPage extends StatelessWidget {
  const PosPage({super.key, required this.c, required this.title, this.sub, this.onBack, required this.children, this.bottom});

  final PosController c;
  final String title;
  final String? sub;
  final VoidCallback? onBack;
  final List<Widget> children;
  final Widget? bottom;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Scaffold(
      backgroundColor: p.bg,
      body: SafeArea(
        bottom: bottom == null,
        child: Column(children: [
          TopBar(c: c, title: title, sub: sub, onBack: onBack),
          if (c.session != null) NetBar(c: c),
          Expanded(
            child: ListView(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: EdgeInsets.fromLTRB(16, 16, 16, bottom == null ? 32 : 24),
              children: [
                Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 640),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: _gaps(children, 14)),
                  ),
                ),
              ],
            ),
          ),
        ]),
      ),
      bottomNavigationBar: bottom,
    );
  }
}

List<Widget> _gaps(List<Widget> children, double gap) => [
      for (var i = 0; i < children.length; i++) ...[if (i > 0) SizedBox(height: gap), children[i]],
    ];

class TopBar extends StatelessWidget {
  const TopBar({super.key, required this.c, required this.title, this.sub, this.onBack});

  final PosController c;
  final String title;
  final String? sub;
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Container(
      decoration: BoxDecoration(color: p.surface, border: Border(bottom: BorderSide(color: p.border))),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(children: [
        if (onBack != null) ...[IconBox(icon: Icons.chevron_left, label: c.t('mobile.back'), onTap: onBack!), const SizedBox(width: 10)],
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
            if (sub != null && sub!.isNotEmpty)
              Text(sub!, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13, color: p.muted)),
          ]),
        ),
        if (c.session != null) ...[const SizedBox(width: 10), IconBox(icon: Icons.menu, label: c.t('mobile.menu'), onTap: () => showMenuSheet(context, c))],
      ]),
    );
  }
}

/// A 44 px square button with an icon, as the web app's .icon-btn.
class IconBox extends StatelessWidget {
  const IconBox({super.key, required this.icon, required this.label, required this.onTap});

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Semantics(
      button: true,
      label: label,
      child: Material(
        color: p.surface,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: BorderSide(color: p.border)),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onTap,
          child: SizedBox(width: 44, height: 44, child: Icon(icon, size: 24, color: p.text)),
        ),
      ),
    );
  }
}

/// "Online · everything synced" / "Offline · 3 waiting" (SYNC-02), with "Sync now" when something waits.
class NetBar extends StatelessWidget {
  const NetBar({super.key, required this.c});

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
    final (bg, fg) = p.tint(kind);
    return Semantics(
      liveRegion: true,
      child: Container(
        color: bg,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        constraints: const BoxConstraints(minHeight: 40),
        child: Row(children: [
          Container(width: 9, height: 9, decoration: BoxDecoration(color: fg, shape: BoxShape.circle)),
          const SizedBox(width: 8),
          Expanded(child: Text(label, style: TextStyle(color: fg, fontSize: 14, fontWeight: FontWeight.w600))),
          if (c.online && waiting > 0 && !c.syncing)
            OutlinedButton(
              onPressed: c.syncNow,
              style: OutlinedButton.styleFrom(
                  minimumSize: const Size(0, 36),
                  foregroundColor: fg,
                  backgroundColor: Colors.transparent,
                  side: BorderSide(color: fg),
                  textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
              child: Text(c.t('mobile.net.syncNow')),
            ),
        ]),
      ),
    );
  }
}

class CardBox extends StatelessWidget {
  const CardBox({super.key, required this.children, this.title});

  final String? title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Container(
      decoration: BoxDecoration(
        color: p.surface,
        border: Border.all(color: p.border),
        borderRadius: BorderRadius.circular(12),
        boxShadow: const [BoxShadow(color: Color(0x0D0F285A), blurRadius: 3, offset: Offset(0, 1))],
      ),
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (title != null) ...[Text(title!, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)), const SizedBox(height: 10)],
        ...children,
      ]),
    );
  }
}

/// A note: info, ok, warn or danger, with its coloured edge.
class NoteBox extends StatelessWidget {
  const NoteBox(this.kind, this.text, {super.key});

  final String kind;
  final String text;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = Palette.of(context).tint(kind);
    return Semantics(
      liveRegion: true,
      child: Container(
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10), border: Border(left: BorderSide(color: fg, width: 4))),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Text(text, style: TextStyle(color: fg, fontSize: 14)),
      ),
    );
  }
}

/// The notice of the controller, when there is one.
Widget noticeOf(PosController c) => c.notice == null ? const SizedBox.shrink() : NoteBox(c.notice!.kind, c.notice!.text);

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
      Text(label, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
      const SizedBox(height: 6),
      child,
      if (error != null) ...[
        const SizedBox(height: 6),
        Text(error!, style: const TextStyle(fontSize: 13, color: danger, fontWeight: FontWeight.w600)),
      ] else if (hint != null) ...[
        const SizedBox(height: 6),
        Text(hint!, style: TextStyle(fontSize: 13, color: p.muted)),
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

  @override
  State<BoundField> createState() => _BoundFieldState();
}

class _BoundFieldState extends State<BoundField> {
  late final TextEditingController _text = TextEditingController(text: widget.value);

  @override
  void didUpdateWidget(BoundField old) {
    super.didUpdateWidget(old);
    if (widget.value != _text.text) {
      _text.value = TextEditingValue(text: widget.value, selection: TextSelection.collapsed(offset: widget.value.length));
    }
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return TextField(
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
      style: TextStyle(fontSize: 16, fontFamily: widget.mono ? mono : null, fontFamilyFallback: widget.mono ? monoFallback : null),
      decoration: InputDecoration(
        hintText: widget.hint,
        counterText: '',
        semanticCounterText: '',
        labelText: null,
        enabledBorder: widget.invalid
            ? OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: const BorderSide(color: danger))
            : null,
      ),
    );
  }
}

class PrimaryButton extends StatelessWidget {
  const PrimaryButton(this.label, {super.key, this.onPressed});

  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => FilledButton(onPressed: onPressed, child: Text(label, textAlign: TextAlign.center));
}

class OutlineButton extends StatelessWidget {
  const OutlineButton(this.label, {super.key, this.onPressed, this.danger = false, this.icon});

  final String label;
  final VoidCallback? onPressed;
  final bool danger;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final style = danger ? OutlinedButton.styleFrom(foregroundColor: dangerColor, side: const BorderSide(color: dangerColor)) : null;
    final text = Text(label, textAlign: TextAlign.center);
    return icon == null
        ? OutlinedButton(onPressed: onPressed, style: style, child: text)
        : OutlinedButton.icon(onPressed: onPressed, style: style, icon: Icon(icon, size: 20), label: text);
  }
}

const dangerColor = danger;

/// Buttons side by side that wrap on a narrow phone, as .btn-group.
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

/// A state as a pill with its dot: pending, accepted, signed, conflict (or a note kind).
class PosBadge extends StatelessWidget {
  const PosBadge(this.kind, this.label, {super.key});

  final String kind;
  final String label;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = Palette.of(context).tint(kind);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 7, height: 7, decoration: BoxDecoration(color: fg, shape: BoxShape.circle)),
        const SizedBox(width: 6),
        Text(label, style: TextStyle(color: fg, fontSize: 12, fontWeight: FontWeight.w700)),
      ]),
    );
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
      decoration: BoxDecoration(color: p.surface, border: Border.all(color: p.border), borderRadius: BorderRadius.circular(12)),
      clipBehavior: Clip.antiAlias,
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        for (var i = 0; i < rows.length; i++) ...[if (i > 0) Divider(height: 1, thickness: 1, color: p.border), rows[i]],
      ]),
    );
  }
}

/// A row of a list: a code in mono, a line under it, something on the right; tappable when [onTap] is given.
class ItemRow extends StatelessWidget {
  const ItemRow({super.key, required this.code, required this.what, this.trailing, this.onTap, this.selected = false, this.extra = const []});

  final String code;
  final String what;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool selected;
  final List<Widget> extra;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    final body = Container(
      constraints: const BoxConstraints(minHeight: 60),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      color: selected ? p.tintBlue : null,
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(code, style: const TextStyle(fontFamily: mono, fontFamilyFallback: monoFallback, fontWeight: FontWeight.w700, fontSize: 14)),
            Text(what, maxLines: onTap == null ? 3 : 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 14, color: p.muted)),
            ...extra,
          ]),
        ),
        if (trailing != null) ...[const SizedBox(width: 12), trailing!],
      ]),
    );
    return onTap == null ? body : Material(color: Colors.transparent, child: InkWell(onTap: onTap, child: body));
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
    final style = TextStyle(fontSize: big ? 20 : 15, fontWeight: big ? FontWeight.w800 : FontWeight.w400, color: muted ? p.muted : null);
    final row = Row(children: [Expanded(child: Text(label, style: style)), const SizedBox(width: 12), Text(value, style: style)]);
    return big
        ? Container(
            padding: const EdgeInsets.only(top: 6), decoration: BoxDecoration(border: Border(top: BorderSide(color: p.border))), child: row)
        : row;
  }
}

/// The bottom bar: what is in the cart, its total, and the way on.
class CartBar extends StatelessWidget {
  const CartBar({super.key, required this.count, required this.amount, required this.action, this.onAction});

  final String count;
  final String amount;
  final String action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return Container(
      decoration: BoxDecoration(
        color: p.surface,
        border: Border(top: BorderSide(color: p.border)),
        boxShadow: const [BoxShadow(color: Color(0x1A0F285A), blurRadius: 18, offset: Offset(0, -6))],
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          // heightFactor 1: as tall as the bar's row, not the whole screen the bottom bar slot offers
          child: Center(
            heightFactor: 1,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: Row(children: [
                Expanded(
                  child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(count, style: TextStyle(fontSize: 13, color: p.muted)),
                    FittedBox(
                        fit: BoxFit.scaleDown,
                        alignment: Alignment.centerLeft,
                        child: Text(amount, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800))),
                  ]),
                ),
                const SizedBox(width: 12),
                ConstrainedBox(constraints: const BoxConstraints(minWidth: 140), child: PrimaryButton(action, onPressed: onAction)),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

/// The menu (the web app's sheet): the screens, refresh the trip, the language, close the trip, sign out.
Future<void> showMenuSheet(BuildContext context, PosController c) async {
  final hasTrip = c.bundle != null;
  final items = <(String, VoidCallback, bool)>[
    if (hasTrip) (c.t('mobile.menu.sell'), () => c.go(PosView.sell), false),
    if (hasTrip) (c.t('mobile.menu.sales'), () => c.go(PosView.sales), false),
    if (hasTrip) (c.t('mobile.menu.breakage'), () => c.go(PosView.breakage), false),
    if (hasTrip) (c.t('mobile.menu.declare'), () => c.go(PosView.declare), false),
    (c.t('mobile.menu.refresh'), c.downloadTrip, false),
    (c.t('mobile.menu.language'), c.toggleLanguage, false),
    if (hasTrip)
      (c.t('mobile.menu.closeTrip'), () => confirmThen(context, c, c.t('mobile.close.confirm'), c.t('mobile.menu.closeTrip'), c.closeTrip), true),
    (c.t('mobile.menu.signOut'), () => confirmThen(context, c, c.t('mobile.signOut.confirm'), c.t('mobile.menu.signOut'), c.signOut), true),
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
            for (final (label, action, isDanger) in items)
              InkWell(
                onTap: () => Navigator.pop(sheet, action),
                child: Container(
                  constraints: const BoxConstraints(minHeight: 56),
                  alignment: Alignment.centerLeft,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  decoration: BoxDecoration(border: Border(bottom: BorderSide(color: p.border))),
                  child: Text(label, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: isDanger ? danger : null)),
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
        Text(question, style: const TextStyle(fontSize: 16)),
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
