// High Bytes POS - selling (MPOS-02, MPOS-03): a label scanned with the camera or typed, or a tap on the vehicle's stock;
// the cart's count and total at the bottom, and the way to pay.

import 'package:flutter/material.dart';

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
      title: b.trip.number,
      sub: '${b.trip.vehicle.plate} · ${b.trip.area}',
      bottom: cart.lines.isEmpty
          ? null
          : CartBar(
              count: c.t('mobile.sell.inCart', [cart.lines.length]),
              amount: '${c.money(cart.total)} RWF',
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
                style: const TextStyle(fontSize: 16, fontFamily: mono, fontFamilyFallback: monoFallback),
                decoration: InputDecoration(hintText: c.t('mobile.sell.scanPh'), hintStyle: TextStyle(color: p.muted, fontFamily: null)),
              ),
            ),
            const SizedBox(width: 8),
            PrimaryButton(c.t('mobile.sell.add'), onPressed: _add),
            const SizedBox(width: 8),
            IconBox(icon: Icons.qr_code_scanner, label: c.t('mobile.camera.open'), onTap: _camera),
          ]),
        ]),
        FieldBox(
          label: c.t('mobile.sell.stock', [onBoard.length]),
          child: BoundField(value: c.search, hint: c.t('mobile.sell.searchPh'), onChanged: c.setSearch, action: TextInputAction.search),
        ),
        ListBox(
          empty: onBoard.isEmpty ? c.t('mobile.sell.nothingLeft') : c.t('mobile.sell.noMatch'),
          children: [
            for (final u in units)
              ItemRow(
                code: u.code,
                what: '${u.productName} · ${u.size}',
                selected: c.inCart(u.id),
                onTap: () => c.toggleUnit(u),
                trailing: Builder(builder: (_) {
                  final price = c.priceFor(customer, u);
                  return Text(price == null ? c.t('mobile.sell.noPriceShort') : '${c.money(c.unitAmount(price, u))} RWF',
                      style: const TextStyle(fontWeight: FontWeight.w700));
                }),
              ),
          ],
        ),
      ],
    );
  }
}
