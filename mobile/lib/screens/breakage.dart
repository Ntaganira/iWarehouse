// High Bytes POS - breakage on the road (MPOS-06): the unit, why, where (the phone's position may be added), a photo; kept
// on the phone until it syncs, then the supervisor accepts or rejects it.

import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../art.dart';
import '../controller.dart';
import '../device.dart';
import '../theme.dart';
import '../widgets.dart';

class BreakageScreen extends StatefulWidget {
  const BreakageScreen({super.key, required this.c});

  final PosController c;

  @override
  State<BreakageScreen> createState() => _BreakageScreenState();
}

class _BreakageScreenState extends State<BreakageScreen> {
  bool _locating = false;
  String? _shownPhoto;
  Uint8List? _shownBytes;

  /// The photo decoded once, not on every rebuild: each key typed would decode it again and the picture flicker.
  Uint8List? _preview(String? photo) {
    if (photo != _shownPhoto) {
      _shownPhoto = photo;
      _shownBytes = photoBytes(photo);
    }
    return _shownBytes;
  }

  Future<void> _photo() async {
    try {
      final photo = await takePhoto();
      if (photo != null) widget.c.setPhoto(photo);
    } catch (_) {
      widget.c.photoFailed();
    }
  }

  Future<void> _position() async {
    setState(() => _locating = true);
    final at = await currentPosition();
    if (mounted) setState(() => _locating = false);
    widget.c.addPosition(at);
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    final f = c.breakage;
    final e = c.errors;
    final p = Palette.of(context);
    final units = c.unitsOnBoard;
    final chosen = c.bundle!.unit(f.unitId);
    final preview = _preview(f.photo);
    return PosPage(
        c: c, title: c.t('mobile.breakage.title'), sub: c.bundle!.trip.number, monoSub: true, art: Art.breakage, onBack: () => c.go(PosView.day), children: [
      noticeOf(c),
      CardBox(children: [
        Text(c.t('mobile.breakage.hint'), style: TextStyle(color: p.muted)),
        const SizedBox(height: 12),
        FieldBox(
          label: c.t('mobile.breakage.unit'),
          hint: chosen == null ? null : '${chosen.productName} · ${chosen.size}',
          error: e['unit'],
          child: DropdownMenu<String>(
            key: ValueKey('unit-${f.unitId}'),
            initialSelection: f.unitId.isEmpty ? null : f.unitId,
            hintText: c.t('mobile.breakage.unitPick'),
            expandedInsets: EdgeInsets.zero,
            enableFilter: true,
            requestFocusOnTap: true,
            menuHeight: 320,
            dropdownMenuEntries: [for (final u in units) DropdownMenuEntry(value: u.id, label: u.code)],
            onSelected: (id) {
              if (id != null) c.setBreakageUnit(id);
            },
          ),
        ),
        const SizedBox(height: 12),
        FieldBox(
          label: c.t('mobile.breakage.reason'),
          error: e['reason'],
          child: BoundField(
              value: f.reason,
              maxLength: 255,
              hint: c.t('mobile.breakage.reasonPh'),
              caps: TextCapitalization.sentences,
              invalid: e['reason'] != null,
              onChanged: (v) => c.setBreakageField('reason', v)),
        ),
        const SizedBox(height: 12),
        FieldBox(
          label: c.t('mobile.breakage.place'),
          error: e['place'],
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            BoundField(
                value: f.place,
                maxLength: 255,
                hint: c.t('mobile.breakage.placePh'),
                caps: TextCapitalization.sentences,
                invalid: e['place'] != null,
                onChanged: (v) => c.setBreakageField('place', v)),
            const SizedBox(height: 8),
            OutlineButton(c.t('mobile.breakage.position'), icon: Icons.my_location, onPressed: _locating ? null : _position),
          ]),
        ),
        const SizedBox(height: 12),
        FieldBox(
          label: c.t('mobile.breakage.photo'),
          error: e['photo'],
          child: preview == null
              ? OutlineButton(c.t('mobile.breakage.photoTake'), icon: Icons.photo_camera_outlined, onPressed: _photo)
              : Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.memory(preview, height: 260, fit: BoxFit.cover, semanticLabel: c.t('mobile.breakage.photo')),
                  ),
                  const SizedBox(height: 8),
                  OutlineButton(c.t('mobile.breakage.photoAgain'), onPressed: () => c.setPhoto(null)),
                ]),
        ),
        const SizedBox(height: 16),
        PrimaryButton(c.t('mobile.breakage.save'), onPressed: () {
          FocusScope.of(context).unfocus();
          c.saveBreakage();
        }),
      ]),
      Text(c.t('mobile.breakage.list'), style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
      ListBox(empty: c.t('mobile.breakage.none'), children: [
        for (final b in c.breakages.reversed)
          ItemRow(
            code: b.code,
            what: '${b.reason} · ${b.place}',
            extra: [
              if (b.status == 'refused' && b.error.isNotEmpty) Text(b.error, style: TextStyle(fontSize: 13, color: p.danger)),
              if (b.decisionNote.isNotEmpty) Text(b.decisionNote, style: TextStyle(fontSize: 14, color: p.muted)),
            ],
            trailing: PosBadge(
              switch (b.stage) { 'pending' => 'pending', 'accepted' => 'signed', 'rejected' || 'refused' => 'conflict', _ => 'accepted' },
              c.t('mobile.breakage.status.${b.stage}'),
            ),
          ),
      ]),
    ]);
  }
}
