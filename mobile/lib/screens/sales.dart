// High Bytes POS - the trip's sales on this phone: how many, how many wait to sync, the total, and each with its state.

import 'package:flutter/material.dart';

import '../controller.dart';
import '../money.dart' as m;
import '../widgets.dart';

class SalesScreen extends StatelessWidget {
  const SalesScreen({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final d = c.bundle!.decimals;
    final total = c.sales.where((s) => s.status != 'conflict').fold(BigInt.zero, (sum, s) => sum + m.scaled(s.total, d));
    return PosPage(c: c, title: c.t('mobile.sales.title'), sub: c.bundle!.trip.number, onBack: () => c.go(PosView.sell), children: [
      noticeOf(c),
      CardBox(children: [
        TotalRow(c.t('mobile.sales.count'), '${c.sales.length}'),
        const SizedBox(height: 6),
        TotalRow(c.t('mobile.sales.pending'), '${c.pending.length}'),
        const SizedBox(height: 6),
        TotalRow(c.t('mobile.sales.total'), '${c.money(total)} RWF', big: true),
      ]),
      ListBox(empty: c.t('mobile.sales.none'), children: [
        for (final s in c.sales.reversed)
          ItemRow(
            code: s.number,
            what: '${dateTime(s.createdAt)} · ${c.tn('mobile.sales.units', s.lines.length, [s.lines.length])}',
            onTap: () => c.openReceipt(s.clientId),
            trailing: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Text(c.money(m.scaled(s.total, d)), style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              PosBadge(s.status, c.t('mobile.status.${s.status}')),
            ]),
          ),
      ]),
    ]);
  }
}
