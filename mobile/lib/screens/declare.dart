// High Bytes POS - the cash the driver declares at the end of the day (MPOS-07), as the client's design draws it: once,
// with network; the amount typed large, grouped as it is typed, and on the button; the accountant counts it when it is
// handed over.

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../money.dart' as m;
import '../theme.dart';
import '../widgets.dart';

class DeclareScreen extends StatelessWidget {
  const DeclareScreen({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final b = c.bundle!;
    final e = c.errors;
    final p = Palette.of(context);
    final declared = b.cashDeclared;
    final typed = c.declareAmount.trim();
    final valid = RegExp(r'^\d+$').hasMatch(typed);
    return PosPage(
      c: c,
      title: c.t('mobile.declare.title'),
      sub: b.trip.number,
      monoSub: true,
      art: Art.cash,
      onBack: () => c.go(PosView.day),
      children: [
        noticeOf(c),
        if (declared != null)
          NoteBox('ok', c.t('mobile.declare.done', [c.money(m.scaled(declared, b.decimals)), b.cashDeclaredAt == null ? '' : dateTime(b.cashDeclaredAt!)]))
        else
          CardBox(padding: 20, children: gaps([
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const IconTile(icon: Icons.payments_outlined, kind: 'ok', size: 44, round: true),
              const SizedBox(width: 14),
              Expanded(child: Text(c.t('mobile.declare.hintCount'), style: TextStyle(fontSize: 15, height: 22 / 15, color: p.text))),
            ]),
            FieldBox(
              label: c.t('mobile.declare.cashLabel'),
              error: e['declare'],
              child: SizedBox(
                child: BoundField(
                  value: c.declareAmount,
                  keyboard: TextInputType.number,
                  invalid: e['declare'] != null,
                  formatters: [GroupedDigits()],
                  height: 68,
                  style: monoStyle(size: 30, weight: FontWeight.w700, color: p.heading, letterSpacing: -0.6),
                  suffix: Padding(
                    padding: const EdgeInsets.only(right: 16),
                    child: Text('RWF', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: p.muted)),
                  ),
                  onChanged: (v) => c.setDeclareAmount(v.replaceAll(',', '')),
                ),
              ),
            ),
            NoteBox('info', c.t('mobile.declare.once')),
            if (c.pendingCount > 0) NoteBox('warn', c.tn('mobile.declare.pending', c.pendingCount, [c.pendingCount])),
            PrimaryButton(
              c.busy
                  ? c.t('mobile.wait')
                  : valid
                      ? c.t('mobile.declare.saveAmount', [c.money(m.scaled(typed, b.decimals))])
                      : c.t('mobile.declare.save'),
              big: true,
              onPressed: c.busy
                  ? null
                  : () {
                      FocusScope.of(context).unfocus();
                      final question = c.declareQuestion();
                      if (question != null) confirmThen(context, c, question, c.t('mobile.declare.save'), c.declareCash);
                    },
            ),
          ], 18)),
      ],
    );
  }
}
