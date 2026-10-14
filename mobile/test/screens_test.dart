// Pictures of the app's screens for the user manual, drawn at a phone's size in the light and dark themes with the fake
// server's trip: flutter test --tags screens --dart-define=SCREENS_DIR=<folder> (PNG files; nothing is compared).
// Without SCREENS_DIR the test only checks that every screen draws.
@Tags(['screens'])
library;

import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:highbytes_pos/app.dart';
import 'package:highbytes_pos/controller.dart';

import 'fake_server.dart';

const _dir = String.fromEnvironment('SCREENS_DIR');
/// Real letters instead of the test font's boxes: the app's own Inter and IBM Plex Mono (assets/fonts), and Material's icons.
Future<void> _fonts() async {
  Future<void> load(String family, List<String> paths) async {
    final loader = FontLoader(family);
    for (final path in paths) {
      loader.addFont(Future.value(ByteData.view(File(path).readAsBytesSync().buffer)));
    }
    await loader.load();
  }

  await load('Inter', [for (final w in ['Regular', 'Medium', 'SemiBold', 'Bold', 'ExtraBold']) 'assets/fonts/Inter-$w.ttf']);
  await load('IBM Plex Mono', [for (final w in ['Medium', 'SemiBold', 'Bold']) 'assets/fonts/IBMPlexMono-$w.ttf']);
  // flutter_tester runs from <flutter>/bin/cache/artifacts/engine/<platform>/
  final root = Platform.environment['FLUTTER_ROOT'] ?? File(Platform.resolvedExecutable).parent.parent.parent.parent.parent.parent.path;
  await load('MaterialIcons', ['$root/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf']);
}

void main() {
  testWidgets('every screen draws, in both themes', (tester) async {
    if (_dir.isNotEmpty) await tester.runAsync(_fonts);
    const phone = Size(390, 844);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final server = FakeServer()
      ..simulated = false // the receipt as a real VSDC signs it, with its QR code
      ..qrSvg = File('test/receipt-qr.svg').readAsStringSync();
    final network = FakeNetwork();
    final c = controllerFor(server, network);
    addTearDown(c.dispose);
    final frame = GlobalKey();

    /// [full]: the whole screen as far as it scrolls (drawn on a taller phone), not only what fits on one.
    Future<void> shot(String name, {bool full = false}) async {
      for (final dark in [false, true]) {
        tester.platformDispatcher.platformBrightnessTestValue = dark ? Brightness.dark : Brightness.light;
        tester.view.physicalSize = phone * 2;
        await tester.pumpAndSettle();
        // Pictures (the breakage photo) decode outside the test's fake time
        await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 300)));
        await tester.pump();
        if (full) {
          final list = find.descendant(of: find.byType(ListView).first, matching: find.byType(Scrollable)).first;
          final more = tester.state<ScrollableState>(list).position.maxScrollExtent;
          if (more > 0) {
            tester.view.physicalSize = Size(phone.width, phone.height + more) * 2;
            await tester.pumpAndSettle();
          }
        }
        expect(tester.takeException(), isNull, reason: name);
        if (_dir.isEmpty) continue;
        await tester.runAsync(() async {
          final boundary = frame.currentContext!.findRenderObject()! as RenderRepaintBoundary;
          final image = await boundary.toImage(pixelRatio: 2);
          final png = await image.toByteData(format: ui.ImageByteFormat.png);
          File('$_dir/fm-$name${dark ? '-dark' : ''}.png')
            ..createSync(recursive: true)
            ..writeAsBytesSync(png!.buffer.asUint8List());
        });
      }
      tester.platformDispatcher.clearPlatformBrightnessTestValue();
    }

    Future<void> sell() => tester.runAsync(() async {
          await c.completeSale();
          await settle();
        });

    await tester.runAsync(c.start);
    await tester.pumpWidget(RepaintBoundary(key: frame, child: PosApp(c: c)));
    await shot('signin', full: true);

    // Signed in before the supervisor confirmed the departure
    server.trip = null;
    await tester.runAsync(() => c.signIn(serverTyped: 'https://pos.example.rw', username: 'driver-m7', password: 'test-password', deviceName: 'Jean’s phone'));
    await shot('notrip');

    // On the road: the trip with two more sheets, and the company's logo (High Bytes Ltd, as set in Settings)
    final trip = tripJson();
    server.trip = trip
      ..['units'] = [
        ...(trip['units'] as List),
        _unit(3, 1000, 600),
        _unit(4, 1200, 900),
      ]
      ..['company'] = {...trip['company'] as Map, 'logo': 'data:image/png;base64,${base64Encode(File('test/company-logo.png').readAsBytesSync())}'};
    await tester.runAsync(c.downloadTrip);
    c.clearNotice();
    c.go(PosView.sell);
    c.addByCode('U-WH-000001');
    await shot('sell');

    await tester.tap(find.byIcon(Icons.menu).first);
    await shot('menu');
    await tester.tapAt(const Offset(195, 40)); // the barrier above the sheet closes it
    await tester.pumpAndSettle();

    c.go(PosView.cart);
    await shot('pay', full: true);

    await sell();
    c.clearNotice();
    await shot('receipt', full: true);

    // Without network: the next sale is kept on the phone
    network.set(false);
    c.go(PosView.sell);
    c.addByCode('U-WH-000002');
    await shot('offline');

    // Two sheets for a contractor, one below the list price with its reason, cash and mobile money
    c.addByCode('U-WH-000003');
    c.setCustomer(contractor);
    c.setPrice(_id(3), '20000');
    c.setReason(_id(3), 'Large order, regular customer');
    c.setCartField('cash', '20000');
    c.setCartField('momo', '6845');
    c.setCartField('momoRef', 'MP241013.1146.K38291');
    c.go(PosView.cart);
    c.clearNotice();
    await shot('price', full: true);

    await sell();
    c.clearNotice();
    await shot('pending', full: true);

    c.setBreakageUnit(_id(4));
    c.setBreakageField('reason', 'Fell when braking');
    c.setBreakageField('place', 'Nyabugogo roundabout');
    final photo = await tester.runAsync(_samplePhoto);
    c.setPhoto(photo);
    c.go(PosView.breakage);
    await shot('breakage', full: true);
    await tester.runAsync(c.saveBreakage);

    c.go(PosView.sales);
    c.clearNotice();
    await shot('sales');

    // Back online, the waiting sale and the report go out
    network.set(true);
    await tester.runAsync(settle);
    c.go(PosView.breakage);
    c.clearNotice();
    await shot('reports', full: true);

    c.go(PosView.day);
    await shot('day', full: true);

    c.setDeclareAmount('45920'); // the two sales' cash
    c.go(PosView.declare);
    c.clearNotice();
    await shot('declare');

    await tester.runAsync(c.declareCash);
    c.clearNotice();
    await shot('declared');
  });
}

String _id(int n) => 'dddddddd-0000-0000-0000-00000000000$n';

/// A sheet of the fake trip's glass (Clear 6 mm).
Map<String, dynamic> _unit(int n, int width, int height) {
  final m2 = width * height / 1000000;
  return {
    'id': _id(n), 'code': 'U-WH-00000$n', 'productId': clear6, 'productCode': 'CLR-6', 'productName': 'Clear 6 mm', //
    'widthMm': width, 'heightMm': height, 'areaM2': m2, 'weightKg': m2 * 6 * 2.5, 'taxCode': 'B', 'vatRate': 18.00,
  };
}

/// The breakage report's photo: a cracked pane of glass, drawn (the test has no camera).
Future<String> _samplePhoto() async {
  const w = 800.0, h = 600.0;
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  canvas.drawRect(const Rect.fromLTWH(0, 0, w, h), Paint()..color = const Color(0xFF6E7F73)); // a van's floor
  const pane = Rect.fromLTWH(70, 60, 660, 480);
  canvas.drawRect(pane, Paint()
    ..shader = ui.Gradient.linear(pane.topLeft, pane.bottomRight, [const Color(0xFFB9D3DC), const Color(0xFFE6F0F3), const Color(0xFFA5C2CC)], [0, .5, 1]));
  canvas.drawRect(pane, Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 5
    ..color = const Color(0xFF7FA1AC));
  final crack = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = 2.4
    ..strokeCap = StrokeCap.round
    ..color = const Color(0xFF4F6B75);
  const at = Offset(420, 300);
  // Rays from where it hit, each bending a little, and two rings around it
  const rays = [
    [Offset(470, 230), Offset(560, 170), Offset(700, 64)],
    [Offset(500, 310), Offset(610, 330), Offset(728, 352)],
    [Offset(450, 380), Offset(520, 450), Offset(566, 538)],
    [Offset(390, 370), Offset(330, 460), Offset(300, 538)],
    [Offset(350, 300), Offset(240, 270), Offset(72, 250)],
    [Offset(380, 230), Offset(330, 150), Offset(260, 62)],
  ];
  for (final ray in rays) {
    final path = Path()..moveTo(at.dx, at.dy);
    for (final o in ray) {
      path.lineTo(o.dx, o.dy);
    }
    canvas.drawPath(path, crack);
  }
  canvas.drawCircle(at, 34, crack);
  canvas.drawCircle(at, 82, crack..strokeWidth = 1.6);
  final image = await recorder.endRecording().toImage(w.toInt(), h.toInt());
  final png = await image.toByteData(format: ui.ImageByteFormat.png);
  return 'data:image/png;base64,${base64Encode(png!.buffer.asUint8List())}';
}
