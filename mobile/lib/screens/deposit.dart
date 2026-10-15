// High Bytes POS - banking the cash on the road (BP 7.3 rule 4), as depositView in app.js: the cash held and banked so far,
// then how (a bank deposit or mobile money to the company), how much, the slip or transaction reference and the day; kept
// on the phone until it syncs, then recorded by the office or refused.

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../money.dart' as m;
import '../theme.dart';
import '../widgets.dart';

class DepositScreen extends StatelessWidget {
  const DepositScreen({super.key, required this.c});

  final PosController c;

  Future<void> _pickDay(BuildContext context) async {
    final b = c.bundle!;
    final now = DateTime.now();
    final last = DateTime(now.year, now.month, now.day);
    final first = DateTime.tryParse(b.trip.date) ?? last.subtract(const Duration(days: 30));
    final shown = DateTime.tryParse(c.deposit.day) ?? last;
    final picked = await showDatePicker(
      context: context,
      initialDate: shown.isBefore(first) ? first : (shown.isAfter(last) ? last : shown),
      firstDate: first.isAfter(last) ? last : first,
      lastDate: last,
    );
    if (picked != null) {
      String two(int n) => n.toString().padLeft(2, '0');
      c.setDepositField('day', '${picked.year}-${two(picked.month)}-${two(picked.day)}');
    }
  }

  @override
  Widget build(BuildContext context) {
    final b = c.bundle!;
    final f = c.deposit;
    final e = c.errors;
    final p = Palette.of(context);
    final s = c.day;
    final valid = RegExp(r'^\d+$').hasMatch(f.amount) && m.scaled(f.amount, b.decimals) > BigInt.zero;
    String dayOf(String iso) => iso.split('-').reversed.join('/');
    return PosPage(
      c: c,
      title: c.t('mobile.deposit.title'),
      sub: b.trip.number,
      monoSub: true,
      art: Art.cash,
      onBack: () => c.go(PosView.day),
      children: [
        noticeOf(c),
        CardBox(padding: 20, children: gaps([
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const IconTile(icon: Icons.account_balance_outlined, kind: 'ok', size: 44, round: true),
            const SizedBox(width: 14),
            Expanded(child: Text(c.t('mobile.deposit.hint'), style: TextStyle(fontSize: 15, height: 22 / 15, color: p.text))),
          ]),
          TileRow(children: [
            StatTile(c.t('mobile.deposit.held'), c.money(s.held > BigInt.zero ? s.held : BigInt.zero)),
            StatTile(c.t('mobile.deposit.banked'), c.money(s.banked), kind: s.banked > BigInt.zero ? 'ok' : 'tile'),
          ]),
          FieldBox(
            label: c.t('mobile.deposit.method'),
            child: DropdownMenu<String>(
              key: ValueKey('method-${f.method}'),
              initialSelection: f.method,
              expandedInsets: EdgeInsets.zero,
              dropdownMenuEntries: [
                for (final method in ['BANK_TRANSFER', 'MOBILE_MONEY']) DropdownMenuEntry(value: method, label: c.t('mobile.deposit.method.$method')),
              ],
              onSelected: c.setDepositMethod,
            ),
          ),
          FieldBox(
            label: c.t('mobile.deposit.amount'),
            error: e['amount'],
            child: BoundField(
              value: f.amount,
              keyboard: TextInputType.number,
              invalid: e['amount'] != null,
              formatters: [GroupedDigits()],
              style: monoStyle(size: 20, weight: FontWeight.w700, color: p.heading),
              suffix: Padding(
                padding: const EdgeInsets.only(right: 16),
                child: Text('RWF', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: p.muted)),
              ),
              onChanged: (v) => c.setDepositAmount(v.replaceAll(',', '')),
            ),
          ),
          FieldBox(
            label: c.t('mobile.deposit.reference'),
            error: e['reference'],
            child: BoundField(
                value: f.reference,
                maxLength: 60,
                mono: true,
                caps: TextCapitalization.characters,
                hint: c.t('mobile.deposit.referencePh'),
                invalid: e['reference'] != null,
                onChanged: (v) => c.setDepositField('reference', v)),
          ),
          FieldBox(
            label: c.t('mobile.deposit.day'),
            error: e['day'],
            child: OutlineButton(dayOf(f.day), icon: Icons.event_outlined, danger: e['day'] != null, onPressed: () => _pickDay(context)),
          ),
          PrimaryButton(
            valid ? c.t('mobile.deposit.saveAmount', [c.money(m.scaled(f.amount, b.decimals))]) : c.t('mobile.deposit.save'),
            big: true,
            onPressed: () {
              FocusScope.of(context).unfocus();
              c.saveDeposit();
            },
          ),
        ], 14)),
        Padding(padding: const EdgeInsets.only(top: 2), child: CardTitle(c.t('mobile.deposit.list'))),
        ListBox(empty: c.t('mobile.deposit.none'), children: [
          for (final d in c.deposits.reversed)
            ItemRow(
              code: d.number.isNotEmpty ? d.number : d.reference,
              what: '${c.t('mobile.deposit.method.${d.method}')} · ${d.reference} · ${dayOf(d.depositedOn)}',
              extra: [if (d.status == 'refused' && d.error.isNotEmpty) Text(d.error, style: TextStyle(fontSize: 13, color: p.danger))],
              trailing: RowAmount(
                c.money(m.scaled(d.amount, b.decimals)),
                weight: FontWeight.w700,
                below: PosBadge(switch (d.status) { 'pending' => 'pending', 'refused' => 'conflict', _ => 'signed' }, c.t('mobile.deposit.status.${d.status}')),
              ),
            ),
        ]),
      ],
    );
  }
}
