package com.ntaganira.heritier.iWarehouse.ebm;

import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : EbmCodesTest.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : RRA's codes and formats as the receipts print them, and what a VSDC answer means for the queue.
 * </pre>
 */
class EbmCodesTest {

    @Test
    void itemCodesFollowRrasPatternPaddingTwoLetterUnits() {
        assertThat(EbmCodes.itemCode("RW", "1", "NT", "U", 6)).isEqualTo("RW1NTXU0000006");      // RRA's own sample
        assertThat(EbmCodes.itemCode("RW", "2", "NT", "M2", 1)).isEqualTo("RW2NTXM2X0000001");
        assertThat(EbmCodes.itemCode("CN", "2", "NT", "M2", 1234567)).hasSizeLessThanOrEqualTo(20);
        assertThatThrownBy(() -> EbmCodes.itemCode("RW", "2", "NT", "M2", 10_000_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receiptNumbersSignaturesAndTheQrCodeReadAsOnRrasReceipts() {
        assertThat(EbmCodes.receiptLabel(27, 32, false, "S")).isEqualTo("27/32 NS");
        assertThat(EbmCodes.receiptLabel(27, 32, true, "S")).isEqualTo("27/32 CS");
        assertThat(EbmCodes.receiptLabel(4, 4, true, "R")).isEqualTo("4/4 CR");
        assertThat(EbmCodes.groups("2ZQSU6NW7NYFMLWNZFHR6FF5AQ")).isEqualTo("2ZQS-U6NW-7NYF-MLWN-ZFHR-6FF5-AQ");
        assertThat(EbmCodes.groups("PE5K66C7RE3LBZCB")).isEqualTo("PE5K-66C7-RE3L-BZCB");
        assertThat(EbmCodes.qrData("https://myrra.rra.gov.rw/x?Data=", "100200300", "00", "PE5K66C7RE3LBZCB"))
                .isEqualTo("https://myrra.rra.gov.rw/x?Data=10020030000PE5K66C7RE3LBZCB");
        assertThat(EbmCodes.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");   // RFC 4648
        assertThat(EbmCodes.base32(new byte[10])).hasSize(16);
        assertThat(EbmCodes.base32(new byte[16])).hasSize(26);
    }

    @Test
    void paymentTypesFollowHowTheSaleWasPaid() {
        assertThat(EbmCodes.paymentType(Set.of(PaymentMethod.CASH), false)).isEqualTo("01");
        assertThat(EbmCodes.paymentType(Set.of(PaymentMethod.MOBILE_MONEY), false)).isEqualTo("06");
        assertThat(EbmCodes.paymentType(Set.of(PaymentMethod.CARD), false)).isEqualTo("05");
        assertThat(EbmCodes.paymentType(Set.of(PaymentMethod.CREDIT), false)).isEqualTo("02");
        assertThat(EbmCodes.paymentType(EnumSet.of(PaymentMethod.CASH, PaymentMethod.CREDIT), false)).isEqualTo("03");
        assertThat(EbmCodes.paymentType(Set.of(PaymentMethod.CASH), true)).isEqualTo("03");         // a deposit
        assertThat(EbmCodes.paymentType(EnumSet.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY), false)).isEqualTo("07");
        assertThat(EbmCodes.paymentType(Set.of(), false)).isEqualTo("02");
        assertThat(EbmCodes.quantityUnit(ChargeUnit.METRE)).isEqualTo("M");
        assertThat(EbmCodes.quantityUnit(ChargeUnit.HOLE)).isEqualTo("U");
    }

    @Test
    void datesAndTextFitTheVsdcsFields() {
        assertThat(EbmCodes.dateTime(LocalDateTime.of(2026, 10, 10, 9, 5, 3))).isEqualTo("20261010090503");
        assertThat(EbmCodes.parseDateTime("20211027162114")).isEqualTo(LocalDateTime.of(2021, 10, 27, 16, 21, 14));
        assertThat(EbmCodes.parseDateTime("2021-10-27")).isNull();
        assertThat(EbmCodes.cut("  Glass Rwanda Limited Company ", 20)).isEqualTo("Glass Rwanda Limited");
        assertThat(EbmCodes.cut("   ", 20)).isNull();
    }

    @Test
    void anAnswerSignsRetriesOrWaitsForAPerson() {
        assertThat(VsdcResults.of("000")).isEqualTo(VsdcResults.Outcome.SIGNED);
        assertThat(VsdcResults.of("894")).isEqualTo(VsdcResults.Outcome.RETRY);      // server communication error
        assertThat(VsdcResults.of("901")).isEqualTo(VsdcResults.Outcome.RETRY);      // device not valid yet
        assertThat(VsdcResults.of("924")).isEqualTo(VsdcResults.Outcome.DUPLICATE);
        assertThat(VsdcResults.of("994")).isEqualTo(VsdcResults.Outcome.DUPLICATE);
        assertThat(VsdcResults.of("910")).isEqualTo(VsdcResults.Outcome.REJECTED);
        assertThat(VsdcResults.of("881")).isEqualTo(VsdcResults.Outcome.REJECTED);   // purchase code missing
        assertThat(VsdcResults.of(null)).isEqualTo(VsdcResults.Outcome.REJECTED);

        assertThat(VsdcResults.retryDelay(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(VsdcResults.retryDelay(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(VsdcResults.retryDelay(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(VsdcResults.retryDelay(7)).isEqualTo(Duration.ofMinutes(30));       // 32 min capped
        assertThat(VsdcResults.retryDelay(500)).isEqualTo(Duration.ofMinutes(30));
    }
}
