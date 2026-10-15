// High Bytes POS - counting what is on board (SDP 6), as countView in app.js: every label scanned with the camera or typed;
// what is not scanned yet and what is not expected as it goes; kept on the phone until it syncs, then the office's
// comparison (found, missing, not expected), and anything missing is told to the office at once.

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../device.dart';
import '../theme.dart';
import '../widgets.dart';

class CountScreen extends StatefulWidget {
  const CountScreen({super.key, required this.c});

  final PosController c;

  @override
  State<CountScreen> createState() => _CountScreenState();
}

class _CountScreenState extends State<CountScreen> {
  final TextEditingController _code = TextEditingController();
  final FocusNode _codeFocus = FocusNode();

  @override
  void dispose() {
    _code.dispose();
    _codeFocus.dispose();
    super.dispose();
  }

  void _add() {
    widget.c.countCode(_code.text);
    _code.clear();
  }

  Future<void> _camera() async {
    final code = await scanLabel(context, widget.c);
    if (code != null) widget.c.countCode(code);
  }

  void _save() {
    final c = widget.c;
    FocusScope.of(context).unfocus();
    if (!c.countReady()) return;
    final question = c.countQuestion();
    if (question == null) {
      c.saveCount();
    } else {
      confirmThen(context, c, question, c.t('mobile.count.save'), c.saveCount);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    final b = c.bundle!;
    final e = c.errors;
    final p = Palette.of(context);
    final expected = c.unitsExpected;
    final codes = {for (final u in expected) u.code};
    final scanned = c.counted.toSet();
    final left = expected.where((u) => !scanned.contains(u.code)).toList();
    final extra = c.counted.where((code) => !codes.contains(code)).toList();
    final found = c.counted.length - extra.length;
    final allFound = expected.isNotEmpty && left.isEmpty;
    return PosPage(
      c: c,
      title: c.t('mobile.count.title'),
      sub: '${b.trip.number} · ${b.trip.vehicle.plate}',
      monoSub: true,
      art: Art.truck,
      onBack: () => c.go(PosView.day),
      children: [
        noticeOf(c),
        CardBox(title: c.t('mobile.count.scanTitle'), children: [
          Text(c.t('mobile.count.hint'), style: TextStyle(color: p.muted, fontSize: 14, height: 20 / 14)),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(
              child: TextField(
                controller: _code,
                focusNode: _codeFocus,
                autocorrect: false,
                enableSuggestions: false,
                textCapitalization: TextCapitalization.characters,
                textInputAction: TextInputAction.go,
                onSubmitted: (_) {
                  _add();
                  _codeFocus.requestFocus();
                },
                style: monoStyle(size: 15, weight: FontWeight.w500, color: p.heading),
                decoration: InputDecoration(
                    hintText: c.t('mobile.sell.scanPh'),
                    hintStyle: monoStyle(size: 15, weight: FontWeight.w500, color: p.muted),
                    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13)),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton(
              onPressed: _add,
              style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 18), textStyle: const TextStyle(fontFamily: sans, fontSize: 15, fontWeight: FontWeight.w700)),
              child: Text(c.t('mobile.sell.add')),
            ),
            const SizedBox(width: 8),
            IconBox(icon: Icons.center_focus_weak, label: c.t('mobile.camera.open'), onTap: _camera),
          ]),
          const SizedBox(height: 14),
          TileRow(children: [
            StatTile(c.t('mobile.count.scanned'), c.t('mobile.count.ofExpected', [found, expected.length]), kind: allFound ? 'ok' : 'tile'),
            StatTile(c.t('mobile.count.toFind'), '${left.length}', kind: left.isNotEmpty && c.counted.isNotEmpty ? 'warn' : 'tile'),
            StatTile(c.t('mobile.count.notExpected'), '${extra.length}', kind: extra.isNotEmpty ? 'danger' : 'tile'),
          ]),
          if (allFound) ...[const SizedBox(height: 12), NoteBox('ok', c.t('mobile.count.allFound'))],
          const SizedBox(height: 12),
          FieldBox(
            label: c.t('mobile.count.note'),
            error: e['count'],
            child: BoundField(
                value: c.countNote, maxLength: 255, caps: TextCapitalization.sentences, hint: c.t('mobile.count.notePh'), onChanged: c.setCountNote),
          ),
          const SizedBox(height: 14),
          ButtonRow(children: [
            OutlineButton(c.t('mobile.count.reset'), onPressed: c.counted.isEmpty ? null : c.resetCount),
            PrimaryButton(c.t('mobile.count.save'), onPressed: _save),
          ]),
        ]),
        if (extra.isNotEmpty) ...[
          Padding(padding: const EdgeInsets.only(top: 2), child: CardTitle(c.t('mobile.count.extraList'), trailing: CountChip(extra.length))),
          ListBox(children: [
            for (final code in extra)
              ItemRow(
                code: code,
                what: c.t('mobile.count.notExpected'),
                trailing: IconBox(icon: Icons.close, label: c.t('mobile.count.remove'), onTap: () => c.removeCounted(code)),
              ),
          ]),
        ],
        if (left.isNotEmpty && c.counted.isNotEmpty) ...[
          Padding(padding: const EdgeInsets.only(top: 2), child: CardTitle(c.t('mobile.count.leftList'), trailing: CountChip(left.length))),
          ListBox(children: [for (final u in left) ItemRow(code: u.code, what: '${u.productName} · ${u.size}')]),
        ],
        Padding(padding: const EdgeInsets.only(top: 2), child: CardTitle(c.t('mobile.count.list'))),
        ListBox(empty: c.t('mobile.count.none'), children: [
          for (final x in c.counts.reversed)
            ItemRow(
              code: dateTime(x.countedAt),
              what: x.status == 'sent'
                  ? c.t('mobile.count.result', [x.found, x.expected, x.missing.length, x.extra.length])
                  : c.tn('mobile.count.labels', x.codes.length, [x.codes.length]),
              extra: [
                if (x.missing.isNotEmpty) Text(c.t('mobile.count.missingCodes', [x.missing.join(', ')]), style: TextStyle(fontSize: 13, color: p.danger)),
                if (x.extra.isNotEmpty) Text(c.t('mobile.count.extraCodes', [x.extra.join(', ')]), style: TextStyle(fontSize: 13, color: p.muted)),
                if (x.status == 'refused' && x.error.isNotEmpty) Text(x.error, style: TextStyle(fontSize: 13, color: p.danger)),
              ],
              trailing: PosBadge(switch (x.stage) { 'pending' => 'pending', 'clean' => 'signed', _ => 'conflict' }, c.t('mobile.count.status.${x.stage}')),
            ),
        ]),
      ],
    );
  }
}
