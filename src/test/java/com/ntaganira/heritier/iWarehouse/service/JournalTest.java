package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Building journals (ACC-04) and the trial balance (ACC-11), without the database. */
class JournalTest {

    private static Journal journal() {
        return Journal.of(JournalSource.CUTTING_JOB, UUID.randomUUID(), "CUT-WH-2026-000001", LocalDate.of(2026, 10, 9), "Cutting job");
    }

    @Test
    void positiveAmountsDebitNegativeOnesCreditAndZeroAddsNothing() {
        Journal j = journal()
                .debit(AccountKey.SPOILAGE, new BigDecimal("100"))
                .credit(AccountKey.INVENTORY, new BigDecimal("100"))
                .debit(AccountKey.COGS, BigDecimal.ZERO)
                .debit(AccountKey.COGS, null);

        assertThat(j.lines()).extracting(Journal.Line::account).containsExactly(AccountKey.SPOILAGE, AccountKey.INVENTORY);
        assertThat(j.lines().get(0).debit()).isEqualByComparingTo("100.00");
        assertThat(j.lines().get(1).credit()).isEqualByComparingTo("100.00");
        assertThat(j.lines().get(1).debit()).isEqualByComparingTo("0");
        assertThat(j.isBalanced()).isTrue();
        assertThat(j.debits()).isEqualByComparingTo("100.00");
    }

    @Test
    void linesOfTheSameAccountAddUpAndThoseCancellingOutDisappear() {
        Journal j = journal()
                .debit(AccountKey.STOCK_ADJUSTMENT, new BigDecimal("15000"))
                .debit(AccountKey.STOCK_ADJUSTMENT, new BigDecimal("-12000"))
                .debit(AccountKey.COGS, new BigDecimal("50"))
                .credit(AccountKey.COGS, new BigDecimal("50"));

        assertThat(j.lines()).singleElement().satisfies(l -> {
            assertThat(l.account()).isEqualTo(AccountKey.STOCK_ADJUSTMENT);
            assertThat(l.debit()).isEqualByComparingTo("3000.00");
        });
        assertThat(journal().debit(AccountKey.COGS, BigDecimal.ONE).credit(AccountKey.COGS, BigDecimal.ONE).isEmpty()).isTrue();
    }

    @Test
    void foreignLinesStayOnTheirOwn() {
        Journal j = journal()
                .add(AccountKey.PAYABLE, new BigDecimal("-100"), null, null, "A", new Journal.Fx("USD", new BigDecimal("0.07"), new BigDecimal("1449")))
                .add(AccountKey.PAYABLE, new BigDecimal("-200"), null, null, "A", new Journal.Fx("EUR", new BigDecimal("0.13"), new BigDecimal("1550")));

        assertThat(j.lines()).hasSize(2).extracting(l -> l.fx().currencyCode()).containsExactly("USD", "EUR");
    }

    @Test
    void stockLinesAreTheChangeOfEachGlassAndTheRestBalancesTheJournal() {
        UUID clear = UUID.randomUUID();
        UUID mirror = UUID.randomUUID();
        Journal j = journal()
                .credit(AccountKey.GRNI, new BigDecimal("1000.00"))
                .stockChange(Map.of(clear, new BigDecimal("500.00"), mirror, new BigDecimal("80.00")),
                        Map.of(clear, new BigDecimal("1499.97"), mirror, new BigDecimal("80.00")))
                .balanceOn(AccountKey.STOCK_REVALUATION);

        assertThat(j.lines()).hasSize(3);
        Journal.Line stock = j.lines().stream().filter(l -> l.account() == AccountKey.INVENTORY).findFirst().orElseThrow();
        assertThat(stock.productId()).isEqualTo(clear);                              // mirror did not change: no line
        assertThat(stock.debit()).isEqualByComparingTo("999.97");
        Journal.Line rounding = j.lines().stream().filter(l -> l.account() == AccountKey.STOCK_REVALUATION).findFirst().orElseThrow();
        assertThat(rounding.debit()).isEqualByComparingTo("0.03");
        assertThat(j.isBalanced()).isTrue();
    }

    @Test
    void amountsRoundedOnceAddUpToTheirTotal() {
        assertThat(Journal.roundTo(new BigDecimal("1749123"), List.of(new BigDecimal("1449123.456"), new BigDecimal("300000"))))
                .containsExactly(new BigDecimal("1449123.00"), new BigDecimal("300000.00"));
        assertThat(Journal.roundTo(new BigDecimal("10.00"), List.of(new BigDecimal("3.333"), new BigDecimal("3.333"), new BigDecimal("3.334"))))
                .extracting(BigDecimal::toPlainString).containsExactly("3.33", "3.33", "3.34");
        assertThat(Journal.roundTo(new BigDecimal("-50"), List.of(new BigDecimal("100.005"), new BigDecimal("-150.004"))))
                .extracting(BigDecimal::toPlainString).containsExactly("100.01", "-150.01");   // a credit note in the posting
        assertThat(Journal.roundTo(BigDecimal.ZERO, List.of())).isEmpty();
    }

    @Test
    void theTrialBalanceShowsEachBalanceOnItsSideAndBalancesWhenTheBooksDo() {
        Account inventory = account("1200", AccountType.ASSET);
        Account grni = account("2020", AccountType.LIABILITY);
        Account spoilage = account("5020", AccountType.EXPENSE);
        Account unused = account("1030", AccountType.ASSET);
        List<Object[]> sums = List.of(
                new Object[]{grni.getId(), new BigDecimal("0.00"), new BigDecimal("989064.72")},
                new Object[]{inventory.getId(), new BigDecimal("1441966.40"), new BigDecimal("500000.00")},
                new Object[]{spoilage.getId(), new BigDecimal("47098.32"), new BigDecimal("0.00")});

        TrialBalance.Result tb = TrialBalance.of(List.of(inventory, grni, spoilage, unused), sums);

        assertThat(tb.rows()).extracting(r -> r.account().getCode()).containsExactly("1200", "2020", "5020");
        assertThat(tb.rows().get(0).getDebit()).isEqualByComparingTo("941966.40");
        assertThat(tb.rows().get(1).getCredit()).isEqualByComparingTo("989064.72");
        assertThat(tb.rows().get(1).getBalance()).isEqualByComparingTo("989064.72");     // a liability's credit balance
        assertThat(tb.debit()).isEqualByComparingTo("989064.72");
        assertThat(tb.isBalanced()).isTrue();

        TrialBalance.Result off = TrialBalance.of(List.of(inventory), List.<Object[]>of(new Object[]{inventory.getId(), BigDecimal.TEN, BigDecimal.ZERO}));
        assertThat(off.isBalanced()).isFalse();
        assertThat(off.getDifference()).isEqualByComparingTo("10");
    }

    private static Account account(String code, AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(code);
        a.setType(type);
        return a;
    }
}
