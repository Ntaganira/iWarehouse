package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The Delegation of Authority (Board Paper Annex E): which office a request needs, who holds which office, consistency. */
class AuthorityTiersTest {

    private static AuthorityTiers.Cell cell(AuthorityLevel level, boolean allowed, String max) {
        return new AuthorityTiers.Cell(level, allowed, max == null ? null : new BigDecimal(max));
    }

    /** Credit notes as seeded: the COO up to 500,000, the MD up to 2,000,000, the Board above. */
    private static final List<AuthorityTiers.Cell> CREDIT_NOTE = List.of(cell(DIRECTOR, false, null), cell(COO, true, "500000"),
            cell(MD, true, "2000000"), cell(BOARD, true, null));

    @Test
    void aRequestNeedsTheLowestOfficeAllowedWhoseLimitCoversIt() {
        assertThat(AuthorityTiers.required(CREDIT_NOTE, new BigDecimal("1"))).isEqualTo(COO);
        assertThat(AuthorityTiers.required(CREDIT_NOTE, new BigDecimal("500000"))).isEqualTo(COO);   // a limit includes itself
        assertThat(AuthorityTiers.required(CREDIT_NOTE, new BigDecimal("500000.01"))).isEqualTo(MD);
        assertThat(AuthorityTiers.required(CREDIT_NOTE, new BigDecimal("2000000"))).isEqualTo(MD);
        assertThat(AuthorityTiers.required(CREDIT_NOTE, new BigDecimal("2000001"))).isEqualTo(BOARD);
    }

    @Test
    void anOfficeNotAllowedIsSkippedAndAboveEveryLimitTheBoardDecides() {
        // Receivable write-offs: the COO up to 500,000, the Managing Director not at all, then the Board
        List<AuthorityTiers.Cell> writeOff = List.of(cell(DIRECTOR, false, null), cell(COO, true, "500000"), cell(MD, false, null));
        assertThat(AuthorityTiers.required(writeOff, new BigDecimal("400000"))).isEqualTo(COO);
        assertThat(AuthorityTiers.required(writeOff, new BigDecimal("600000"))).isEqualTo(BOARD);
        // Manual journals: a Director up to 5M, the COO without limit
        List<AuthorityTiers.Cell> journal = List.of(cell(DIRECTOR, true, "5000000"), cell(COO, true, null), cell(MD, true, null));
        assertThat(AuthorityTiers.required(journal, new BigDecimal("900000000"))).isEqualTo(COO);
        // No cells at all, or a missing amount: the Board, and the lowest office
        assertThat(AuthorityTiers.required(List.of(), BigDecimal.ONE)).isEqualTo(BOARD);
        assertThat(AuthorityTiers.required(journal, null)).isEqualTo(DIRECTOR);
    }

    @Test
    void aDiscountsLimitsArePercentages() {
        List<AuthorityTiers.Cell> discount = List.of(cell(DIRECTOR, false, null), cell(COO, true, "7"), cell(MD, true, null));
        assertThat(AuthorityTiers.required(discount, new BigDecimal("7.00"))).isEqualTo(COO);
        assertThat(AuthorityTiers.required(discount, new BigDecimal("7.01"))).isEqualTo(MD);
    }

    @Test
    void aUserHoldsTheHighestOfficeOfTheirRightsAndMayDecideWhatALowerOneMay() {
        assertThat(AuthorityTiers.highest(Set.of("PERM_SELL", "PERM_AUTHORITY_DIRECTOR", "PERM_AUTHORITY_MD"))).contains(MD);
        assertThat(AuthorityTiers.highest(Set.of("PERM_APPROVE_SALE"))).isEmpty();
        assertThat(AuthorityTiers.mayDecide(Optional.of(MD), COO)).isTrue();
        assertThat(AuthorityTiers.mayDecide(Optional.of(MD), MD)).isTrue();
        assertThat(AuthorityTiers.mayDecide(Optional.of(COO), MD)).isFalse();
        assertThat(AuthorityTiers.mayDecide(Optional.empty(), DIRECTOR)).isFalse();     // no office, no approval
        assertThat(AuthorityTiers.mayDecide(Optional.of(BOARD), BOARD)).isTrue();
    }

    @Test
    void aHigherOfficesLimitIsNeverBelowALowerOnes() {
        assertThat(AuthorityTiers.inconsistent(CREDIT_NOTE)).isEmpty();
        assertThat(AuthorityTiers.inconsistent(List.of(cell(DIRECTOR, true, "5000000"), cell(COO, true, null), cell(MD, true, null))))
                .isEmpty();                                                                     // no limit above no limit
        assertThat(AuthorityTiers.inconsistent(List.of(cell(DIRECTOR, true, "500000"), cell(COO, true, "200000"))))
                .contains(COO);
        assertThat(AuthorityTiers.inconsistent(List.of(cell(DIRECTOR, true, null), cell(COO, true, "200000"))))
                .contains(COO);                                                                 // a limit above no limit
        assertThat(AuthorityTiers.inconsistent(List.of(cell(DIRECTOR, true, "500000"), cell(COO, false, null),
                cell(MD, true, "600000")))).isEmpty();                                          // an office not allowed is skipped
    }
}
