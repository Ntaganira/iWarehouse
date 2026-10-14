// High Bytes POS - what the phone itself does: whether there is a network, the camera reading labels (QR codes, MPOS-03),
// a photo of broken glass, the position, and sharing a receipt by WhatsApp, SMS or any app (MPOS-05).

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import 'controller.dart';

/// The phone's network as connectivity_plus reports it: any connection but none counts as online.
class PhoneNetwork implements Network {
  final Connectivity _connectivity = Connectivity();

  static bool _on(List<ConnectivityResult> results) => results.any((r) => r != ConnectivityResult.none);

  @override
  Future<bool> isOnline() async => _on(await _connectivity.checkConnectivity());

  @override
  Stream<bool> get changes => _connectivity.onConnectivityChanged.map(_on).distinct();
}

/// Opens the camera on its own screen and gives back the first label it reads, or null when the driver goes back.
Future<String?> scanLabel(BuildContext context, PosController c) =>
    Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => _ScannerPage(c: c), fullscreenDialog: true));

class _ScannerPage extends StatefulWidget {
  const _ScannerPage({required this.c});

  final PosController c;

  @override
  State<_ScannerPage> createState() => _ScannerPageState();
}

class _ScannerPageState extends State<_ScannerPage> {
  final MobileScannerController _camera =
      MobileScannerController(formats: const [BarcodeFormat.qrCode, BarcodeFormat.code128], detectionSpeed: DetectionSpeed.noDuplicates);
  bool _done = false;

  @override
  void dispose() {
    _camera.dispose();
    super.dispose();
  }

  void _found(BarcodeCapture capture) {
    if (_done) return;
    for (final b in capture.barcodes) {
      final value = b.rawValue;
      if (value != null && value.trim().isNotEmpty) {
        _done = true;
        Navigator.of(context).pop(value);
        return;
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(c.t('mobile.sell.scanTitle')),
        actions: [
          IconButton(
              tooltip: c.t('mobile.camera.close'), icon: const Icon(Icons.close), onPressed: () => Navigator.of(context).pop()),
        ],
      ),
      body: Stack(fit: StackFit.expand, children: [
        MobileScanner(controller: _camera, onDetect: _found),
        Center(
          child: Container(
            width: 240,
            height: 240,
            decoration: BoxDecoration(border: Border.all(color: Colors.white, width: 3), borderRadius: BorderRadius.circular(18)),
          ),
        ),
      ]),
    );
  }
}

/// A photo from the camera, shrunk to 1600 px at most, as a JPEG data URL (small enough to queue offline and send on a
/// slow network); null when the driver cancels. Throws when the camera cannot be used.
Future<String?> takePhoto() async {
  final photo = await ImagePicker().pickImage(source: ImageSource.camera, maxWidth: 1600, maxHeight: 1600, imageQuality: 80);
  if (photo == null) return null;
  final bytes = await photo.readAsBytes();
  return 'data:image/jpeg;base64,${base64Encode(bytes)}';
}

Uint8List? photoBytes(String? dataUrl) {
  if (dataUrl == null) return null;
  final comma = dataUrl.indexOf(',');
  try {
    return base64Decode(comma < 0 ? dataUrl : dataUrl.substring(comma + 1));
  } catch (_) {
    return null;
  }
}

/// The phone's position as "-1.94995, 30.05885", or null when it is off, refused or takes longer than 10 s.
Future<String?> currentPosition() async {
  try {
    if (!await Geolocator.isLocationServiceEnabled()) return null;
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) permission = await Geolocator.requestPermission();
    if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) return null;
    final at = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high, timeLimit: Duration(seconds: 10)));
    return '${at.latitude.toStringAsFixed(5)}, ${at.longitude.toStringAsFixed(5)}';
  } catch (_) {
    return null;
  }
}

/// Shares the receipt: its picture and its text, through the phone's share sheet (WhatsApp, SMS, email...).
Future<void> shareReceipt({required Uint8List png, required String number, required String text}) async {
  final dir = await getTemporaryDirectory();
  final file = File('${dir.path}${Platform.pathSeparator}receipt-$number.png');
  await file.writeAsBytes(png, flush: true);
  await SharePlus.instance.share(ShareParams(text: text, subject: number, files: [XFile(file.path, mimeType: 'image/png')]));
}

/// Shares the receipt as text only, for an SMS.
Future<void> shareText({required String number, required String text}) async {
  await SharePlus.instance.share(ShareParams(text: text, subject: number));
}
