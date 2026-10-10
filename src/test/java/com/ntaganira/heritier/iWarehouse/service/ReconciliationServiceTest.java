package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.ReconciliationStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.BankReconciliationLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.BankReconciliationRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Bank reconciliation (ACC-12): the lines ticked take the previous statement's balance to the new one; what is not ticked is
 * outstanding; a line is cleared once; the next statement starts from this one; the latest can be cancelled.
 */
class ReconciliationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final List<JournalLine> ledger = new ArrayList<>();
    private final List<BankReconciliation> saved = new ArrayList<>();
    private final List<BankReconciliationLine> savedLines = new ArrayList<>();
    private Account bank;
    private Account sales;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = new AppUserPrincipal(7L, "accountant", "accountant", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        bank = account("1030", "Bank", AccountKey.BANK);
        sales = account("4010", "Sales Revenue", AccountKey.SALES);
        // September: a deposit of 500,000 on 05/09 and a payment of 120,000 on 20/09; October: a deposit of 300,000 on 10/10
        ledger.add(line("2026-09-05", "500000", "0"));
        ledger.add(line("2026-09-20", "0", "120000"));
        ledger.add(line("2026-10-10", "300000", "0"));

        BankReconciliationRepository repo = mock(BankReconciliationRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            BankReconciliation r = a.getArgument(0);
            r.setId(UUID.randomUUID());
            saved.add(r);
            return r;
        });
        when(repo.findFirstByAccount_IdAndStatusOrderByStatementDateDesc(any(), eq(ReconciliationStatus.RECONCILED))).thenAnswer(a -> saved.stream()
                .filter(r -> r.getStatus() == ReconciliationStatus.RECONCILED).max(Comparator.comparing(BankReconciliation::getStatementDate)));
        when(repo.findDetailedById(any())).thenAnswer(a -> saved.stream().filter(r -> r.getId().equals(a.getArgument(0))).findFirst());
        BankReconciliationLineRepository lineRepo = mock(BankReconciliationLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            savedLines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.clearedLineIds(any())).thenAnswer(a -> savedLines.stream()
                .filter(l -> saved.stream().anyMatch(r -> r.getId().equals(l.getReconciliationId()) && r.isReconciled()))
                .map(l -> l.getJournalLine().getId()).collect(Collectors.toSet()));
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.findById(bank.getId())).thenReturn(Optional.of(bank));
        when(accountRepo.findById(sales.getId())).thenReturn(Optional.of(sales));
        when(accountRepo.lockById(any())).thenAnswer(a -> accountRepo.findById(a.getArgument(0)));
        JournalLineRepository journalLineRepo = mock(JournalLineRepository.class);
        when(journalLineRepo.findAccountLinesUpTo(eq(bank.getId()), any())).thenAnswer(a -> ledger.stream()
                .filter(l -> !l.getEntry().getEntryDate().isAfter(a.getArgument(1))).toList());
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.BANK_RECONCILIATION)).thenReturn("REC-WH-2026-000001", "REC-WH-2026-000002", "REC-WH-2026-000003");
        service = new ReconciliationService(repo, lineRepo, accountRepo, journalLineRepo, numbers, CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theLinesTickedMustGiveTheStatementBalanceAndTheRestIsOutstanding() {
        LocalDate sept30 = LocalDate.of(2026, 9, 30);
        ReconciliationService.Draft draft = service.draft(bank.getId(), sept30, new BigDecimal("500000"));
        assertThat(draft.open()).hasSize(2);                                            // October's deposit is after the date
        assertThat(draft.bookBalance()).isEqualByComparingTo("380000");
        assertThat(draft.getPreviousBalance()).isEqualByComparingTo("0");

        // The bank shows the deposit, not yet the payment
        assertThatThrownBy(() -> service.reconcile(bank.getId(), sept30, new BigDecimal("500000"), List.of(ledger.get(0).getId(), ledger.get(1).getId()), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("reconciliation.difference");
                    assertThat((BigDecimal) e.getArgs()[0]).isEqualByComparingTo("120000");
                });
        BankReconciliation rec = service.reconcile(bank.getId(), sept30, new BigDecimal("500000"), List.of(ledger.get(0).getId()), "September statement");

        assertThat(rec.getNumber()).isEqualTo("REC-WH-2026-000001");
        assertThat(rec.getClearedAmount()).isEqualByComparingTo("500000");
        assertThat(rec.getBookBalance()).isEqualByComparingTo("380000");
        assertThat(rec.getOutstandingDeposits()).isEqualByComparingTo("0");
        assertThat(rec.getOutstandingPayments()).isEqualByComparingTo("120000");      // 380,000 - 0 + 120,000 = 500,000
        assertThat(rec.getReconciledBy()).isEqualTo("accountant");
        assertThat(savedLines).hasSize(1);

        // October starts from 500,000: the payment (outstanding) and October's deposit are open
        ReconciliationService.Draft october = service.draft(bank.getId(), LocalDate.of(2026, 10, 31), new BigDecimal("680000"));
        assertThat(october.getPreviousBalance()).isEqualByComparingTo("500000");
        assertThat(october.open()).extracting(JournalLine::getId).containsExactly(ledger.get(1).getId(), ledger.get(2).getId());
        assertThat(october.getToClear()).isEqualByComparingTo("180000");
        assertThatThrownBy(() -> service.reconcile(bank.getId(), LocalDate.of(2026, 10, 31), new BigDecimal("680000"), List.of(ledger.get(0).getId()), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("reconciliation.lineNotOpen");   // cleared once
        BankReconciliation second = service.reconcile(bank.getId(), LocalDate.of(2026, 10, 31), new BigDecimal("680000"),
                List.of(ledger.get(1).getId(), ledger.get(2).getId()), null);
        assertThat(second.getOutstandingPayments()).isEqualByComparingTo("0");
        assertThat(second.getBookBalance()).isEqualByComparingTo("680000");
    }

    @Test
    void theStatementDateFollowsThePreviousOneAndOnlyTheLatestIsCancelled() {
        assertThatThrownBy(() -> service.draft(bank.getId(), LocalDate.of(2026, 11, 4), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("reconciliation.date.invalid");
        assertThatThrownBy(() -> service.draft(sales.getId(), LocalDate.of(2026, 9, 30), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("reconciliation.account.invalid");
        assertThatThrownBy(() -> service.draft(bank.getId(), LocalDate.of(2026, 9, 30), new BigDecimal("1.005")))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("reconciliation.balance.invalid");

        BankReconciliation september = service.reconcile(bank.getId(), LocalDate.of(2026, 9, 30), new BigDecimal("380000"),
                List.of(ledger.get(0).getId(), ledger.get(1).getId()), null);
        assertThatThrownBy(() -> service.draft(bank.getId(), LocalDate.of(2026, 9, 30), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("reconciliation.date.early");
        BankReconciliation october = service.reconcile(bank.getId(), LocalDate.of(2026, 10, 31), new BigDecimal("680000"),
                List.of(ledger.get(2).getId()), null);

        assertThat(service.canCancel(september)).isFalse();
        assertThatThrownBy(() -> service.cancel(september.getId(), "Wrong"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("reconciliation.notLatest");
        service.cancel(october.getId(), "  Wrong statement  ");
        assertThat(october.getStatus()).isEqualTo(ReconciliationStatus.CANCELLED);
        assertThat(october.getCancelReason()).isEqualTo("Wrong statement");
        // October's deposit is open again, and the next statement starts from September's
        ReconciliationService.Draft again = service.draft(bank.getId(), LocalDate.of(2026, 10, 31), new BigDecimal("680000"));
        assertThat(again.open()).extracting(JournalLine::getId).containsExactly(ledger.get(2).getId());
        assertThat(again.getPreviousBalance()).isEqualByComparingTo("380000");
    }

    @Test
    void outstandingIsWhatIsNotTicked() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Reconciliations.Result r = Reconciliations.of(new BigDecimal("1000"), new BigDecimal("1500"), List.of(
                new Reconciliations.Line(a, new BigDecimal("700"), BigDecimal.ZERO),
                new Reconciliations.Line(b, BigDecimal.ZERO, new BigDecimal("200")),
                new Reconciliations.Line(c, new BigDecimal("50"), BigDecimal.ZERO)), Set.of(a, b));
        assertThat(r.cleared()).isEqualByComparingTo("500");
        assertThat(r.isReconciled()).isTrue();
        assertThat(r.outstandingDeposits()).isEqualByComparingTo("50");
        assertThat(r.outstandingPayments()).isEqualByComparingTo("0");
        assertThat(Reconciliations.of(BigDecimal.ZERO, new BigDecimal("100"), List.of(), Set.of()).difference()).isEqualByComparingTo("100");
    }

    private static Account account(String code, String name, AccountKey key) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(name);
        a.setType(AccountType.ASSET);
        a.setSystemKey(key);
        return a;
    }

    private static JournalLine line(String date, String debit, String credit) {
        JournalEntry e = new JournalEntry();
        e.setId(UUID.randomUUID());
        e.setEntryDate(LocalDate.parse(date));
        JournalLine l = new JournalLine();
        l.setId(UUID.randomUUID());
        l.setEntry(e);
        l.setDebit(new BigDecimal(debit));
        l.setCredit(new BigDecimal(credit));
        return l;
    }
}
