// High Bytes POS - what the phone keeps: the trip download (the API's TripBundle, SYNC-01), the sales made on it and the
// breakage reports, each saved as the JSON the server sends or expects (controller/api/MobileApi.java on the server).

import 'money.dart';

Map<String, dynamic> _map(Object? v) => v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};
List<Map<String, dynamic>> _maps(Object? v) => v is List ? v.map(_map).toList() : <Map<String, dynamic>>[];
String _s(Object? v) => v == null ? '' : v.toString();
String? _sn(Object? v) => v?.toString();
int _i(Object? v) => v is num ? v.toInt() : int.tryParse(_s(v)) ?? 0;
bool _b(Object? v) => v == true;

/// The phone's sign-in: its token, the server it belongs to and who signed in.
class Session {
  Session({required this.token, required this.device, required this.username, required this.fullName, required this.server});

  factory Session.fromJson(Map<String, dynamic> j) => Session(
      token: _s(j['token']), device: _s(j['device']), username: _s(j['username']), fullName: _s(j['fullName']), server: _s(j['server']));

  final String token;
  final String device;
  final String username;
  final String fullName;
  final String server;

  Map<String, dynamic> toJson() => {'token': token, 'device': device, 'username': username, 'fullName': fullName, 'server': server};
}

class Vehicle {
  Vehicle(Map<String, dynamic> j)
      : plate = _s(j['plate']),
        model = _s(j['model']);

  final String plate;
  final String model;
}

class Trip {
  Trip(Map<String, dynamic> j)
      : id = _s(j['id']),
        number = _s(j['number']),
        area = _s(j['area']),
        driver = _s(j['driver']),
        vehicle = Vehicle(_map(j['vehicle']));

  final String id;
  final String number;
  final String area;
  final String driver;
  final Vehicle vehicle;
}

/// A unit on the vehicle (a sheet or a cut piece) with its glass, size and tax letter.
class Unit {
  Unit(Map<String, dynamic> j)
      : id = _s(j['id']),
        code = _s(j['code']),
        productId = _s(j['productId']),
        productCode = _s(j['productCode']),
        productName = _s(j['productName']),
        widthMm = _i(j['widthMm']),
        heightMm = _i(j['heightMm']),
        taxCode = _s(j['taxCode']),
        vatRate = decimalText(j['vatRate']);

  final String id;
  final String code;
  final String productId;
  final String productCode;
  final String productName;
  final int widthMm;
  final int heightMm;
  final String taxCode;
  final String vatRate;

  String get size => '$widthMm × $heightMm mm';
}

class PriceList {
  PriceList(Map<String, dynamic> j)
      : id = _s(j['id']),
        defaultList = _b(j['defaultList']),
        pricesIncludeVat = _b(j['pricesIncludeVat']),
        minChargeableM2 = decimalText(j['minChargeableM2']);

  final String id;
  final bool defaultList;
  final bool pricesIncludeVat;
  final String minChargeableM2;
}

class Price {
  Price(Map<String, dynamic> j)
      : listId = _s(j['listId']),
        productId = _s(j['productId']),
        pricePerM2 = decimalText(j['pricePerM2']);

  final String listId;
  final String productId;
  final String pricePerM2;
}

class Customer {
  Customer(Map<String, dynamic> j)
      : id = _s(j['id']),
        code = _s(j['code']),
        name = _s(j['name']),
        tin = _s(j['tin']),
        priceListId = _sn(j['priceListId']),
        defaultCustomer = _b(j['defaultCustomer']);

  final String id;
  final String code;
  final String name;
  final String tin;
  final String? priceListId;
  final bool defaultCustomer;

  String get label => code.isEmpty ? name : '$name ($code)';
}

class Company {
  Company(Map<String, dynamic> j)
      : name = _s(j['name']),
        tin = _s(j['tin']),
        address = _s(j['address']),
        phone = _s(j['phone']);

  final String name;
  final String tin;
  final String address;
  final String phone;
}

/// The trip as downloaded: prices frozen for the trip, its invoice numbers, the driver's discount limit. The JSON is kept
/// as it came, plus what the phone learns later (the cash declared).
class Bundle {
  Bundle(this.json)
      : trip = Trip(_map(json['trip'])),
        units = _maps(json['units']).map(Unit.new).toList(),
        priceLists = _maps(json['priceLists']).map(PriceList.new).toList(),
        prices = _maps(json['prices']).map(Price.new).toList(),
        customers = _maps(json['customers']).map(Customer.new).toList(),
        numbers = (json['numbers'] is List ? (json['numbers'] as List).map((n) => n.toString()).toList() : <String>[]),
        discountLimit = decimalText(json['discountLimit']),
        decimals = _i(json['decimals']),
        company = Company(_map(json['company'])),
        reportedBroken = (json['reportedBroken'] is List ? (json['reportedBroken'] as List).map((n) => n.toString()).toSet() : <String>{});

  final Map<String, dynamic> json;
  final Trip trip;
  final List<Unit> units;
  final List<PriceList> priceLists;
  final List<Price> prices;
  final List<Customer> customers;
  final List<String> numbers;
  final String discountLimit;
  final int decimals;
  final Company company;
  final Set<String> reportedBroken;

  String? get cashDeclared => json['cashDeclared'] == null ? null : decimalText(json['cashDeclared']);
  String? get cashDeclaredAt => _sn(json['cashDeclaredAt']);

  Bundle declared(Object? amount, Object? at) => Bundle({...json, 'cashDeclared': amount, 'cashDeclaredAt': at});

  Unit? unit(String id) {
    for (final u in units) {
      if (u.id == id) return u;
    }
    return null;
  }
}

/// A unit in the cart: the price per m² the driver charges and why, when it is not the list price.
class CartLine {
  CartLine(this.unitId, this.pricePerM2, [this.priceReason = '']);

  final String unitId;
  String pricePerM2;
  String priceReason;
}

class Cart {
  final List<CartLine> lines = [];
  String? customerId;
  String buyerName = '';
  String buyerTin = '';
  String cash = '';
  String tendered = '';
  String momo = '';
  String momoRef = '';
}

class SaleLine {
  SaleLine(this.json);

  final Map<String, dynamic> json;

  String get unitId => _s(json['unitId']);
  String get code => _s(json['code']);
  String get productName => _s(json['productName']);
  int get widthMm => _i(json['widthMm']);
  int get heightMm => _i(json['heightMm']);
  String get pricePerM2 => decimalText(json['pricePerM2']);
  String get listPrice => decimalText(json['listPrice']);
  String get priceReason => _s(json['priceReason']);
  String get amount => decimalText(json['amount']);
  String get taxCode => _s(json['taxCode']);
  String get vatRate => decimalText(json['vatRate']);
}

class SalePayment {
  SalePayment(this.json);

  final Map<String, dynamic> json;

  String get method => _s(json['method']);
  String get amount => decimalText(json['amount']);
  String get reference => _s(json['reference']);
}

/// What the server said about a sale's EBM receipt (the API's EbmView, SYNC-06).
class Ebm {
  Ebm(this.json);

  final Map<String, dynamic> json;

  bool get signed => _b(json['signed']);
  bool get simulated => _b(json['simulated']);
  String get receiptLabel => _s(json['receiptLabel']);
  String get sdcId => _s(json['sdcId']);
  String get mrcNo => _s(json['mrcNo']);
  String get internalData => _s(json['internalData']);
  String get signature => _s(json['signature']);
  String get signedAt => _s(json['signedAt']);
  String? get qrSvg => _sn(json['qrSvg']);
}

/// A sale made on the phone: pending until the server takes it, then accepted, signed (EBM) or a conflict.
class Sale {
  Sale(this.json);

  final Map<String, dynamic> json;

  String get clientId => _s(json['clientId']);
  int get seq => _i(json['seq']);
  String get number => _s(json['number']);
  String get tripId => _s(json['tripId']);
  String? get customerId => _sn(json['customerId']);
  String get customerName => _s(json['customerName']);
  String get buyerName => _s(json['buyerName']);
  String get buyerTin => _s(json['buyerTin']);
  String get createdAt => _s(json['createdAt']);
  List<SaleLine> get lines => _maps(json['lines']).map(SaleLine.new).toList();
  List<SalePayment> get payments => _maps(json['payments']).map(SalePayment.new).toList();
  String get total => decimalText(json['total']);
  String? get cashTendered => json['cashTendered'] == null ? null : decimalText(json['cashTendered']);
  String? get change => json['change'] == null ? null : decimalText(json['change']);

  /// pending | accepted | signed | conflict
  String get status => _s(json['status']);
  Map<String, dynamic>? get result => json['result'] is Map ? _map(json['result']) : null;
  Ebm? get ebm => result != null && result!['ebm'] is Map ? Ebm(_map(result!['ebm'])) : null;
  String get conflictText => result != null && result!['conflict'] is Map ? _s(_map(result!['conflict'])['reasonText']) : '';

  /// The server's answer (SaleResult): accepted (signed by EBM or not yet) or a conflict for the supervisor.
  void apply(Map<String, dynamic> result, String now) {
    json['result'] = result;
    final ebm = result['ebm'] is Map ? _map(result['ebm']) : null;
    json['status'] = result['outcome'] == 'ACCEPTED' ? (ebm != null && ebm['signed'] == true ? 'signed' : 'accepted') : 'conflict';
    json['syncedAt'] ??= now;
  }

  /// What the API's POST /sales takes (MobileSaleService.SaleRequest).
  Map<String, dynamic> payload() => {
        'clientId': clientId,
        'number': number,
        'tripId': tripId,
        'customerId': customerId,
        'buyerName': buyerName.isEmpty ? null : buyerName,
        'buyerTin': buyerTin.isEmpty ? null : buyerTin,
        'createdAt': createdAt,
        'lines': lines
            .map((l) => {
                  'unitId': l.unitId,
                  'pricePerM2': num.parse(l.pricePerM2),
                  'priceReason': l.priceReason.isEmpty ? null : l.priceReason,
                  'amount': num.parse(l.amount),
                })
            .toList(),
        'payments': payments
            .map((p) => {'method': p.method, 'amount': num.parse(p.amount), 'reference': p.reference.isEmpty ? null : p.reference})
            .toList(),
        'total': num.parse(total),
        'cashTendered': cashTendered == null ? null : num.parse(cashTendered!),
      };
}

/// Breakage on the road (MPOS-06): pending until sent, then sent (the supervisor decides) or refused by the server.
class Breakage {
  Breakage(this.json);

  final Map<String, dynamic> json;

  String get clientId => _s(json['clientId']);
  int get seq => _i(json['seq']);
  String get tripId => _s(json['tripId']);
  String get unitId => _s(json['unitId']);
  String get code => _s(json['code']);
  String get reason => _s(json['reason']);
  String get place => _s(json['place']);
  String get happenedAt => _s(json['happenedAt']);
  String? get photo => _sn(json['photo']);

  /// pending | sent | refused
  String get status => _s(json['status']);
  String get error => _s(json['error']);
  Map<String, dynamic>? get result => json['result'] is Map ? _map(json['result']) : null;

  /// REPORTED | ACCEPTED | REJECTED, once sent
  String get decision => result == null ? '' : _s(result!['status']);
  String get decisionNote => result == null ? '' : _s(result!['decisionNote']);

  /// pending | sent | accepted | rejected | refused: where the report stands for the driver.
  String get stage => status == 'pending'
      ? 'pending'
      : status == 'refused'
          ? 'refused'
          : decision == 'ACCEPTED'
              ? 'accepted'
              : decision == 'REJECTED'
                  ? 'rejected'
                  : 'sent';
}
