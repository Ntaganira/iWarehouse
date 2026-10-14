// High Bytes POS - signed in, no trip on the phone yet: download it (SYNC-01).

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../theme.dart';
import '../widgets.dart';

class NoTripScreen extends StatelessWidget {
  const NoTripScreen({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return PosPage(c: c, eyebrow: c.t('mobile.brand'), title: c.t('mobile.trip.title'), sub: c.session?.fullName, art: Art.truck, children: [
      noticeOf(c),
      CardBox(padding: 20, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const IconTile(icon: Icons.local_shipping_outlined, kind: 'info', size: 44, round: true),
          const SizedBox(width: 14),
          Expanded(child: Text(c.t('mobile.trip.hint'), style: TextStyle(fontSize: 15, height: 22 / 15, color: p.text))),
        ]),
        const SizedBox(height: 18),
        PrimaryButton(c.busy ? c.t('mobile.wait') : c.t('mobile.trip.download'), big: true, onPressed: c.busy ? null : c.downloadTrip),
      ]),
    ]);
  }
}
