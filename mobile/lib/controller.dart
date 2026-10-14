// High Bytes POS - the app's state and rules: the same as the web app's app.js (MPOS-01..07, SYNC-01..08, AUD-07).
//
// A driver signs in once on their phone (a token of its own), downloads their trip (the units on the vehicle, the
// customers, the trip's frozen prices and a block of invoice numbers) and sells: by scanning a label or from the
// vehicle's stock, at the trip's prices or lower within their limit, for cash and/or mobile money. Every sale is saved on
// the phone first with its own UUID and invoice number, its receipt shown at once ("pending signature"), then sent to the
// server when there is network, oldest first, retrying with backoff; the server takes it once (its clientId), or keeps it
// for the supervisor. The signed receipt (EBM) is fetched afterwards. At the end of the day the driver reports breakage on
// the road (a photo, the reason, the place; queued offline like the sales) and declares the cash they hold.

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import 'api.dart';
import 'models.dart';
import 'money.dart' as m;
import 'store.dart';
import 'texts.dart';

enum PosView { sell, cart, receipt, sales, breakage, declare }

/// A message at the top of the screen: info, ok, warn or danger.
class Notice {
  const Notice(this.kind, this.text);

  final String kind;
  final String text;
}

/// Whether the phone has a network, and when that changes.
abstract class Network {
  Future<bool> isOnline();

  Stream<bool> get changes;
}

/// A unit's price for the cart's customer: the list it comes from and the price per m².
class UnitPrice {
  const UnitPrice(this.list, this.price);

  final PriceList list;
  final String price;
}

class PricedLine {
  PricedLine(this.line, this.unit, this.price, this.pricePerM2, this.priceOk, this.changed, this.amount);

  final CartLine line;

  /// The price per m² as charged: what was typed, read as an amount (a comma as the decimal point).
  final String pricePerM2;
  final Unit unit;
  final UnitPrice? price;
  final bool priceOk;
  final bool changed;
  final BigInt amount;
}

class PricedCart {
  PricedCart(this.customer, this.lines, this.total);

  final Customer? customer;
  final List<PricedLine> lines;
  final BigInt total;
}

class BreakageForm {
  String unitId = '';
  String reason = '';
  String place = '';
  String? photo; // a JPEG as a data URL
}

class PosController extends ChangeNotifier {
  PosController({
    required this.store,
    required this.api,
    required this.network,
    required this.bundledTexts,
    this.defaultServer = '',
    this.allowHttp = false,
    this.systemLanguage = 'en',
    this.syncEvery = const Duration(seconds: 30),
  });

  static const languages = ['en', 'fr'];
  static const _retryMax = 300000;
  static const _uuid = Uuid();

  final Store store;
  final Api api;
  final Network network;
  final Future<Map<String, String>> Function(String lang) bundledTexts;
  final String defaultServer;
  final bool allowHttp;
  final String systemLanguage;
  final Duration syncEvery;
  final Texts texts = Texts();

  bool started = false;
  Session? session;
  String lang = 'en';
  String server = '';
  Bundle? bundle;
  List<Sale> sales = [];
  List<Breakage> breakages = [];
  Cart cart = Cart();
  PosView view = PosView.sell;
  String? receiptId;
  String search = '';
  Notice? notice;
  Map<String, String> errors = {};
  bool busy = false;
  bool syncing = false;
  bool online = true;
  BreakageForm breakage = BreakageForm();
  String declareAmount = '';
  int retryDelay = 2000;

  Timer? _retry;
  Timer? _tick;
  StreamSubscription<bool>? _net;
  bool _disposed = false;

  String t(String key, [List<Object?> args = const []]) => texts.t(key, args);

  String tn(String key, int count, [List<Object?> args = const []]) => texts.tn(key, count, args);

  String money(BigInt n) => m.money(n, bundle?.decimals ?? 0);

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  // ================================================================ starting

  Future<void> start() async {
    api.deviceKey = await _deviceKey();
    final savedLang = await store.get('lang');
    lang = languages.contains(savedLang) ? savedLang as String : (systemLanguage == 'fr' ? 'fr' : 'en');
    final s = await store.get('session');
    session = s is Map ? Session.fromJson(Map<String, dynamic>.from(s)) : null;
    server = session?.server ?? (await store.get('server') as String?) ?? defaultServer;
    api
      ..server = server
      ..token = session?.token
      ..lang = lang;
    await loadTexts(lang);
    final b = await store.get('bundle');
    bundle = b is Map ? Bundle(Map<String, dynamic>.from(b)) : null;
    sales = (await store.all('sales')).map(Sale.new).toList();
    breakages = (await store.all('breakages')).map(Breakage.new).toList();
    online = await network.isOnline();
    _net = network.changes.listen((on) {
      final was = online;
      online = on;
      if (on && !was) retryDelay = 2000;
      _changed();
      if (on && !was) unawaited(sync());
    });
    _tick = Timer.periodic(syncEvery, (_) {
      if (pendingCount > 0 || sales.any((s) => s.status == 'accepted') || breakages.any((b) => b.status == 'sent' && b.decision == 'REPORTED')) {
        unawaited(sync());
      }
    });
    started = true;
    _changed();
    if (session != null && online) {
      await downloadTrip();
      if (bundle != null) await sync();
    }
  }

  /// The app comes back to the screen: send what waits.
  void resumed() => unawaited(sync());

  @override
  void dispose() {
    _disposed = true;
    _retry?.cancel();
    _tick?.cancel();
    _net?.cancel();
    super.dispose();
  }

  Future<String> _deviceKey() async {
    final key = await store.get('device-key');
    if (key is String && key.isNotEmpty) return key;
    final fresh = _uuid.v4();
    await store.set('device-key', fresh);
    return fresh;
  }

  /// The texts in a language: the copy in the app, what the server sent last time, then the server's current ones.
  Future<void> loadTexts(String language) async {
    final base = await bundledTexts(language);
    final cached = await store.get('texts-$language');
    texts.use({...base, if (cached is Map) ...cached.map((k, v) => MapEntry(k.toString(), v.toString()))});
    _changed();
    final fresh = await api.messages(language);
    if (fresh != null) {
      texts.use({...base, ...fresh});
      await store.set('texts-$language', fresh);
      _changed();
    }
  }

  Future<void> setLanguage(String language) async {
    if (!languages.contains(language)) return;
    lang = language;
    api.lang = language;
    await store.set('lang', language);
    await loadTexts(language);
  }

  Future<void> toggleLanguage() => setLanguage(lang == 'en' ? 'fr' : 'en');

  // ================================================================ the trip on the phone

  List<Customer> get customers => bundle?.customers ?? const [];

  Customer? get defaultCustomer {
    for (final c in customers) {
      if (c.defaultCustomer) return c;
    }
    return customers.isEmpty ? null : customers.first;
  }

  Customer? customerOf(String? id) {
    for (final c in customers) {
      if (c.id == id) return c;
    }
    return defaultCustomer;
  }

  Set<String> get soldHere => {for (final s in sales) for (final l in s.lines) l.unitId};

  /// Units reported broken on the road (FLT-08): by this phone (not refused or rejected) or known to the server.
  Set<String> get reportedBroken => {
        ...?bundle?.reportedBroken,
        for (final b in breakages)
          if (b.status != 'refused' && b.decision != 'REJECTED') b.unitId,
      };

  /// The units still on the vehicle and for sale as far as this phone knows (MPOS-02).
  List<Unit> get unitsOnBoard {
    final b = bundle;
    if (b == null) return const [];
    final sold = soldHere;
    final broken = reportedBroken;
    return b.units.where((u) => !sold.contains(u.id) && !broken.contains(u.id)).toList();
  }

  /// The glass's price for a customer (MobileSales.Snapshot.resolve): their list, then the default list.
  UnitPrice? priceFor(Customer? customer, Unit unit) {
    final b = bundle!;
    PriceList? defaultList;
    PriceList? own;
    for (final l in b.priceLists) {
      if (l.defaultList) defaultList ??= l;
      if (customer != null && l.id == customer.priceListId) own = l;
    }
    own ??= defaultList;
    Price? find(PriceList? list) {
      if (list == null) return null;
      for (final p in b.prices) {
        if (p.listId == list.id && p.productId == unit.productId) return p;
      }
      return null;
    }

    var price = find(own);
    var list = own;
    if (price == null && own != defaultList) {
      price = find(defaultList);
      list = defaultList;
    }
    return price == null || list == null ? null : UnitPrice(list, price.pricePerM2);
  }

  BigInt unitAmount(UnitPrice price, Unit unit, [String? pricePerM2]) => m.lineAmount(pricePerM2 ?? price.price, unit.widthMm, unit.heightMm,
      pricesIncludeVat: price.list.pricesIncludeVat, minChargeableM2: price.list.minChargeableM2, vatRate: unit.vatRate, decimals: bundle!.decimals);

  String? nextNumber() {
    final used = {for (final s in sales) s.number};
    for (final n in bundle?.numbers ?? const <String>[]) {
      if (!used.contains(n)) return n;
    }
    return null;
  }

  List<Sale> get pending => sales.where((s) => s.status == 'pending').toList();

  List<Breakage> get pendingBreakages => breakages.where((b) => b.status == 'pending').toList();

  /// Everything this phone still has to send: sales and breakage reports.
  int get pendingCount => pending.length + pendingBreakages.length;

  Future<void> downloadTrip() async {
    if (!online) return _notice('warn', t('mobile.offline.needed'));
    busy = true;
    _changed();
    try {
      final json = await api.request('GET', '/trip');
      final fresh = Bundle(Map<String, dynamic>.from(json as Map));
      if (bundle != null && bundle!.trip.id != fresh.trip.id) {
        if (pendingCount > 0) {
          _notice('danger', t('mobile.trip.otherPending'));
          return;
        }
        await store.clear('sales');
        await store.clear('breakages');
        sales = [];
        breakages = [];
      }
      bundle = fresh;
      await store.set('bundle', fresh.json);
      cart = Cart();
      _notice('ok', tn('mobile.trip.downloaded', fresh.units.length, [fresh.trip.number, fresh.units.length]));
    } on ApiError catch (e) {
      await handleError(e);
    } finally {
      busy = false;
      _changed();
    }
  }

  // ================================================================ syncing (SYNC-02..06)

  /// Sends the pending sales, oldest first, then the breakage reports; stops at the first that cannot reach the server
  /// and tries again later, waiting twice as long each time (at most 5 minutes).
  Future<void> sync() async {
    if (syncing || session == null || !online) return;
    syncing = true;
    _changed();
    try {
      // Oldest first, until none waits: a sale made while this runs goes in the same run
      for (var sale = _firstOf(pending); sale != null; sale = _firstOf(pending)) {
        final result = await api.request('POST', '/sales', sale.payload());
        sale.apply(Map<String, dynamic>.from(result as Map), _now());
        await store.put('sales', sale.json);
        _changed();
      }
      for (var b = _firstOf(pendingBreakages); b != null; b = _firstOf(pendingBreakages)) {
        try {
          b.json['result'] = await api.request('POST', '/breakages', {
            'clientId': b.clientId,
            'tripId': b.tripId,
            'unitId': b.unitId,
            'reason': b.reason,
            'place': b.place,
            'happenedAt': b.happenedAt,
            'photo': b.photo,
            'photoName': '${b.code}.jpg',
          });
          b.json['status'] = 'sent';
        } on ApiError catch (e) {
          if (e.offline || e.unauthorized || e.status == null || e.status! >= 500) rethrow;
          b.json['status'] = 'refused';
          b.json['error'] = e.message ?? t('mobile.error.server');
        }
        b.json['photo'] = null; // sent or refused: the phone keeps no copy
        await store.put('breakages', b.json);
        _changed();
      }
      retryDelay = 2000;
      await refreshSignatures();
    } on ApiError catch (e) {
      if (e.unauthorized) {
        await signedOutByServer();
        return;
      }
      if (e.status != null && e.status! < 500) _notice('danger', e.message ?? t('mobile.error.server'));
      _retry?.cancel();
      _retry = Timer(Duration(milliseconds: retryDelay), sync);
      retryDelay = math.min(retryDelay * 2, _retryMax);
    } finally {
      syncing = false;
      _changed();
    }
  }

  static T? _firstOf<T>(List<T> list) => list.isEmpty ? null : list.first;

  /// Fetches the EBM signature of sales taken but not signed yet (SYNC-06), and the supervisor's decision on breakage.
  Future<void> refreshSignatures() async {
    final waiting = sales.where((s) => s.status == 'accepted').toList();
    for (final sale in waiting.skip(math.max(0, waiting.length - 30))) {
      final result = await api.request('GET', '/sales/${sale.clientId}');
      sale.apply(Map<String, dynamic>.from(result as Map), _now());
      await store.put('sales', sale.json);
    }
    final deciding = breakages.where((b) => b.status == 'sent' && b.decision == 'REPORTED').toList();
    for (final b in deciding.skip(math.max(0, deciding.length - 30))) {
      b.json['result'] = await api.request('GET', '/breakages/${b.clientId}');
      await store.put('breakages', b.json);
    }
    if (waiting.isNotEmpty || deciding.isNotEmpty) _changed();
  }

  /// "Sync now": at once, the waiting time reset.
  void syncNow() {
    retryDelay = 2000;
    unawaited(sync());
  }

  // ================================================================ selling (MPOS-02..05)

  bool inCart(String unitId) => cart.lines.any((l) => l.unitId == unitId);

  /// Adds a unit to the sale, or takes it out when it is in already (a tap on the vehicle's stock).
  void toggleUnit(Unit unit) {
    notice = null;
    if (inCart(unit.id)) {
      removeLine(unit.id);
      return;
    }
    final p = priceFor(customerOf(cart.customerId), unit);
    if (p == null) return _notice('danger', t('mobile.sell.noPrice', [unit.code]));
    cart.lines.add(CartLine(unit.id, p.price));
    _notice('ok', t('mobile.sell.added', [unit.code]));
  }

  void removeLine(String unitId) {
    cart.lines.removeWhere((l) => l.unitId == unitId);
    _changed();
  }

  /// A label scanned or typed (MPOS-03): only units on this vehicle, not sold yet (MPOS-02).
  void addByCode(String raw) {
    notice = null;
    final code = raw.trim().toUpperCase();
    if (code.isEmpty) return _changed();
    final b = bundle!;
    Unit? unit;
    for (final u in b.units) {
      if (u.code == code) unit = u;
    }
    if (unit == null) return _notice('danger', t('mobile.sell.notOnVehicle', [code]));
    if (soldHere.contains(unit.id)) return _notice('warn', t('mobile.sell.alreadySold', [code]));
    if (reportedBroken.contains(unit.id)) return _notice('warn', t('mobile.sell.reportedBroken', [code]));
    if (inCart(unit.id)) return _notice('info', t('mobile.sell.alreadyInCart', [code]));
    toggleUnit(unit);
  }

  void setSearch(String value) {
    search = value;
    _changed();
  }

  /// The cart priced for its customer: each line's unit, list price and amount; the total.
  PricedCart priced() {
    final customer = customerOf(cart.customerId);
    final b = bundle!;
    final lines = <PricedLine>[];
    for (final l in cart.lines) {
      final unit = b.unit(l.unitId);
      if (unit == null) continue;
      final p = priceFor(customer, unit);
      final typed = m.amountText(l.pricePerM2) ?? l.pricePerM2.trim();
      final priceOk = p != null && RegExp(r'^\d+(\.\d{1,2})?$').hasMatch(typed) && m.withinLimit(p.price, typed, b.discountLimit);
      final amount = p != null && priceOk ? unitAmount(p, unit, typed) : BigInt.zero;
      // A price that is not the list price asks for a reason (one not allowed shows its error first)
      final changed = p != null && (!priceOk || m.scaled(typed, 2) != m.scaled(p.price, 2));
      lines.add(PricedLine(l, unit, p, typed, priceOk, changed, amount));
    }
    return PricedCart(customer, lines, lines.fold(BigInt.zero, (sum, x) => sum + x.amount));
  }

  /// VAT included in the cart's lines (Vat.totals), in cents.
  BigInt cartVat(PricedCart p) =>
      m.vatOf(p.lines.where((x) => x.priceOk).map((x) => m.VatLine(x.unit.taxCode, x.unit.vatRate, m.text(x.amount, bundle!.decimals))), bundle!.decimals);

  void setCustomer(String? id) {
    cart.customerId = id;
    final customer = customerOf(id);
    for (final l in cart.lines) {
      final unit = bundle!.unit(l.unitId);
      final p = unit == null ? null : priceFor(customer, unit);
      if (p != null) {
        l.pricePerM2 = p.price;
        l.priceReason = '';
      }
    }
    if (customer != null && !customer.defaultCustomer && customer.tin.isNotEmpty) cart.buyerTin = customer.tin;
    _changed();
  }

  void setPrice(String unitId, String value) {
    for (final l in cart.lines) {
      if (l.unitId == unitId) l.pricePerM2 = value;
    }
    errors.remove('price-$unitId');
    _changed();
  }

  void setReason(String unitId, String value) {
    for (final l in cart.lines) {
      if (l.unitId == unitId) l.priceReason = value;
    }
    errors.remove('reason-$unitId');
  }

  /// A payment field changed: its error goes (cash and mobile money share "must add up").
  void setCartField(String field, String value) {
    switch (field) {
      case 'buyerName':
        cart.buyerName = value;
      case 'buyerTin':
        cart.buyerTin = value;
        errors.remove('tin');
      case 'cash':
        cart.cash = value;
        errors.remove('pay');
      case 'tendered':
        cart.tendered = value;
        errors.remove('tendered');
      case 'momo':
        cart.momo = value;
        errors.remove('pay');
      case 'momoRef':
        cart.momoRef = value;
        errors.remove('momoRef');
    }
    _changed();
  }

  /// The cash the form shows: both payment fields left empty means the whole total in cash.
  String cashShown(PricedCart p) => cart.cash.trim().isEmpty && cart.momo.trim().isEmpty ? m.text(p.total, bundle!.decimals) : cart.cash;

  String? changeText(PricedCart p) {
    final d = bundle!.decimals;
    final tendered = _amount(cart.tendered, d);
    final cash = _amount(cashShown(p), d);
    if (cart.tendered.trim().isEmpty || tendered == null || cash == null) return null;
    final c = tendered - cash;
    return c >= BigInt.zero ? t('mobile.pay.change', [money(c)]) : null;
  }

  void allCash() {
    cart.cash = m.text(priced().total, bundle!.decimals);
    cart.momo = '';
    errors.remove('pay');
    _changed();
  }

  void allMomo() {
    cart.momo = m.text(priced().total, bundle!.decimals);
    cart.cash = '0';
    cart.tendered = '';
    errors.remove('pay');
    _changed();
  }

  static BigInt? _amount(String typed, int decimals) {
    final s = m.amountText(typed);
    return s == null ? null : m.scaled(s, decimals);
  }

  /// Checks the cart and saves the sale on the phone with its number; it syncs when it can. False when refused.
  Future<bool> completeSale() async {
    final b = bundle!;
    final d = b.decimals;
    final p = priced();
    final e = <String, String>{};
    if (p.lines.isEmpty) e['cart'] = t('mobile.pay.empty');
    for (final x in p.lines) {
      final id = x.unit.id;
      if (x.price == null) {
        e['price-$id'] = t('mobile.sell.noPrice', [x.unit.code]);
      } else if (!x.priceOk) {
        e['price-$id'] = t('mobile.pay.priceLimit', [m.grouped(m.lowestPrice(x.price!.price, b.discountLimit), 2)]);
      } else if (x.changed && x.line.priceReason.trim().isEmpty) {
        e['reason-$id'] = t('mobile.pay.reasonNeeded');
      }
    }
    final tin = cart.buyerTin.replaceAll(RegExp(r'[\s-]'), '');
    if (tin.isNotEmpty && !RegExp(r'^\d{9}$').hasMatch(tin)) e['tin'] = t('mobile.pay.tin');
    final cashTyped = cashShown(p);
    final cash = cashTyped.trim().isEmpty ? BigInt.zero : _amount(cashTyped, d);
    final momo = cart.momo.trim().isEmpty ? BigInt.zero : _amount(cart.momo, d);
    if (cash == null || momo == null) {
      e['pay'] = t('mobile.pay.invalidAmount');
    } else if (cash + momo != p.total) {
      e['pay'] = t('mobile.pay.mustAddUp', [money(cash + momo), money(p.total)]);
    }
    if (momo != null && momo > BigInt.zero && cart.momoRef.trim().isEmpty) e['momoRef'] = t('mobile.pay.momoRef');
    BigInt? tendered;
    if (cash != null && cash > BigInt.zero && cart.tendered.trim().isNotEmpty) {
      tendered = _amount(cart.tendered, d);
      if (tendered == null) {
        e['tendered'] = t('mobile.pay.invalidAmount');
      } else if (tendered < cash) {
        e['tendered'] = t('mobile.pay.tenderedLow', [money(cash)]);
      }
    }
    final number = nextNumber();
    if (number == null) e['cart'] = t('mobile.pay.noNumber');
    errors = e;
    if (e.isNotEmpty) {
      _changed();
      return false;
    }

    final paidCash = cash!, paidMomo = momo!;
    final payments = <Map<String, dynamic>>[
      if (paidCash > BigInt.zero) {'method': 'CASH', 'amount': m.text(paidCash, d)},
      if (paidMomo > BigInt.zero) {'method': 'MOBILE_MONEY', 'amount': m.text(paidMomo, d), 'reference': cart.momoRef.trim()},
    ];
    final sale = Sale({
      'clientId': _uuid.v4(),
      'seq': DateTime.now().millisecondsSinceEpoch,
      'number': number,
      'tripId': b.trip.id,
      'customerId': p.customer?.id,
      'customerName': p.customer?.name ?? '',
      'buyerName': cart.buyerName.trim(),
      'buyerTin': tin,
      'createdAt': _now(),
      'lines': [
        for (final x in p.lines)
          {
            'unitId': x.unit.id,
            'code': x.unit.code,
            'productName': x.unit.productName,
            'widthMm': x.unit.widthMm,
            'heightMm': x.unit.heightMm,
            'pricePerM2': x.pricePerM2,
            'listPrice': x.price!.price,
            'priceReason': x.changed ? x.line.priceReason.trim() : '',
            'amount': m.text(x.amount, d),
            'taxCode': x.unit.taxCode,
            'vatRate': x.unit.vatRate,
          },
      ],
      'payments': payments,
      'total': m.text(p.total, d),
      'cashTendered': tendered == null ? null : m.text(tendered, d),
      'change': tendered == null ? null : m.text(tendered - paidCash, d),
      'status': 'pending',
      'result': null,
    });
    await store.put('sales', sale.json);
    sales.add(sale);
    cart = Cart();
    errors = {};
    receiptId = sale.clientId;
    view = PosView.receipt;
    _notice('ok', t('mobile.pay.saved', [number]));
    unawaited(sync());
    return true;
  }

  Sale? get receiptSale {
    for (final s in sales) {
      if (s.clientId == receiptId) return s;
    }
    return null;
  }

  void openReceipt(String clientId) {
    notice = null;
    receiptId = clientId;
    view = PosView.receipt;
    _changed();
    if (online && session != null) unawaited(refreshSignatures().catchError((Object _) {}));
  }

  // ================================================================ the end of the day (MPOS-06, MPOS-07)

  void setBreakageUnit(String id) {
    breakage.unitId = id;
    errors.remove('unit');
    _changed();
  }

  void setBreakageField(String field, String value) {
    if (field == 'reason') breakage.reason = value;
    if (field == 'place') breakage.place = value;
    errors.remove(field);
  }

  void setPhoto(String? dataUrl) {
    breakage.photo = dataUrl;
    errors.remove('photo');
    _changed();
  }

  void photoFailed() {
    errors['photo'] = t('mobile.breakage.photoBad');
    _changed();
  }

  /// The phone's position added to the place.
  void addPosition(String? at) {
    if (at == null) return _notice('warn', t('mobile.breakage.noPosition'));
    breakage.place = '${breakage.place.trim().isEmpty ? '' : '${breakage.place.trim()} '}($at)';
    errors.remove('place');
    _changed();
  }

  /// Saves a breakage report on the phone (MPOS-06): it syncs when it can, like a sale.
  Future<bool> saveBreakage() async {
    final f = breakage;
    final e = <String, String>{};
    final unit = bundle!.unit(f.unitId);
    if (unit == null) e['unit'] = t('mobile.breakage.unitNeeded');
    if (f.reason.trim().isEmpty) e['reason'] = t('mobile.breakage.reasonNeeded');
    if (f.place.trim().isEmpty) e['place'] = t('mobile.breakage.placeNeeded');
    if (f.photo == null) e['photo'] = t('mobile.breakage.photoNeeded');
    errors = e;
    if (e.isNotEmpty) {
      _changed();
      return false;
    }
    String cut(String s) => s.length > 255 ? s.substring(0, 255) : s;
    final b = Breakage({
      'clientId': _uuid.v4(),
      'seq': DateTime.now().millisecondsSinceEpoch,
      'tripId': bundle!.trip.id,
      'unitId': unit!.id,
      'code': unit.code,
      'productName': unit.productName,
      'reason': cut(f.reason.trim()),
      'place': cut(f.place.trim()),
      'happenedAt': _now(),
      'photo': f.photo,
      'status': 'pending',
      'result': null,
    });
    await store.put('breakages', b.json);
    breakages.add(b);
    cart.lines.removeWhere((l) => l.unitId == unit.id);
    breakage = BreakageForm();
    _notice('ok', t('mobile.breakage.saved', [unit.code]));
    unawaited(sync());
    return true;
  }

  void setDeclareAmount(String value) {
    declareAmount = value;
    errors.remove('declare');
  }

  /// The question before declaring, or null (and the error shown) when the amount is not a whole number.
  String? declareQuestion() {
    final value = declareAmount.trim();
    if (!RegExp(r'^\d+$').hasMatch(value)) {
      errors = {'declare': t('mobile.declare.invalid')};
      _changed();
      return null;
    }
    return t('mobile.declare.confirm', [money(m.scaled(value, bundle!.decimals))]);
  }

  /// Declares the cash the driver holds (MPOS-07), once, with network.
  Future<void> declareCash() async {
    final value = declareAmount.trim();
    if (!RegExp(r'^\d+$').hasMatch(value)) {
      errors = {'declare': t('mobile.declare.invalid')};
      return _changed();
    }
    if (!online) return _notice('warn', t('mobile.offline.needed'));
    busy = true;
    _changed();
    try {
      final r = Map<String, dynamic>.from(await api.request('POST', '/trip/declaration', {'tripId': bundle!.trip.id, 'amount': int.parse(value)}) as Map);
      bundle = bundle!.declared(r['cashDeclared'], r['declaredAt']);
      await store.set('bundle', bundle!.json);
      declareAmount = '';
      errors = {};
      _notice('ok', t('mobile.declare.saved', [money(m.scaled(r['cashDeclared'], bundle!.decimals))]));
    } on ApiError catch (e) {
      await handleError(e);
    } finally {
      busy = false;
      _changed();
    }
  }

  // ================================================================ signing in and out, closing the trip (SYNC-07, SYNC-08)

  /// The server address as typed, or null (and the error shown) when it is not one the app can use.
  String? _serverOf(String typed) {
    var s = typed.trim();
    while (s.endsWith('/')) {
      s = s.substring(0, s.length - 1);
    }
    final uri = Uri.tryParse(s);
    if (uri == null || !(uri.scheme == 'https' || uri.scheme == 'http') || uri.host.isEmpty) {
      errors = {'server': t('mobile.login.serverInvalid')};
      return null;
    }
    if (uri.scheme == 'http' && !allowHttp) {
      errors = {'server': t('mobile.login.serverHttps')};
      return null;
    }
    return s;
  }

  Future<void> signIn({required String serverTyped, required String username, required String password, required String deviceName}) async {
    notice = null;
    final address = _serverOf(serverTyped);
    if (address == null) return _changed();
    if (pendingCount > 0 && server.isNotEmpty && address != server) {
      errors = {'server': t('mobile.login.serverPending', [server])};
      return _changed();
    }
    if (!online) return _notice('warn', t('mobile.offline.needed'));
    if (username.trim().isEmpty || password.isEmpty) {
      errors = {'login': t('mobile.login.missing')};
      return _changed();
    }
    busy = true;
    _changed();
    try {
      api
        ..server = address
        ..token = null;
      final r = Map<String, dynamic>.from(await api.request('POST', '/auth/login', {
        'username': username.trim(),
        'password': password,
        'deviceId': api.deviceKey,
        'deviceName': deviceName.trim(),
      }) as Map);
      final user = Map<String, dynamic>.from(r['user'] as Map);
      session = Session(
          token: r['token'].toString(), device: r['device'].toString(), username: user['username'].toString(), fullName: user['fullName'].toString(), server: address);
      server = address;
      api.token = session!.token;
      await store.set('session', session!.toJson());
      await store.set('server', address);
      errors = {};
      view = PosView.sell;
      busy = false;
      await loadTexts(lang);
      await downloadTrip();
      unawaited(sync());
    } on ApiError catch (e) {
      api.server = server;
      errors = {'login': e.offline ? t('mobile.login.unreachable') : (e.message ?? t('mobile.error.server'))};
    } finally {
      busy = false;
      _changed();
    }
  }

  /// The phone's data for the trip is cleared, once nothing waits to sync (SYNC-07, SYNC-08).
  Future<void> closeTrip() async {
    if (pendingCount > 0) return _notice('danger', tn('mobile.close.pending', pendingCount, [pendingCount]));
    await store.clear('sales');
    await store.clear('breakages');
    await store.remove('bundle');
    sales = [];
    breakages = [];
    bundle = null;
    cart = Cart();
    view = PosView.sell;
    _notice('ok', t('mobile.close.done'));
  }

  Future<void> signOut() async {
    if (pendingCount > 0) return _notice('danger', tn('mobile.signOut.pending', pendingCount, [pendingCount]));
    try {
      await api.request('POST', '/auth/logout');
    } on ApiError {
      // offline or revoked already: the data goes anyway
    }
    await _forget();
    _notice('ok', t('mobile.signOut.done'));
  }

  Future<void> _forget() async {
    await store.clear('sales');
    await store.clear('breakages');
    await store.remove('bundle');
    await store.remove('session');
    session = null;
    api.token = null;
    bundle = null;
    sales = [];
    breakages = [];
    cart = Cart();
    view = PosView.sell;
  }

  /// The token was revoked or the user disabled: sales waiting stay until someone signs in again.
  Future<void> signedOutByServer() async {
    session = null;
    api.token = null;
    await store.remove('session');
    _notice('danger', t('mobile.login.expired'));
  }

  // ================================================================ navigation, notices and errors

  void go(PosView to) {
    notice = null;
    view = to;
    errors = {};
    _changed();
  }

  /// The system's back: to the screen this one came from; false when there is nowhere to go back to.
  bool back() {
    if (session == null || bundle == null || view == PosView.sell) return false;
    go(view == PosView.receipt ? PosView.sales : PosView.sell);
    return true;
  }

  void clearNotice() {
    notice = null;
    _changed();
  }

  void _notice(String kind, String text) {
    notice = Notice(kind, text);
    _changed();
  }

  Future<void> handleError(ApiError e) async {
    if (e.offline) return _notice('warn', t('mobile.offline.needed'));
    if (e.unauthorized) return signedOutByServer();
    _notice('danger', e.message ?? t('mobile.error.server'));
  }

  static String _now() => DateTime.now().toUtc().toIso8601String();
}
