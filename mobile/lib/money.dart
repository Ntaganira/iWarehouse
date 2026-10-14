// High Bytes POS - the server's sums with integers (MobileSales, Vat.lineAmount, Vat.totals), exactly as the web app's
// app.js does them, so the phone, the web app and the server always agree on every amount. Change the three together;
// test/money_test.dart holds the server's worked examples (MobileSalesTest).

final BigInt _ten = BigInt.from(10);

BigInt _pow10(int n) => _ten.pow(n);

/// A JSON value as decimal text: "27000.5" stays as it is, 27000.0 becomes "27000", 27000.05 becomes "27000.05".
String decimalText(Object? value) {
  if (value == null) return '0';
  if (value is String) return value;
  if (value is int) return value.toString();
  if (value is double) {
    if (value == value.truncateToDouble() && value.abs() < 1e15) return value.toInt().toString();
    return value.toString();
  }
  return value.toString();
}

/// Text a person typed as an amount, or null when it is not one. Spaces group thousands ("25 000"); so does a comma
/// before three digits ("3,328", "1,250,000") or beside a point ("1,250.50"); a single comma before one or two digits is
/// the decimal point of a French keyboard ("12,5").
String? amountText(String typed) {
  var text = typed.replaceAll(RegExp(r'[\s  ]'), '');
  if (text.contains('.')) {
    text = text.replaceAll(',', '');
  } else if (text.contains(',')) {
    final last = text.lastIndexOf(',');
    if (text.length - last - 1 == 3) {
      text = text.replaceAll(',', '');
    } else if (text.indexOf(',') == last) {
      text = text.replaceFirst(',', '.');
    }
  }
  return RegExp(r'^\d+(\.\d+)?$').hasMatch(text) ? text : null;
}

/// "27000.5" (or 27000.5) as an integer with [scale] decimals, rounded half up.
BigInt scaled(Object? value, int scale) {
  var text = decimalText(value).trim();
  if (text.isEmpty) text = '0';
  final negative = text.startsWith('-');
  final parts = text.replaceFirst('-', '').split('.');
  final whole = parts[0].isEmpty ? '0' : parts[0];
  final frac = parts.length > 1 ? parts[1] : '';
  final digits = (frac + '0' * (scale + 1)).substring(0, scale + 1);
  final kept = digits.substring(0, scale);
  var n = BigInt.parse(whole) * _pow10(scale) + BigInt.parse(kept.isEmpty ? '0' : kept);
  if (int.parse(digits[scale]) >= 5) n += BigInt.one;
  return negative ? -n : n;
}

/// a / b rounded half up, for a >= 0 and b > 0.
BigInt divUp(BigInt a, BigInt b) => (a * BigInt.two + b) ~/ (b * BigInt.two);

/// An integer with [scale] decimals as text: "27000.50".
String text(BigInt n, int scale) {
  if (scale == 0) return n.toString();
  final negative = n < BigInt.zero;
  final s = (negative ? -n : n).toString().padLeft(scale + 1, '0');
  return '${negative ? '-' : ''}${s.substring(0, s.length - scale)}.${s.substring(s.length - scale)}';
}

/// "1234567" -> "1,234,567" (the sign kept).
String _group(String whole) {
  final negative = whole.startsWith('-');
  final digits = negative ? whole.substring(1) : whole;
  final out = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) out.write(',');
    out.write(digits[i]);
  }
  return '${negative ? '-' : ''}$out';
}

String _show(String value) {
  final parts = value.split('.');
  final f = parts.length > 1 ? parts[1] : '';
  return _group(parts[0]) + (f.isNotEmpty && BigInt.parse(f) != BigInt.zero ? '.$f' : '');
}

/// An amount in the currency's smallest unit, grouped: 25920 -> "25,920" (decimals shown only when not zero).
String money(BigInt n, int decimals) => _show(text(n, decimals));

/// A decimal amount as text, grouped: "23000.5" -> "23,000.50", "23000" -> "23,000" (prices per m², VAT).
String grouped(Object? value, int scale) => _show(text(scaled(value, scale), scale));

/// A unit's amount (MobileSales.lineAmount): price x chargeable area, VAT added when the list excludes it, rounded to the
/// currency's decimals.
BigInt lineAmount(Object? pricePerM2, int widthMm, int heightMm,
    {required bool pricesIncludeVat, required Object? minChargeableM2, required Object? vatRate, required int decimals}) {
  final price = scaled(pricePerM2, 2);
  final area = divUp(BigInt.from(widthMm) * BigInt.from(heightMm), BigInt.from(100)); // m², 4 decimals
  final min = scaled(minChargeableM2, 4);
  final chargeable = area > min ? area : min;
  final x = price * chargeable; // 6 decimals
  final rate = scaled(vatRate, 2);
  if (!pricesIncludeVat && rate > BigInt.zero) {
    return divUp(x * (BigInt.from(10000) + rate), _pow10(10 - decimals)); // 10 decimals, then the currency's
  }
  return divUp(x, _pow10(6 - decimals));
}

/// One line for [vatOf]: its tax letter, rate and amount (VAT included).
class VatLine {
  const VatLine(this.taxCode, this.vatRate, this.amount);

  final String taxCode;
  final Object? vatRate;
  final Object? amount;
}

/// VAT in a total per tax letter (Vat.totals): gross x rate / (100 + rate), in cents.
BigInt vatOf(Iterable<VatLine> lines, int decimals) {
  final rates = <String, BigInt>{};
  final gross = <String, BigInt>{};
  for (final l in lines) {
    rates.putIfAbsent(l.taxCode, () => scaled(l.vatRate, 2));
    gross[l.taxCode] = (gross[l.taxCode] ?? BigInt.zero) + scaled(l.amount, decimals) * _pow10(2 - (decimals < 2 ? decimals : 2));
  }
  var vat = BigInt.zero;
  for (final code in rates.keys) {
    final rate = rates[code]!;
    if (rate > BigInt.zero) vat += divUp(gross[code]! * rate, BigInt.from(10000) + rate);
  }
  return vat;
}

/// A price the driver may charge (MobileSales.withinLimit): the list price or more, or less within their limit (%).
bool withinLimit(Object? listPrice, Object? price, Object? discountLimit) {
  final list = scaled(listPrice, 2);
  final p = scaled(price, 2);
  if (p < BigInt.zero) return false;
  if (p >= list) return true;
  final percent = divUp((list - p) * BigInt.from(10000), list); // 2 decimals
  return percent <= scaled(discountLimit, 2);
}

/// The lowest price within the limit, for the hint under the price.
String lowestPrice(Object? listPrice, Object? discountLimit) {
  final list = scaled(listPrice, 2);
  final limit = scaled(discountLimit, 2);
  var low = list - (list * limit) ~/ BigInt.from(10000);
  while (low > BigInt.zero && !withinLimit(listPrice, text(low, 2), discountLimit)) {
    low += BigInt.one;
  }
  return text(low, 2);
}
