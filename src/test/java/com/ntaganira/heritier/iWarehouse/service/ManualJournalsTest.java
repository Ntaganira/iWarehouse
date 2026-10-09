package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules of manual journals (ACC-05): control accounts, one side per line, balanced lines. */
class ManualJournalsTest {

    @Test
    void controlAccountsArePostedByTheirDocumentsOnly() {
        for (AccountKey key : List.of(AccountKey.INVENTORY, AccountKey.RECEIVABLE, AccountKey.PAYABLE, AccountKey.GRNI, AccountKey.CASH,
                AccountKey.DRIVER_FLOAT, AccountKey.CLAIMS)) {
            assertThat(ManualJournals.isControlled(key)).as("%s", key).isTrue();
        }
        // The vault, the bank, accruals, VAT, expenses and accounts the accountant adds are open to manual journals
        for (AccountKey key : List.of(AccountKey.CASH_VAULT, AccountKey.BANK, AccountKey.MOBILE_MONEY, AccountKey.IMPORT_ACCRUAL,
                AccountKey.VAT_INPUT, AccountKey.VAT_OUTPUT, AccountKey.FX_GAIN_LOSS, AccountKey.RETAINED_EARNINGS)) {
            assertThat(ManualJournals.isControlled(key)).as("%s", key).isFalse();
        }
        assertThat(ManualJournals.isControlled(null)).isFalse();
    }

    @Test
    void aLineIsADebitOrACreditAndTheLinesBalance() {
        assertThat(amounts("100", null).isOneSided()).isTrue();
        assertThat(amounts(null, "0.01").isOneSided()).isTrue();
        assertThat(amounts("100", "100").isOneSided()).isFalse();
        assertThat(amounts(null, null).isOneSided()).isFalse();
        assertThat(amounts("0", "0").isOneSided()).isFalse();

        ManualJournals.Totals even = ManualJournals.totals(List.of(amounts("0.10", null), amounts("0.20", null), amounts(null, "0.30")));
        assertThat(even.isBalanced()).isTrue();
        ManualJournals.Totals short_ = ManualJournals.totals(List.of(amounts("12500", null), amounts(null, "12499.99")));
        assertThat(short_.isBalanced()).isFalse();
        assertThat(short_.getDifference()).isEqualByComparingTo("0.01");
    }

    private static ManualJournals.Amounts amounts(String debit, String credit) {
        return new ManualJournals.Amounts(debit == null ? null : new BigDecimal(debit), credit == null ? null : new BigDecimal(credit));
    }
}
