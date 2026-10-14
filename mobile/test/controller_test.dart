// The app's rules against a fake server: signing in and the trip, selling and its checks, the offline queue, the
// server's refusals, breakage and the cash declaration. The same behaviour as the web app (static/m/app.js).

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:highbytes_pos/api.dart';
import 'package:highbytes_pos/controller.dart';
import 'package:highbytes_pos/store.dart';
import 'package:http/testing.dart';

import 'fake_server.dart';

void main() {
  late FakeServer server;
  late FakeNetwork network;
  late PosController c;

  Future<void> signedIn() async {
    await c.start();
    await c.signIn(serverTyped: 'https://pos.example.rw/', username: 'driver-m7', password: 'test-password', deviceName: 'Jean’s phone');
    await settle();
  }

  setUp(() {
    server = FakeServer();
    network = FakeNetwork();
    c = controllerFor(server, network);
  });

  tearDown(() => c.dispose());

  group('signing in', () {
    test('signs in, keeps the server and downloads the trip', () async {
      await signedIn();
      expect(c.session!.server, 'https://pos.example.rw');
      expect(c.session!.fullName, 'Jean Driver');
      expect(c.bundle!.trip.number, 'TRP-WH-2026-000020');
      expect(c.unitsOnBoard.map((u) => u.code), ['U-WH-000001', 'U-WH-000002']);
      expect(c.notice!.text, 'Trip TRP-WH-2026-000020 on the phone: 2 units on board');
      final login = server.posts('/auth/login').single;
      expect(login.headers['X-Device-Id'], isNotEmpty);
      expect(login.body, contains('"deviceName":"Jean’s phone"'));
    });

    test('a wrong password shows the server\'s message', () async {
      await c.start();
      await c.signIn(serverTyped: 'https://pos.example.rw', username: 'driver-m7', password: 'nope', deviceName: '');
      expect(c.session, isNull);
      expect(c.errors['login'], 'Wrong username or password');
    });

    test('the address must be https, unless the build allows http', () async {
      await c.start();
      await c.signIn(serverTyped: 'pos.example.rw', username: 'a', password: 'b', deviceName: '');
      expect(c.errors['server'], c.t('mobile.login.serverInvalid'));
      await c.signIn(serverTyped: 'http://192.168.1.20:8080', username: 'a', password: 'b', deviceName: '');
      expect(c.errors['server'], c.t('mobile.login.serverHttps'));
      expect(server.requests.where((r) => r.url.path == '/api/v1/auth/login'), isEmpty);
    });

    test('a server that cannot be reached is said so', () async {
      c.dispose();
      c = PosController(
          store: MemoryStore(),
          api: Api(MockClient((_) async => throw const SocketException('no route to host')), userAgent: 'test'),
          network: network,
          bundledTexts: (_) async => englishTexts());
      await c.start();
      await c.signIn(serverTyped: 'https://nowhere.example.rw', username: 'driver-m7', password: 'x', deviceName: '');
      expect(c.session, isNull);
      expect(c.errors['login'], c.t('mobile.login.unreachable'));
    });
  });

  group('selling', () {
    test('a sale is saved on the phone, sent, then signed', () async {
      await signedIn();
      c.addByCode(' u-wh-000001 ');
      expect(c.cart.lines.single.unitId, unit1);
      final cart = c.priced();
      expect(cart.total, BigInt.from(25920));
      expect(c.cashShown(cart), '25920');
      expect(await c.completeSale(), isTrue);
      expect(c.view, PosView.receipt);
      await settle();
      final sent = server.sales.single;
      expect(sent['number'], 'MINV-WH-2026-000101');
      expect(sent['tripId'], c.bundle!.trip.id);
      expect(sent['total'], 25920);
      expect(sent['lines'], [
        {'unitId': unit1, 'pricePerM2': 27000, 'priceReason': null, 'amount': 25920},
      ]);
      expect(sent['payments'], [
        {'method': 'CASH', 'amount': 25920, 'reference': null},
      ]);
      expect(c.sales.single.status, 'signed'); // accepted, then its signature fetched straight away
      expect(c.unitsOnBoard.map((u) => u.code), ['U-WH-000002']);
      c.addByCode('U-WH-000001');
      expect(c.notice!.text, c.t('mobile.sell.alreadySold', ['U-WH-000001']));
    });

    test('the customer\'s list prices the units, VAT added when it excludes it', () async {
      await signedIn();
      c.addByCode('U-WH-000001');
      c.addByCode('U-WH-000002');
      c.setCustomer(contractor);
      final cart = c.priced();
      expect(cart.lines.map((x) => x.amount), [BigInt.from(24355), BigInt.from(12685)]);
      expect(c.cart.buyerTin, '102345678');
      expect(c.cartVat(cart), BigInt.from(565017)); // (24,355 + 12,685) x 18 / 118 = 5,650.17, in cents
    });

    test('the checks before saving a sale', () async {
      await signedIn();
      c.addByCode('U-WH-000001');
      c.setPrice(unit1, '24000'); // 11.1% off, the limit is 10%
      expect(await c.completeSale(), isFalse);
      expect(c.errors['price-$unit1'], c.t('mobile.pay.priceLimit', ['24,300']));
      c.setPrice(unit1, '24300');
      expect(await c.completeSale(), isFalse);
      expect(c.errors['reason-$unit1'], c.t('mobile.pay.reasonNeeded'));
      c.setReason(unit1, 'Regular customer');
      c.setCartField('buyerTin', '12345');
      c.setCartField('cash', '20000');
      c.setCartField('momo', '3328');
      expect(await c.completeSale(), isFalse);
      expect(c.errors['tin'], c.t('mobile.pay.tin'));
      expect(c.errors['momoRef'], c.t('mobile.pay.momoRef'));
      expect(c.errors['pay'], isNull); // 20,000 + 3,328 = 23,328 = 24,300 x 0.96
      c.setCartField('buyerTin', '');
      c.setCartField('momoRef', 'MP-77');
      c.setCartField('tendered', '19000');
      expect(await c.completeSale(), isFalse);
      expect(c.errors['tendered'], c.t('mobile.pay.tenderedLow', ['20,000']));
      c.setCartField('tendered', '20 000');
      c.setCartField('momo', '3000');
      expect(await c.completeSale(), isFalse);
      expect(c.errors['pay'], c.t('mobile.pay.mustAddUp', ['23,000', '23,328']));
      c.setCartField('momo', '3,328');
      expect(await c.completeSale(), isTrue);
      await settle();
      expect(server.sales.single['lines'][0]['priceReason'], 'Regular customer');
      expect(server.sales.single['payments'], [
        {'method': 'CASH', 'amount': 20000, 'reference': null},
        {'method': 'MOBILE_MONEY', 'amount': 3328, 'reference': 'MP-77'},
      ]);
      expect(server.sales.single['cashTendered'], 20000);
    });

    test('each sale takes the next invoice number of the trip', () async {
      await signedIn();
      for (final code in ['U-WH-000001', 'U-WH-000002']) {
        c.addByCode(code);
        expect(await c.completeSale(), isTrue);
      }
      await settle();
      expect(server.sales.map((s) => s['number']), ['MINV-WH-2026-000101', 'MINV-WH-2026-000102']);
    });
  });

  group('offline and the server\'s refusals', () {
    test('sales made offline wait, then go oldest first when the network comes back', () async {
      await signedIn();
      network.set(false);
      await settle();
      for (final code in ['U-WH-000001', 'U-WH-000002']) {
        c.addByCode(code);
        await c.completeSale();
      }
      await settle();
      expect(server.sales, isEmpty);
      expect(c.pendingCount, 2);
      expect(await c.store.all('sales'), hasLength(2)); // kept on the phone
      network.set(true);
      await settle();
      expect(server.sales.map((s) => s['number']), ['MINV-WH-2026-000101', 'MINV-WH-2026-000102']);
      expect(c.pendingCount, 0);
    });

    test('a refused sale stays waiting, the driver told, and is tried again later', () async {
      await signedIn();
      server.refuseSales = (422, 'Something is wrong with this sale');
      c.addByCode('U-WH-000001');
      await c.completeSale();
      await settle();
      expect(c.sales.single.status, 'pending');
      expect(c.notice!.text, 'Something is wrong with this sale');
      expect(c.retryDelay, 4000);
    });

    test('a revoked phone is signed out and its sales stay', () async {
      await signedIn();
      server.revoked = true;
      c.addByCode('U-WH-000001');
      await c.completeSale();
      await settle();
      expect(c.session, isNull);
      expect(c.notice!.text, c.t('mobile.login.expired'));
      expect(c.pendingCount, 1);
      expect(await c.store.get('session'), isNull);
    });

    test('signing out and closing the trip wait for the sales', () async {
      await signedIn();
      network.set(false);
      c.addByCode('U-WH-000001');
      await c.completeSale();
      await c.closeTrip();
      expect(c.bundle, isNotNull);
      expect(c.notice!.text, c.tn('mobile.close.pending', 1, [1]));
      await c.signOut();
      expect(c.session, isNotNull);
      network.set(true);
      await settle();
      await c.signOut();
      expect(c.session, isNull);
      expect(c.sales, isEmpty);
    });

    test('a phone with sales of one server does not sign in to another', () async {
      await signedIn();
      server.revoked = true;
      c.addByCode('U-WH-000001');
      await c.completeSale();
      await settle();
      server.revoked = false;
      await c.signIn(serverTyped: 'https://other.example.rw', username: 'driver-m7', password: 'test-password', deviceName: '');
      expect(c.errors['server'], c.t('mobile.login.serverPending', ['https://pos.example.rw']));
    });

    test('everything survives the app being closed', () async {
      final store = MemoryStore();
      c.dispose();
      c = controllerFor(server, network, store: store);
      await signedIn();
      network.set(false);
      c.addByCode('U-WH-000001');
      await c.completeSale();
      c.dispose();
      c = controllerFor(server, network, store: store);
      network.on = false;
      await c.start();
      expect(c.session!.username, 'driver-m7');
      expect(c.bundle!.trip.number, 'TRP-WH-2026-000020');
      expect(c.pending.single.number, 'MINV-WH-2026-000101');
    });
  });

  group('the end of the day', () {
    test('a breakage report needs the unit, why, where and a photo, and syncs like a sale', () async {
      await signedIn();
      expect(await c.saveBreakage(), isFalse);
      expect(c.errors.keys, containsAll(['unit', 'reason', 'place', 'photo']));
      c.setBreakageUnit(unit2);
      c.setBreakageField('reason', 'Fell when braking');
      c.setBreakageField('place', 'Near Nyabugogo');
      c.addPosition('-1.94995, 30.05885');
      c.setPhoto('data:image/jpeg;base64,/9j/4AAQ');
      expect(await c.saveBreakage(), isTrue);
      await settle();
      final sent = server.breakages.single;
      expect(sent['unitId'], unit2);
      expect(sent['place'], 'Near Nyabugogo (-1.94995, 30.05885)');
      expect(sent['photo'], startsWith('data:image/jpeg;base64,'));
      expect(sent['photoName'], 'U-WH-000002.jpg');
      expect(c.breakages.single.photo, isNull); // the phone keeps no copy once sent
      expect(c.breakages.single.stage, 'accepted'); // its decision fetched after sending
      expect(c.unitsOnBoard.map((u) => u.code), ['U-WH-000001']);
    });

    test('the cash is declared once, as a whole number', () async {
      await signedIn();
      c.setDeclareAmount('12.5');
      expect(c.declareQuestion(), isNull);
      expect(c.errors['declare'], c.t('mobile.declare.invalid'));
      c.setDeclareAmount('25920');
      expect(c.declareQuestion(), c.t('mobile.declare.confirm', ['25,920']));
      await c.declareCash();
      expect(c.bundle!.cashDeclared, '25920');
      expect(server.posts('/trip/declaration').single.body, contains('"amount":25920'));
    });
  });

  group('my day', () {
    test('opens first and adds up the trip from what the phone holds', () async {
      await signedIn();
      expect(c.view, PosView.day);
      var day = c.day;
      expect([day.sales, day.total, day.sold, day.onBoard, day.waiting], [0, BigInt.zero, 0, 2, 0]);

      c.addByCode('U-WH-000001'); // 25,920 in cash, sent and signed
      await c.completeSale();
      await settle();
      network.set(false); // 6,750 split between cash and mobile money, waiting on the phone
      c.addByCode('U-WH-000002');
      c.setCartField('cash', '5000');
      c.setCartField('momo', '1750');
      c.setCartField('momoRef', 'MP-1');
      expect(await c.completeSale(), isTrue);

      day = c.day;
      expect(day.sales, 2);
      expect(day.total, BigInt.from(32670));
      expect(day.cash, BigInt.from(30920));
      expect(day.momo, BigInt.from(1750));
      expect([day.sold, day.broken, day.onBoard], [2, 0, 0]);
      expect([day.signed, day.receipts, day.waiting, day.conflicts], [1, 2, 1, 0]);
    });

    test('the system\'s back leads home to My day, which leaves the app', () async {
      await signedIn();
      expect(c.back(), isFalse);
      for (final from in [PosView.sell, PosView.sales, PosView.breakage, PosView.declare]) {
        c.go(from);
        expect(c.back(), isTrue);
        expect(c.view, PosView.day, reason: '$from');
      }
      c.go(PosView.cart);
      c.back();
      expect(c.view, PosView.sell);
    });

    test('the company\'s logo comes with the trip, decoded once', () async {
      server.trip!['company'] = {...server.trip!['company'] as Map, 'logo': 'data:image/png;base64,iVBORw0KGgo='};
      await signedIn();
      final logo = c.bundle!.company.logoBytes!;
      expect(logo.take(4), [0x89, 0x50, 0x4E, 0x47]); // PNG
      expect(identical(c.bundle!.company.logoBytes, logo), isTrue);
    });
  });
}
