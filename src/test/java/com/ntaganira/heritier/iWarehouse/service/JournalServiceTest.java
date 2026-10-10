package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Journals given by account (a manual journal, ACC-05) and reversals: a journal by account must balance with a debit or a
 * credit per line; a reversal posts every line the other way, keeping what each line is about, and names what it reverses.
 */
class JournalServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final List<JournalEntry> entries = new ArrayList<>();
    private final List<JournalLine> lines = new ArrayList<>();
    private JournalService service;
    private int number;
    private LocalDate closedThrough;

    @BeforeEach
    void setUp() {
        JournalEntryRepository entryRepo = mock(JournalEntryRepository.class);
        when(entryRepo.save(any())).thenAnswer(a -> {
            JournalEntry e = a.getArgument(0);
            e.setId(UUID.randomUUID());
            entries.add(e);
            return e;
        });
        when(entryRepo.findByReversesIdOrderByNumber(any())).thenAnswer(a -> entries.stream()
                .filter(e -> a.getArgument(0).equals(e.getReversesId())).toList());
        JournalLineRepository lineRepo = mock(JournalLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            lines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.findByEntry_IdOrderByLineNo(any())).thenAnswer(a -> lines.stream()
                .filter(l -> l.getEntry().getId().equals(a.getArgument(0))).toList());
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.JOURNAL)).thenAnswer(a -> String.format("JV-WH-2026-%06d", ++number));
        AccountingPeriodRepository periodRepo = mock(AccountingPeriodRepository.class);
        when(periodRepo.closedThrough()).thenAnswer(a -> closedThrough);
        service = new JournalService(entryRepo, lineRepo, mock(AccountRepository.class), mock(ProductRepository.class),
                mock(SupplierRepository.class), mock(CustomerRepository.class), mock(StockSummaryService.class), numbers,
                new PeriodLock(periodRepo), CLOCK);
    }

    @Test
    void aJournalByAccountBalancesWithADebitOrACreditPerLine() {
        Account charges = account("5190");
        Account bank = account("1030");
        JournalEntry entry = service.postLines(JournalSource.MANUAL_JOURNAL, UUID.randomUUID(), "MJ-WH-2026-000001", LocalDate.of(2026, 9, 30),
                "Bank charges of September", List.of(line(charges, "12500", "0", "Statement"), line(bank, "0", "12500", null)));

        assertThat(entry.getNumber()).isEqualTo("JV-WH-2026-000001");
        assertThat(entry.getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(entry.getSourceType()).isEqualTo(JournalSource.MANUAL_JOURNAL);
        assertThat(entry.getTotal()).isEqualByComparingTo("12500");
        assertThat(entry.getReversesId()).isNull();
        assertThat(lines).extracting(l -> l.getLineNo() + " " + l.getAccount().getCode() + " " + l.getDebit() + "/" + l.getCredit())
                .containsExactly("1 5190 12500.00/0.00", "2 1030 0.00/12500.00");

        assertThatThrownBy(() -> service.postLines(JournalSource.MANUAL_JOURNAL, null, "MJ-2", LocalDate.of(2026, 9, 30), "x",
                List.of(line(charges, "100", "0", null), line(bank, "0", "99", null)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.postLines(JournalSource.MANUAL_JOURNAL, null, "MJ-3", LocalDate.of(2026, 9, 30), "x",
                List.of(line(charges, "100", "100", null), line(bank, "0", "0", null)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.postLines(JournalSource.MANUAL_JOURNAL, null, "MJ-4", LocalDate.of(2026, 9, 30), "x",
                List.of(line(charges, "0", "0", null)))).isInstanceOf(IllegalStateException.class);
        assertThat(entries).hasSize(1);
    }

    @Test
    void aReversalPostsEveryLineTheOtherWayOnceAndNamesTheJournal() {
        Supplier shandong = new Supplier();
        shandong.setId(UUID.randomUUID());
        JournalEntry original = service.postLines(JournalSource.MANUAL_JOURNAL, UUID.randomUUID(), "MJ-WH-2026-000001", LocalDate.of(2026, 9, 30),
                "Accrued freight", List.of(line(account("5190"), "300000", "0", "Freight"), line(account("2030"), "0", "300000", null)));
        lines.get(1).setSupplier(shandong);                                              // what a line is about stays on its reversal
        lines.get(1).setCurrencyCode("USD");
        lines.get(1).setFxAmount(new BigDecimal("205.48"));
        lines.get(1).setRate(new BigDecimal("1460"));

        JournalEntry reversal = service.reverse(original, LocalDate.of(2026, 10, 1), "Reversal of MJ-WH-2026-000001: paid in October");

        assertThat(reversal.getNumber()).isEqualTo("JV-WH-2026-000002");
        assertThat(reversal.getEntryDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(reversal.getReversesId()).isEqualTo(original.getId());
        assertThat(reversal.getSourceType()).isEqualTo(JournalSource.MANUAL_JOURNAL);
        assertThat(reversal.getSourceId()).isEqualTo(original.getSourceId());
        assertThat(reversal.getTotal()).isEqualByComparingTo("300000");
        List<JournalLine> back = lines.subList(2, 4);
        assertThat(back).extracting(l -> l.getAccount().getCode() + " " + l.getDebit() + "/" + l.getCredit())
                .containsExactly("5190 0.00/300000.00", "2030 300000.00/0.00");
        assertThat(back.get(0).getMemo()).isEqualTo("Freight");
        assertThat(back.get(1).getSupplier()).isSameAs(shandong);
        assertThat(back.get(1).getFxAmount()).isEqualByComparingTo("205.48");
        assertThat(back.get(1).getRate()).isEqualByComparingTo("1460");

        assertThatThrownBy(() -> service.reverse(original, LocalDate.of(2026, 10, 2), "again")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nothingIsPostedOnAClosedMonth() {   // ACC-10
        closedThrough = LocalDate.of(2026, 9, 30);
        List<JournalService.AccountLine> lines = List.of(line(account("5190"), "100", "0", null), line(account("1030"), "0", "100", null));
        assertThatThrownBy(() -> service.postLines(JournalSource.MANUAL_JOURNAL, null, "MJ-1", LocalDate.of(2026, 9, 30), "x", lines))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("period.closed");
                    assertThat(e.getArgs()).containsExactly("30/09/2026", "30/09/2026");
                });
        assertThat(entries).isEmpty();
        assertThat(service.postLines(JournalSource.MANUAL_JOURNAL, null, "MJ-2", LocalDate.of(2026, 10, 1), "x", lines)).isNotNull();

        JournalEntry october = entries.get(0);
        closedThrough = LocalDate.of(2026, 10, 31);                                     // a reversal is refused there too
        assertThatThrownBy(() -> service.reverse(october, LocalDate.of(2026, 10, 15), "x")).isInstanceOf(BusinessException.class);
    }

    private static Account account(String code) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        return a;
    }

    private static JournalService.AccountLine line(Account account, String debit, String credit, String memo) {
        return new JournalService.AccountLine(account, new BigDecimal(debit), new BigDecimal(credit), memo);
    }
}
