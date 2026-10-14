// High Bytes POS - the receipt (MPOS-05): shown at once, "pending signature" until EBM signs it, then the SDC block and
// its QR code (SYNC-06). Shared as a picture with its text (WhatsApp, email) or as text alone (SMS): there is no printer.

import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../controller.dart';
import '../device.dart';
import '../models.dart';
import '../money.dart' as m;
import '../widgets.dart';

class ReceiptScreen extends StatefulWidget {
  const ReceiptScreen({super.key, required this.c, required this.sale});

  final PosController c;
  final Sale sale;

  @override
  State<ReceiptScreen> createState() => _ReceiptScreenState();
}

class _ReceiptScreenState extends State<ReceiptScreen> {
  final GlobalKey _paper = GlobalKey();
  bool _sharing = false;

  Future<void> _share({required bool picture}) async {
    final c = widget.c;
    final sale = widget.sale;
    setState(() => _sharing = true);
    try {
      final text = receiptText(c, sale);
      if (picture) {
        final boundary = _paper.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        final image = await boundary.toImage(pixelRatio: 3);
        final png = await image.toByteData(format: ui.ImageByteFormat.png);
        await shareReceipt(png: png!.buffer.asUint8List(), number: sale.number, text: text);
      } else {
        await shareText(number: sale.number, text: text);
      }
    } catch (_) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(c.t('mobile.error.server'))));
    } finally {
      if (mounted) setState(() => _sharing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    final sale = widget.sale;
    final status = switch (sale.status) {
      'conflict' => NoteBox('danger', c.t('mobile.receipt.conflictNote', [sale.conflictText])),
      'pending' => NoteBox('warn', c.t('mobile.receipt.pendingNote')),
      'accepted' => NoteBox('info', c.t('mobile.receipt.acceptedNote')),
      _ => NoteBox('ok', c.t('mobile.receipt.signedNote')),
    };
    return PosPage(c: c, title: sale.number, sub: dateTime(sale.createdAt), onBack: () => c.go(PosView.sales), children: [
      noticeOf(c),
      status,
      Center(child: RepaintBoundary(key: _paper, child: ReceiptPaper(c: c, sale: sale))),
      ButtonRow(children: [
        OutlineButton(c.t('mobile.receipt.share'), icon: Icons.share, onPressed: _sharing ? null : () => _share(picture: true)),
        OutlineButton(c.t('mobile.receipt.shareText'), icon: Icons.sms_outlined, onPressed: _sharing ? null : () => _share(picture: false)),
      ]),
      PrimaryButton(c.t('mobile.receipt.newSale'), onPressed: () => c.go(PosView.sell)),
    ]);
  }
}

/// The receipt as the web app draws it: 80 mm paper, black on white in both themes.
class ReceiptPaper extends StatelessWidget {
  const ReceiptPaper({super.key, required this.c, required this.sale});

  final PosController c;
  final Sale sale;

  @override
  Widget build(BuildContext context) {
    final b = c.bundle!;
    final co = b.company;
    final d = b.decimals;
    final ebm = sale.ebm;
    String amount(String v) => c.money(m.scaled(v, d));
    final vat = m.vatOf(sale.lines.map((l) => m.VatLine(l.taxCode, l.vatRate, l.amount)), d);
    return DefaultTextStyle(
      style: const TextStyle(fontFamily: 'monospace', fontFamilyFallback: ['Menlo', 'Courier New'], fontSize: 13, color: Color(0xFF111111), height: 1.35),
      child: Container(
        constraints: const BoxConstraints(maxWidth: 380),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0xFFE3E8F1))),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          _center(co.name.isEmpty ? c.t('mobile.app') : co.name, size: 15, bold: true),
          if (co.address.isNotEmpty) _center(co.address),
          if (co.phone.isNotEmpty) _center(co.phone),
          if (co.tin.isNotEmpty) _center('TIN ${co.tin}'),
          const _Rule(),
          _row(c.t('mobile.receipt.invoice'), sale.number, bold: true),
          _row(c.t('mobile.receipt.date'), dateTime(sale.createdAt)),
          _row(c.t('mobile.receipt.vehicle'), b.trip.vehicle.plate),
          _row(c.t('mobile.receipt.seller'), b.trip.driver),
          _row(c.t('mobile.receipt.customer'), sale.buyerName.isNotEmpty ? sale.buyerName : sale.customerName),
          if (sale.buyerTin.isNotEmpty) _row(c.t('mobile.receipt.tin'), sale.buyerTin),
          const _Rule(),
          for (final l in sale.lines) ...[
            Text('${l.code} ${l.productName}'),
            _row('${l.widthMm}×${l.heightMm} @ ${m.grouped(l.pricePerM2, 2)}', amount(l.amount)),
            if (l.priceReason.isNotEmpty) Text(c.t('mobile.receipt.listPrice', [m.grouped(l.listPrice, 2)]), style: const TextStyle(color: Color(0xFF64748B))),
          ],
          const _Rule(),
          _row(c.t('mobile.receipt.total'), amount(sale.total), bold: true, size: 15),
          _row(c.t('mobile.receipt.vat'), m.grouped(m.text(vat, 2), 2)),
          for (final p in sale.payments)
            _row('${c.t('mobile.method.${p.method}')}${p.reference.isEmpty ? '' : ' ${p.reference}'}', amount(p.amount)),
          if (sale.change != null) _row(c.t('mobile.receipt.change'), amount(sale.change!)),
          const _Rule(),
          if (ebm != null && ebm.signed) ...[
            _center(c.t('mobile.receipt.sdc'), bold: true),
            _row(c.t('mobile.receipt.date'), ebm.signedAt),
            _row('SDC ID', ebm.sdcId),
            _row(c.t('mobile.receipt.number'), ebm.receiptLabel),
            Text(c.t('mobile.receipt.internalData')),
            _center(ebm.internalData),
            Text(c.t('mobile.receipt.signature')),
            _center(ebm.signature),
            _row('MRC', ebm.mrcNo),
            if (ebm.qrSvg != null)
              Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Center(child: SvgPicture.string(ebm.qrSvg!, width: 140, height: 140))),
            _center(c.t('mobile.receipt.endLegal')),
            if (ebm.simulated) _center(c.t('mobile.receipt.simulated'), bold: true),
          ] else if (sale.status == 'conflict')
            _center(c.t('mobile.receipt.conflict'), bold: true)
          else
            _center(c.t('mobile.receipt.pending'), bold: true),
        ]),
      ),
    );
  }

  static Widget _center(String text, {bool bold = false, double? size}) => Text(text,
      textAlign: TextAlign.center, style: TextStyle(fontWeight: bold ? FontWeight.w800 : null, fontSize: size));

  static Widget _row(String label, String value, {bool bold = false, double? size}) {
    final style = TextStyle(fontWeight: bold ? FontWeight.w800 : null, fontSize: size);
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Expanded(child: Text(label, style: style)),
      const SizedBox(width: 8),
      Flexible(child: Text(value, textAlign: TextAlign.right, style: style)),
    ]);
  }
}

class _Rule extends StatelessWidget {
  const _Rule();

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: LayoutBuilder(builder: (context, box) {
          final dashes = (box.maxWidth / 6).floor();
          return Row(children: [
            for (var i = 0; i < dashes; i++) Container(width: 3, height: 1, margin: const EdgeInsets.only(right: 3), color: const Color(0xFF777777)),
          ]);
        }),
      );
}

/// The receipt as text, for SMS and WhatsApp.
String receiptText(PosController c, Sale sale) {
  final b = c.bundle!;
  final d = b.decimals;
  final ebm = sale.ebm;
  return [
    b.company.name.isEmpty ? c.t('mobile.app') : b.company.name,
    '${c.t('mobile.receipt.invoice')} ${sale.number}',
    dateTime(sale.createdAt),
    for (final l in sale.lines) '${l.code} ${l.productName}: ${c.money(m.scaled(l.amount, d))} RWF',
    '${c.t('mobile.receipt.total')}: ${c.money(m.scaled(sale.total, d))} RWF',
    ebm != null && ebm.signed ? 'EBM ${ebm.receiptLabel} · SDC ${ebm.sdcId}' : c.t('mobile.receipt.pending'),
  ].join('\n');
}
