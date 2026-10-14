// A server for the tests: the mobile API's answers (controller/api on the server) for one trip of two sheets, recording
// what the phone sends.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:highbytes_pos/api.dart';
import 'package:highbytes_pos/controller.dart';
import 'package:highbytes_pos/store.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

const retail = 'aaaaaaaa-0000-0000-0000-000000000001';
const contractorList = 'aaaaaaaa-0000-0000-0000-000000000002';
const clear6 = 'bbbbbbbb-0000-0000-0000-000000000001';
const walkIn = 'cccccccc-0000-0000-0000-000000000001';
const contractor = 'cccccccc-0000-0000-0000-000000000002';
const unit1 = 'dddddddd-0000-0000-0000-000000000001';
const unit2 = 'dddddddd-0000-0000-0000-000000000002';

Map<String, dynamic> tripJson({String id = 'eeeeeeee-0000-0000-0000-000000000001', String number = 'TRP-WH-2026-000020'}) => {
      'trip': {
        'id': id,
        'number': number,
        'date': '2026-10-13',
        'area': 'Musanze, Rubavu',
        'vehicle': {'plate': 'RAC123A', 'model': 'Isuzu NPR', 'maxPieces': 40, 'maxLoadKg': 3000},
        'driver': 'Jean Driver',
        'departedAt': '2026-10-13T07:30:00',
      },
      'units': [
        {
          'id': unit1, 'code': 'U-WH-000001', 'productId': clear6, 'productCode': 'CLR-6', 'productName': 'Clear 6 mm', //
          'widthMm': 1200, 'heightMm': 800, 'areaM2': 0.96, 'weightKg': 14.4, 'taxCode': 'B', 'vatRate': 18.00,
        },
        {
          'id': unit2, 'code': 'U-WH-000002', 'productId': clear6, 'productCode': 'CLR-6', 'productName': 'Clear 6 mm', //
          'widthMm': 300, 'heightMm': 300, 'areaM2': 0.09, 'weightKg': 1.35, 'taxCode': 'B', 'vatRate': 18.00,
        },
      ],
      'priceLists': [
        {'id': retail, 'code': 'RETAIL', 'name': 'Retail', 'defaultList': true, 'pricesIncludeVat': true, 'minChargeableM2': 0.25},
        {'id': contractorList, 'code': 'CONTR', 'name': 'Contractors', 'defaultList': false, 'pricesIncludeVat': false, 'minChargeableM2': 0.5},
      ],
      'prices': [
        {'listId': retail, 'productId': clear6, 'pricePerM2': 27000.00},
        {'listId': contractorList, 'productId': clear6, 'pricePerM2': 21500.00},
      ],
      'customers': [
        {'id': walkIn, 'code': 'WALK-IN', 'name': 'Walk-in customer', 'type': 'WALK_IN', 'tin': null, 'phone': null, 'priceListId': null, 'defaultCustomer': true},
        {'id': contractor, 'code': 'CUS-WH-00007', 'name': 'Kigali Builders', 'type': 'ACCOUNT', 'tin': '102345678', 'phone': null, 'priceListId': contractorList, 'defaultCustomer': false},
      ],
      'numbers': ['MINV-WH-2026-000101', 'MINV-WH-2026-000102', 'MINV-WH-2026-000103'],
      'discountLimit': 10.00,
      'decimals': 0,
      'company': {'name': 'High Bytes Ltd', 'tin': '100000001', 'address': 'KK 15 Rd, Kigali', 'phone': '+250788000111'},
      'ebmMode': 'SIMULATOR',
      'downloadedAt': '2026-10-13T08:00:00',
      'reportedBroken': <String>[],
      'cashDeclared': null,
    };

class FakeServer {
  final List<http.Request> requests = [];
  final List<Map<String, dynamic>> sales = [];
  final List<Map<String, dynamic>> breakages = [];
  Map<String, dynamic>? trip = tripJson(); // null: the driver is on no trip on the road

  /// When set, POST /sales answers this status and message instead.
  (int, String)? refuseSales;
  bool revoked = false;
  bool signNow = false;
  bool simulated = true; // signed by the EBM simulator; false: as by a real VSDC
  String? qrSvg; // the receipt's QR code once signed

  late final MockClient client = MockClient((request) async {
    requests.add(request);
    final path = request.url.path;
    if (revoked && path != '/api/v1/messages') return _json({'error': 'unauthorized', 'message': 'Sign in'}, 401);
    if (path == '/api/v1/messages') return _json({'mobile.app': 'High Bytes Mobile POS'});
    if (path == '/api/v1/auth/login') {
      final body = jsonDecode(request.body) as Map;
      if (body['password'] != 'test-password') return _json({'error': 'mobile.login.invalid', 'message': 'Wrong username or password'}, 422);
      return _json({'token': 'token-1', 'device': 'ffffffff-0000-0000-0000-000000000001', 'user': {'username': body['username'], 'fullName': 'Jean Driver'}});
    }
    if (path == '/api/v1/auth/logout') return _json({'username': 'driver-m7', 'fullName': 'Jean Driver'});
    if (path == '/api/v1/trip') {
      final t = trip;
      return t == null ? _json({'error': 'mobile.trip.none', 'message': 'You are on no trip on the road: the supervisor confirms the departure first'}, 422) : _json(t);
    }
    if (path == '/api/v1/sales' && request.method == 'POST') {
      if (refuseSales != null) return _json({'error': 'mobile.badRequest', 'message': refuseSales!.$2}, refuseSales!.$1);
      final sale = Map<String, dynamic>.from(jsonDecode(request.body) as Map);
      sales.add(sale);
      return _json(_result(sale, signed: signNow));
    }
    if (path.startsWith('/api/v1/sales/')) {
      final id = path.split('/').last;
      final sale = sales.firstWhere((s) => s['clientId'] == id);
      return _json(_result(sale, signed: true));
    }
    if (path == '/api/v1/breakages') {
      final b = Map<String, dynamic>.from(jsonDecode(request.body) as Map);
      breakages.add(b);
      return _json({'clientId': b['clientId'], 'outcome': 'RECORDED', 'unitCode': 'U-WH-000002', 'status': 'REPORTED', 'decisionNote': null});
    }
    if (path.startsWith('/api/v1/breakages/')) {
      return _json({'clientId': path.split('/').last, 'outcome': 'ALREADY', 'unitCode': 'U-WH-000002', 'status': 'ACCEPTED', 'decisionNote': 'Checked on the photo'});
    }
    if (path == '/api/v1/trip/declaration') {
      final body = jsonDecode(request.body) as Map;
      return _json({'tripId': body['tripId'], 'tripNumber': 'TRP-WH-2026-000020', 'cashDeclared': body['amount'], 'declaredAt': '2026-10-13T18:00:00'});
    }
    return _json({'error': 'notFound', 'message': 'Not found'}, 404);
  });

  Map<String, dynamic> _result(Map<String, dynamic> sale, {required bool signed}) => {
        'clientId': sale['clientId'],
        'outcome': 'ACCEPTED',
        'number': sale['number'],
        'invoiceId': '99999999-0000-0000-0000-000000000001',
        'total': sale['total'],
        'ebm': signed
            ? {
                'status': 'SIGNED', 'signed': true, 'simulated': simulated, 'invcNo': 41, 'receiptLabel': '41/41 NS', 'sdcId': 'SDC010000001', //
                'mrcNo': 'WIS00000001', 'internalData': 'ABCD-EFGH', 'signature': 'IJKL-MNOP', 'signedAt': _stamp(DateTime.now()), 'qrSvg': qrSvg,
              }
            : {'status': 'QUEUED', 'signed': false, 'simulated': simulated, 'invcNo': 41},
        'conflict': null,
      };

  /// dd/MM/yyyy HH:mm, as the server writes the signing time.
  static String _stamp(DateTime t) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} ${two(t.hour)}:${two(t.minute)}';
  }

  http.Response _json(Object body, [int status = 200]) =>
      http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

  List<http.Request> posts(String path) => requests.where((r) => r.method == 'POST' && r.url.path == '/api/v1$path').toList();
}

class FakeNetwork implements Network {
  bool on = true;
  final StreamController<bool> _changes = StreamController<bool>.broadcast();

  @override
  Future<bool> isOnline() async => on;

  @override
  Stream<bool> get changes => _changes.stream;

  void set(bool value) {
    on = value;
    _changes.add(value);
  }
}

/// The app's English texts, as shipped (assets/texts/en.json).
Map<String, String> englishTexts() =>
    Map<String, String>.from(jsonDecode(File('assets/texts/en.json').readAsStringSync()) as Map);

PosController controllerFor(FakeServer server, FakeNetwork network, {Store? store}) => PosController(
      store: store ?? MemoryStore(),
      api: Api(server.client, userAgent: 'test'),
      network: network,
      bundledTexts: (lang) async => englishTexts(),
      defaultServer: 'https://pos.example.rw',
      syncEvery: const Duration(hours: 1),
    );

/// Lets the controller's unawaited work (a sync after a sale) finish.
Future<void> settle() async {
  for (var i = 0; i < 20; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}
