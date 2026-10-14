// High Bytes POS - the cash the driver declares at the end of the day (MPOS-07): once, with network; the accountant counts
// it when it is handed over.

import 'package:flutter/material.dart';

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
    return PosPage(c: c, title: c.t('mobile.declare.title'), sub: b.trip.number, onBack: () => c.go(PosView.sell), children: [
      noticeOf(c),
      if (declared != null)
        NoteBox('ok', c.t('mobile.declare.done', [c.money(m.scaled(declared, b.decimals)), b.cashDeclaredAt == null ? '' : dateTime(b.cashDeclaredAt!)]))
      else
        CardBox(children: [
          Text(c.t('mobile.declare.hint'), style: TextStyle(color: p.muted)),
          const SizedBox(height: 12),
          FieldBox(
            label: c.t('mobile.declare.amount'),
            error: e['declare'],
            child: BoundField(value: c.declareAmount, keyboard: TextInputType.number, invalid: e['declare'] != null, onChanged: c.setDeclareAmount),
          ),
          if (c.pendingCount > 0) ...[const SizedBox(height: 12), NoteBox('warn', c.tn('mobile.declare.pending', c.pendingCount, [c.pendingCount]))],
          const SizedBox(height: 16),
          PrimaryButton(
            c.busy ? c.t('mobile.wait') : c.t('mobile.declare.save'),
            onPressed: c.busy
                ? null
                : () {
                    FocusScope.of(context).unfocus();
                    final question = c.declareQuestion();
                    if (question != null) confirmThen(context, c, question, c.t('mobile.declare.save'), c.declareCash);
                  },
          ),
        ]),
    ]);
  }
}
