// High Bytes POS - paying (MPOS-04, MPOS-05): the customer (their frozen list prices the units), each unit's price per m²
// (lower only within the driver's limit, with a reason), the total and its VAT, then cash and/or mobile money, and
// credit for an approved customer within what their account has left (decision D8).

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../money.dart' as m;
import '../theme.dart';
import '../widgets.dart';

class CartScreen extends StatelessWidget {
  const CartScreen({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final b = c.bundle!;
    final p = c.priced();
    final e = c.errors;
    final pal = Palette.of(context);
    final change = c.changeText(p);
    final creditLeft = c.creditLeftOf(p.customer);
    const amountKeys = TextInputType.numberWithOptions(decimal: true);
    return PosPage(
      c: c,
      title: c.t('mobile.pay.title'),
      sub: b.trip.number,
      monoSub: true,
      art: Art.cash,
      onBack: () => c.go(PosView.sell),
      bottom: CartBar(
        count: c.t('mobile.sell.inCart', [p.lines.length]),
        amount: c.money(p.total),
        action: c.t('mobile.pay.complete'),
        onAction: p.lines.isEmpty
            ? null
            : () {
                FocusScope.of(context).unfocus();
                c.completeSale();
              },
      ),
      children: [
        noticeOf(c),
        if (e['cart'] != null) NoteBox('danger', e['cart']!),
        CardBox(title: c.t('mobile.pay.customer'), children: [
          FieldBox(
            label: c.t('mobile.pay.customer'),
            hint: c.t('mobile.pay.customerHint'),
            child: DropdownMenu<String>(
              key: ValueKey('customer-${p.customer?.id}'),
              initialSelection: p.customer?.id,
              expandedInsets: EdgeInsets.zero,
              enableFilter: true,
              requestFocusOnTap: true,
              menuHeight: 320,
              dropdownMenuEntries: [for (final cu in c.customers) DropdownMenuEntry(value: cu.id, label: cu.label)],
              onSelected: c.setCustomer,
            ),
          ),
          const SizedBox(height: 12),
          FieldBox(
            label: c.t('mobile.pay.buyerName'),
            child: BoundField(
                value: c.cart.buyerName, maxLength: 100, caps: TextCapitalization.words, onChanged: (v) => c.setCartField('buyerName', v)),
          ),
          const SizedBox(height: 12),
          FieldBox(
            label: c.t('mobile.pay.buyerTin'),
            hint: c.t('mobile.pay.buyerTinHint'),
            error: e['tin'],
            child: BoundField(
                value: c.cart.buyerTin,
                keyboard: TextInputType.number,
                maxLength: 11,
                invalid: e['tin'] != null,
                onChanged: (v) => c.setCartField('buyerTin', v)),
          ),
        ]),
        ListBox(empty: c.t('mobile.pay.empty'), children: [
          for (final x in p.lines)
            Padding(
              key: ValueKey(x.unit.id),
              padding: const EdgeInsets.all(14),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(x.unit.code, style: monoStyle(size: 14, weight: FontWeight.w600, color: pal.heading)),
                      Text('${x.unit.productName} · ${x.unit.size}', style: TextStyle(color: pal.muted, fontSize: 14)),
                    ]),
                  ),
                  const SizedBox(width: 10),
                  IconBox(icon: Icons.close, label: c.t('mobile.pay.remove'), onTap: () => c.removeLine(x.unit.id)),
                ]),
                const SizedBox(height: 10),
                Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Expanded(
                    child: FieldBox(
                      label: c.t('mobile.pay.price'),
                      hint: x.price == null ? null : c.t('mobile.pay.listPrice', [m.grouped(x.price!.price, 2)]),
                      error: e['price-${x.unit.id}'],
                      child: BoundField(
                          key: ValueKey('price-${x.unit.id}'),
                          value: x.line.pricePerM2,
                          keyboard: amountKeys,
                          invalid: e['price-${x.unit.id}'] != null,
                          onChanged: (v) => c.setPrice(x.unit.id, v)),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: FieldBox(
                      label: c.t('mobile.pay.amount'),
                      child: Container(
                        constraints: const BoxConstraints(minHeight: 48),
                        alignment: Alignment.centerRight,
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        decoration: BoxDecoration(color: pal.bg, border: Border.all(color: pal.border, width: 1.5), borderRadius: BorderRadius.circular(8)),
                        child: Text(x.priceOk ? c.money(x.amount) : '—', style: monoStyle(size: 16, weight: FontWeight.w700, color: pal.heading)),
                      ),
                    ),
                  ),
                ]),
                if (x.changed) ...[
                  const SizedBox(height: 10),
                  FieldBox(
                    label: c.t('mobile.pay.reason'),
                    error: e['reason-${x.unit.id}'],
                    child: BoundField(
                        key: ValueKey('reason-${x.unit.id}'),
                        value: x.line.priceReason,
                        maxLength: 200,
                        caps: TextCapitalization.sentences,
                        invalid: e['reason-${x.unit.id}'] != null,
                        onChanged: (v) => c.setReason(x.unit.id, v)),
                  ),
                ],
              ]),
            ),
        ]),
        CardBox(children: [
          TotalRow(c.t('mobile.pay.total'), '${c.money(p.total)} RWF', big: true),
          const SizedBox(height: 6),
          TotalRow(c.t('mobile.pay.vatIncluded'), m.grouped(m.text(c.cartVat(p), 2), 2), muted: true),
        ]),
        CardBox(title: c.t('mobile.pay.payment'), children: [
          ButtonRow(children: [
            OutlineButton(c.t('mobile.pay.allCash'), onPressed: c.allCash),
            OutlineButton(c.t('mobile.pay.allMomo'), onPressed: c.allMomo),
          ]),
          const SizedBox(height: 12),
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: FieldBox(
                label: c.t('mobile.pay.cash'),
                child: BoundField(value: c.cashShown(p), keyboard: amountKeys, onChanged: (v) => c.setCartField('cash', v)),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FieldBox(
                label: c.t('mobile.pay.tendered'),
                child: BoundField(
                    value: c.cart.tendered,
                    keyboard: amountKeys,
                    invalid: e['tendered'] != null,
                    onChanged: (v) => c.setCartField('tendered', v)),
              ),
            ),
          ]),
          if (e['tendered'] != null)
            Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(e['tendered']!, style: TextStyle(fontSize: 13, color: pal.danger, fontWeight: FontWeight.w600)))
          else if (change != null)
            Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(change, style: TextStyle(fontSize: 13, color: pal.muted, fontWeight: FontWeight.w700))),
          const SizedBox(height: 12),
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: FieldBox(
                label: c.t('mobile.pay.momo'),
                child: BoundField(value: c.cart.momo, keyboard: amountKeys, onChanged: (v) => c.setCartField('momo', v)),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FieldBox(
                label: c.t('mobile.pay.momoRefLabel'),
                child: BoundField(
                    value: c.cart.momoRef,
                    maxLength: 60,
                    caps: TextCapitalization.characters,
                    invalid: e['momoRef'] != null,
                    onChanged: (v) => c.setCartField('momoRef', v)),
              ),
            ),
          ]),
          if (creditLeft != null) ...[
            const SizedBox(height: 12),
            if (creditLeft == BigInt.zero)
              NoteBox('info', c.t('mobile.pay.noCreditLeft'))
            else
              FieldBox(
                label: c.t('mobile.pay.credit'),
                hint: c.t('mobile.pay.creditLeft', [c.money(creditLeft)]),
                error: e['credit'],
                child: BoundField(
                    value: c.cart.credit, keyboard: amountKeys, invalid: e['credit'] != null, onChanged: (v) => c.setCartField('credit', v)),
              ),
            if (creditLeft >= p.total && p.total > BigInt.zero) ...[
              const SizedBox(height: 10),
              OutlineButton(c.t('mobile.pay.allCredit'), onPressed: c.allCredit),
            ],
          ],
          for (final key in ['momoRef', 'pay'])
            if (e[key] != null)
              Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(e[key]!, style: TextStyle(fontSize: 13, color: pal.danger, fontWeight: FontWeight.w600))),
        ]),
      ],
    );
  }
}
