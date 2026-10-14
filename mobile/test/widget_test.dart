// The screens as a driver uses them, against the fake server: sign in, tap a sheet, pay, the receipt; the system's back.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:highbytes_pos/app.dart';
import 'package:highbytes_pos/controller.dart';

import 'fake_server.dart';

void main() {
  late FakeServer server;
  late FakeNetwork network;
  late PosController c;

  setUp(() {
    server = FakeServer();
    network = FakeNetwork();
    c = controllerFor(server, network);
  });

  tearDown(() => c.dispose());

  Future<void> phone(WidgetTester tester) async {
    tester.view.physicalSize = const Size(390 * 3, 844 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
  }

  testWidgets('signing in shows the server, then the trip', (tester) async {
    await phone(tester);
    await tester.runAsync(c.start);
    await tester.pumpWidget(PosApp(c: c));
    await tester.pumpAndSettle();
    expect(find.text(c.t('mobile.login.title')), findsOneWidget);
    expect(find.text('pos.example.rw'), findsOneWidget); // the server remembered, on its card

    final fields = find.byType(TextField);
    await tester.enterText(fields.at(0), 'driver-m7');
    await tester.enterText(fields.at(1), 'test-password');
    await tester.enterText(fields.at(2), 'Jean’s phone');
    await tester.ensureVisible(find.text(c.t('mobile.login.submit')));
    await tester.pumpAndSettle(); // the logo makes the form taller than the screen: scrolled to Sign in first
    await tester.tap(find.text(c.t('mobile.login.submit')));
    await tester.runAsync(settle);
    await tester.pumpAndSettle();

    // My day first, then Sell
    expect(find.text(c.t('mobile.day.title').toUpperCase()), findsOneWidget); // the header's eyebrow
    expect(find.textContaining('TRP-WH-2026-000020 · RAC123A'), findsOneWidget);
    expect(find.text(c.t('mobile.net.online')), findsOneWidget);
    await tester.tap(find.text(c.t('mobile.menu.sell')));
    await tester.pumpAndSettle();
    expect(find.text('TRP-WH-2026-000020'), findsOneWidget);
    expect(find.text('U-WH-000001'), findsOneWidget);

    // The system's back returns to My day
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text(c.t('mobile.day.title').toUpperCase()), findsOneWidget);
  });

  testWidgets('a tap sells a sheet: the cart, the payment, the receipt', (tester) async {
    await phone(tester);
    await tester.runAsync(() async {
      await c.start();
      await c.signIn(serverTyped: 'https://pos.example.rw', username: 'driver-m7', password: 'test-password', deviceName: '');
    });
    await tester.pumpWidget(PosApp(c: c));
    await tester.pumpAndSettle();
    await tester.tap(find.text(c.t('mobile.menu.sell')));
    await tester.pumpAndSettle();

    await tester.tap(find.text('U-WH-000001'));
    await tester.pumpAndSettle();
    expect(find.text('25,920 RWF'), findsWidgets);
    await tester.tap(find.text(c.t('mobile.sell.pay')));
    await tester.pumpAndSettle();
    expect(find.text(c.t('mobile.pay.title')), findsWidgets); // the title, and the payment card's own
    expect(find.text('25920', skipOffstage: false), findsOneWidget); // all in cash unless the driver says otherwise

    // The system's back returns to the sell screen, the cart kept
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text(c.t('mobile.sell.scanTitle')), findsOneWidget);
    await tester.tap(find.text(c.t('mobile.sell.pay')));
    await tester.pumpAndSettle();

    await tester.tap(find.text(c.t('mobile.pay.complete')));
    await tester.runAsync(settle);
    await tester.pumpAndSettle();
    expect(find.text('MINV-WH-2026-000101'), findsWidgets);
    expect(find.text(c.t('mobile.receipt.share')), findsOneWidget);
    expect(server.sales, hasLength(1));
  });
}
