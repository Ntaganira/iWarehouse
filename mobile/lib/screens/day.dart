// High Bytes POS - My day, the first screen, in the client's design: the trip at a glance from what this phone holds, so it
// works offline, with the way to sell, to count the van and to bank the cash; as dayView in app.js.

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../money.dart' as m;
import '../theme.dart';
import '../widgets.dart';

class DayScreen extends StatelessWidget {
  const DayScreen({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final b = c.bundle!;
    final s = c.day;
    final p = Palette.of(context);
    final date = b.trip.date.isEmpty ? '' : b.trip.date.split('-').reversed.join('/');
    // The last count of the vehicle: all there, or what was missing
    final lc = s.lastCount;
    final Widget? lastCount = lc == null
        ? null
        : lc.status != 'sent'
            ? NoteBox('info', c.t('mobile.day.lastCountWaiting', [dateTime(lc.countedAt)]))
            : lc.missing.isNotEmpty
                ? NoteBox('warn', c.tn('mobile.day.lastCountMissing', lc.missing.length, [dateTime(lc.countedAt), lc.missing.length]))
                : NoteBox('ok', c.t('mobile.day.lastCount', [dateTime(lc.countedAt), lc.found, lc.expected]));
    return PosPage(
      c: c,
      eyebrow: c.t('mobile.day.title'),
      title: c.session?.fullName ?? '',
      sub: '${b.trip.number} · ${b.trip.vehicle.plate}\n${[b.trip.area, date].where((x) => x.isNotEmpty).join(' · ')}'.trim(),
      art: Art.truck,
      children: [
        noticeOf(c),
        PrimaryButton(c.t('mobile.menu.sell'), big: true, onPressed: () => c.go(PosView.sell)),
        CardBox(padding: 18, children: [
          Eyebrow(c.t('mobile.day.sales')),
          const SizedBox(height: 4),
          Amount(c.money(s.total), size: 31),
          Text(c.tn('mobile.day.salesCount', s.sales, [s.sales]), style: TextStyle(fontSize: 13, color: p.muted)),
          const SizedBox(height: 14),
          TileRow(children: [
            StatTile(c.t('mobile.method.CASH'), c.money(s.cash)),
            StatTile(c.t('mobile.method.MOBILE_MONEY'), c.money(s.momo)),
          ]),
          if (s.credit > BigInt.zero) ...[const SizedBox(height: 10), StatTile(c.t('mobile.method.CREDIT'), c.money(s.credit))],
          if (s.conflicts > 0) ...[const SizedBox(height: 10), StatTile(c.t('mobile.day.conflicts'), '${s.conflicts}', kind: 'danger')],
          const SizedBox(height: 14),
          OutlineButton(c.t('mobile.menu.sales'), icon: Icons.receipt_long_outlined, onPressed: () => c.go(PosView.sales)),
        ]),
        CardBox(title: c.t('mobile.day.glass'), children: [
          TileRow(children: [
            StatTile(c.t('mobile.day.sold'), '${s.sold}', kind: 'ok'),
            StatTile(c.t('mobile.day.broken'), '${s.broken}', kind: s.broken > 0 ? 'warn' : 'tile'),
            StatTile(c.t('mobile.day.onBoard'), '${s.onBoard}'),
          ]),
          if (lastCount != null) ...[const SizedBox(height: 14), lastCount],
          const SizedBox(height: 14),
          OutlineButton(c.t('mobile.menu.count'), icon: Icons.fact_check_outlined, onPressed: () => c.go(PosView.count)),
          const SizedBox(height: 10),
          OutlineButton(c.t('mobile.menu.breakage'), icon: Icons.report_gmailerrorred, onPressed: () => c.go(PosView.breakage)),
        ]),
        CardBox(title: c.t('mobile.day.receipts'), children: [
          TileRow(children: [
            StatTile(c.t('mobile.day.signed'), c.t('mobile.day.ofTotal', [s.signed, s.receipts]), kind: s.receipts > 0 && s.signed == s.receipts ? 'ok' : 'tile'),
            StatTile(c.t('mobile.sales.pending'), '${s.waiting}', kind: s.waiting > 0 ? 'warn' : 'tile'),
          ]),
        ]),
        CardBox(title: c.t('mobile.day.cash'), children: [
          if (b.cashDeclared != null)
            NoteBox('ok', c.t('mobile.declare.done', [c.money(m.scaled(b.cashDeclared!, b.decimals)), b.cashDeclaredAt == null ? '' : dateTime(b.cashDeclaredAt!)]))
          else ...[
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const IconTile(icon: Icons.payments_outlined, kind: 'ok', size: 44, round: true),
              const SizedBox(width: 14),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(c.t(s.banked > BigInt.zero ? 'mobile.day.cashHeld' : 'mobile.day.cashFromSales'), style: TextStyle(fontSize: 13, color: p.text)),
                  const SizedBox(height: 2),
                  Amount(c.money(s.banked > BigInt.zero ? (s.held > BigInt.zero ? s.held : BigInt.zero) : s.cash), size: 22),
                  if (s.banked > BigInt.zero)
                    Text(c.t('mobile.day.cashBanked', [c.money(s.cash), c.money(s.banked)]), style: TextStyle(fontSize: 13, color: p.muted)),
                ]),
              ),
            ]),
            const SizedBox(height: 10),
            Text(c.t('mobile.day.cashHint'), style: TextStyle(color: p.muted, fontSize: 13, height: 19 / 13)),
            const SizedBox(height: 14),
            OutlineButton(c.t('mobile.menu.deposit'), icon: Icons.account_balance_outlined, onPressed: () => c.go(PosView.deposit)),
            const SizedBox(height: 10),
            OutlineButton(c.t('mobile.menu.declare'), icon: Icons.payments_outlined, onPressed: () => c.go(PosView.declare)),
          ],
        ]),
      ],
    );
  }
}
