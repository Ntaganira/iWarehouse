package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a return credits (POS-09): a line's share of the pieces back, which adds up to the line however many credit notes
 * it takes, and the split between the invoice's balance due and the refund.
 */
class CreditNotesTest {

    @Test
    void piecesBackOnSeveralCreditNotesAddUpToTheLine() {
        BigDecimal line = new BigDecimal("10001");                                     // 3 pieces
        BigDecimal first = CreditNotes.share(line, 3, 0, 1);
        BigDecimal second = CreditNotes.share(line, 3, 1, 1);
        BigDecimal third = CreditNotes.share(line, 3, 2, 1);

        assertThat(first).isEqualByComparingTo("3334");                                // 3,333.67 rounded
        assertThat(second).isEqualByComparingTo("3333");                               // 6,667.33 -> 6,667, less 3,334
        assertThat(third).isEqualByComparingTo("3334");
        assertThat(first.add(second).add(third)).isEqualByComparingTo(line);
        assertThat(CreditNotes.share(line, 3, 1, 2)).isEqualByComparingTo(second.add(third));
        assertThat(CreditNotes.share(new BigDecimal("195008"), 1, 0, 1)).isEqualByComparingTo("195008");
    }

    @Test
    void moreThanTheLineNeverComesBack() {
        assertThatThrownBy(() -> CreditNotes.share(BigDecimal.TEN, 2, 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditNotes.share(BigDecimal.TEN, 2, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCreditComesOffTheBalanceDueFirst() {
        CreditNotes.Refund owed = CreditNotes.refund(new BigDecimal("195008"), new BigDecimal("13500"));
        assertThat(owed.balanceReduced()).isEqualByComparingTo("13500");
        assertThat(owed.refunded()).isEqualByComparingTo("181508");

        CreditNotes.Refund small = CreditNotes.refund(new BigDecimal("6750"), new BigDecimal("13500"));
        assertThat(small.balanceReduced()).isEqualByComparingTo("6750");
        assertThat(small.refunded()).isEqualByComparingTo("0");

        CreditNotes.Refund paid = CreditNotes.refund(new BigDecimal("6750"), BigDecimal.ZERO);
        assertThat(paid.balanceReduced()).isEqualByComparingTo("0");
        assertThat(paid.refunded()).isEqualByComparingTo("6750");
    }
}
