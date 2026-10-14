// High Bytes POS - selling (MPOS-02, MPOS-03), as the client's design draws it: a label scanned with the camera or typed,
// or a tap on the vehicle's stock (a round check marks what is in the sale); the sale's count and total at the bottom,
// and the way to pay.

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../device.dart';
import '../theme.dart';
import '../widgets.dart';

class SellScreen extends StatefulWidget {
  const SellScreen({super.key, required this.c});

  final PosController c;

  @override
  State<SellScreen> createState() => _SellScreenState();
}

class _SellScreenState extends State<SellScreen> {
  final TextEditingController _code = TextEditingController();
  final FocusNode _codeFocus = FocusNode();

  @override
  void dispose() {
    _code.dispose();
    _codeFocus.dispose();
    super.dispose();
  }

  void _add() {
    widget.c.addByCode(_code.text);
    _code.clear();
  }

  Future<void> _camera() async {
    final code = await scanLabel(context, widget.c);
    if (code != null) widget.c.addByCode(code);
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    final b = c.bundle!;
    final p = Palette.of(context);
    final customer = c.customerOf(c.cart.customerId);
    final term = c.search.trim().toUpperCase();
    final onBoard = c.unitsOnBoard;
    final units = onBoard
        .where((u) => term.isEmpty || u.code.contains(term) || u.productName.toUpperCase().contains(term) || u.productCode.contains(term))
        .toList();
    final cart = c.priced();
    return PosPage(
      c: c,
      eyebrow: c.t('mobile.sell.trip'),
      title: b.trip.number,
      monoTitle: true,
      sub: '${b.trip.vehicle.plate} · ${b.trip.area}',
      art: Art.truck,
      onBack: () => c.go(PosView.day),
      bottom: cart.lines.isEmpty
          ? null
          : CartBar(
              count: c.t('mobile.sell.inCart', [cart.lines.length]),
              amount: c.money(cart.total),
              action: c.t('mobile.sell.pay'),
              onAction: () => c.go(PosView.cart)),
      children: [
        noticeOf(c),
        CardBox(title: c.t('mobile.sell.scanTitle'), children: [
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
        ]),
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Row(children: [
            CardTitle(c.t('mobile.sell.onBoard'), trailing: CountChip(onBoard.length)),
            const SizedBox(width: 12),
            Expanded(child: Text(c.t('mobile.sell.tapHint'), textAlign: TextAlign.right, style: TextStyle(fontSize: 13, color: p.muted))),
          ]),
        ),
        BoundField(
          value: c.search,
          hint: c.t('mobile.sell.searchPh'),
          icon: Icons.search,
          onChanged: c.setSearch,
          action: TextInputAction.search,
          semantics: c.t('mobile.sell.searchPh'),
        ),
        ListBox(
          empty: onBoard.isEmpty ? c.t('mobile.sell.nothingLeft') : c.t('mobile.sell.noMatch'),
          children: [
            for (final u in units)
              Builder(builder: (_) {
                final on = c.inCart(u.id);
                final price = c.priceFor(customer, u);
                return ItemRow(
                  code: u.code,
                  what: '${u.productName} · ${u.size}',
                  selected: on,
                  leading: CheckMark(on: on),
                  onTap: () => c.toggleUnit(u),
                  trailing: price == null
                      ? Text(c.t('mobile.sell.noPriceShort'), style: TextStyle(fontSize: 13, color: p.muted))
                      : RowAmount(c.money(c.unitAmount(price, u))),
                );
              }),
          ],
        ),
      ],
    );
  }
}
