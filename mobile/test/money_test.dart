// The server's worked examples (MobileSalesTest on the server): the app's integers must give the same amounts.

import 'package:flutter_test/flutter_test.dart';
import 'package:highbytes_pos/money.dart';

BigInt n(int v) => BigInt.from(v);

void main() {
  group('line amounts are the counter\'s amounts', () {
    BigInt retail(String price, int w, int h) =>
        lineAmount(price, w, h, pricesIncludeVat: true, minChargeableM2: '0.2500', vatRate: '18', decimals: 0);
    BigInt contractor(String price, int w, int h) =>
        lineAmount(price, w, h, pricesIncludeVat: false, minChargeableM2: '0.5000', vatRate: '18', decimals: 0);

    test('1200 x 800 = 0.96 m² at 27,000 VAT included', () => expect(retail('27000', 1200, 800), n(25920)));
    test('VAT added: 0.96 x 21,500 x 1.18 = 24,355.2', () => expect(contractor('21500', 1200, 800), n(24355)));
    test('a small piece is charged the list\'s minimum area', () => expect(contractor('21500', 300, 300), n(12685)));
    test('half a franc rounds up', () => expect(retail('10001', 500, 1000), n(5001)));
    test('JSON numbers as doubles give the same', () => expect(lineAmount(27000.0, 1200, 800,
        pricesIncludeVat: true, minChargeableM2: 0.25, vatRate: 18.0, decimals: 0), n(25920)));
  });

  test('a lower price stays within the driver\'s limit', () {
    expect(withinLimit('27000', '27000', '0'), isTrue);
    expect(withinLimit('27000', '28000', '0'), isTrue); // more is always allowed
    expect(withinLimit('27000', '26999', '0'), isTrue); // 0.0037% rounds to 0.00%, as at the counter
    expect(withinLimit('27000', '26990', '0'), isFalse); // 0.04%
    expect(withinLimit('27000', '24300', '10'), isTrue); // exactly 10%
    expect(withinLimit('27000', '24290', '10'), isFalse); // 10.04%
    expect(withinLimit('27000', '-1', '100'), isFalse);
    expect(lowestPrice('27000', '10'), '24300.00');
    expect(lowestPrice('27000', '0'), '27000.00'); // as the web app: it counts up from the limit, never below
  });

  test('VAT per tax letter, on the total', () {
    // 25,920 VAT included at 18%: 25,920 x 18 / 118 = 3,953.898... -> 3,953.90
    expect(vatOf([const VatLine('B', '18', '25920')], 0), n(395390));
    // Two lines of the same letter are taxed on their sum, not line by line
    expect(vatOf([const VatLine('B', '18', '25920'), const VatLine('B', '18', '12685')], 0), n(588890));
    expect(vatOf([const VatLine('A', '0', '1000')], 0), BigInt.zero);
  });

  test('decimal text, rounding and grouping', () {
    expect(scaled('27000.5', 2), n(2700050));
    expect(scaled('0.125', 2), n(13));
    expect(scaled('-1.5', 0), n(-2));
    expect(scaled('', 2), BigInt.zero);
    expect(text(n(2700050), 2), '27000.50');
    expect(text(n(-5), 2), '-0.05');
    expect(money(n(1234567), 0), '1,234,567');
    expect(money(n(-1234), 0), '-1,234');
    expect(grouped('23000.5', 2), '23,000.50');
    expect(grouped('23000', 2), '23,000');
    expect(decimalText(27000.0), '27000');
    expect(decimalText(27000.05), '27000.05');
    expect(decimalText(25920), '25920');
  });

  test('amounts typed by a person', () {
    expect(amountText('25000'), '25000');
    expect(amountText(' 25 000 '), '25000');
    expect(amountText('12,5'), '12.5');
    expect(amountText('12.5'), '12.5');
    expect(amountText('1,250.5'), '1250.5');
    expect(amountText('3,328'), '3328');
    expect(amountText('1,250,000'), '1250000');
    expect(amountText('27000,50'), '27000.50');
    expect(amountText('1,2,3'), isNull);
    expect(amountText('abc'), isNull);
    expect(amountText('-5'), isNull);
    expect(amountText(''), isNull);
  });
}
