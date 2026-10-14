// High Bytes POS - the trip's sales on this phone, as the client's design draws it: the total on this phone with how many
// sales and how many wait to sync on tiles, then each sale with its state.

import 'package:flutter/material.dart';

import '../art.dart';
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
    final waiting = c.pending.length;
    return PosPage(
      c: c,
      title: c.t('mobile.sales.title'),
      sub: c.bundle!.trip.number,
      monoSub: true,
      art: Art.receipt,
      onBack: () => c.go(PosView.day),
      children: [
        noticeOf(c),
        CardBox(padding: 18, children: [
          Eyebrow(c.t('mobile.sales.totalPhone')),
          const SizedBox(height: 4),
          Amount(c.money(total), size: 31),
          const SizedBox(height: 14),
          TileRow(children: [
            StatTile(c.t('mobile.day.sales'), '${c.sales.length}'),
            StatTile(c.t('mobile.sales.pending'), '$waiting', kind: waiting > 0 ? 'warn' : 'tile'),
          ]),
        ]),
        Padding(padding: const EdgeInsets.only(top: 2), child: CardTitle(c.t('mobile.sales.count'))),
        ListBox(empty: c.t('mobile.sales.none'), children: [
          for (final s in c.sales.reversed)
            ItemRow(
              code: s.number,
              what: '${dateTime(s.createdAt)} · ${c.tn('mobile.sales.units', s.lines.length, [s.lines.length])}',
              onTap: () => c.openReceipt(s.clientId),
              leading: IconTile(icon: Icons.receipt_outlined, kind: s.status),
              trailing: RowAmount(c.money(m.scaled(s.total, d)), weight: FontWeight.w700, below: PosBadge(s.status, c.t('mobile.status.${s.status}'))),
            ),
        ]),
      ],
    );
  }
}
