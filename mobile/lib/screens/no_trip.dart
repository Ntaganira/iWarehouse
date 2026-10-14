// High Bytes POS - signed in, no trip on the phone yet: download it (SYNC-01).

import 'package:flutter/material.dart';

import '../controller.dart';
import '../theme.dart';
import '../widgets.dart';

class NoTripScreen extends StatelessWidget {
  const NoTripScreen({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return PosPage(c: c, title: c.t('mobile.app'), sub: c.session?.fullName, children: [
      noticeOf(c),
      CardBox(children: [
        Text(c.t('mobile.trip.title'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
        const SizedBox(height: 14),
        Text(c.t('mobile.trip.hint'), style: TextStyle(color: p.muted)),
        const SizedBox(height: 14),
        PrimaryButton(c.busy ? c.t('mobile.wait') : c.t('mobile.trip.download'), onPressed: c.busy ? null : c.downloadTrip),
      ]),
    ]);
  }
}
