package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ManualJournalDto;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.ManualJournal;
import com.ntaganira.heritier.iWarehouse.entity.ManualJournalLine;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalEntryRepository;
import com.ntaganira.heritier.iWarehouse.repository.ManualJournalLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.ManualJournalRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Manual journals (ACC-05): asked for by account, balanced, never on a control account; approved (posted) or rejected by
 * another person, withdrawn by the requester; reversed, never deleted, on a day from its own date to today.
 */
class ManualJournalServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDate SEPT_30 = LocalDate.of(2026, 9, 30);

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<UUID, ManualJournal> saved = new HashMap<>();
    private final List<ManualJournalLine> savedLines = new ArrayList<>();
    private Account bank;
    private Account charges;
    private Account receivable;
    private Account retired;
    private JournalService journals;
    private ManualJournalService service;

    @BeforeEach
    void setUp() {
        bank = account("1030", "Bank", AccountType.ASSET, AccountKey.BANK, true);
        charges = account("5190", "Bank charges", AccountType.EXPENSE, null, true);
        receivable = account("1100", "Accounts Receivable", AccountType.ASSET, AccountKey.RECEIVABLE, true);
        retired = account("5180", "Old expenses", AccountType.EXPENSE, null, false);
        signIn(7L, "accountant");

        ManualJournalRepository repo = mock(ManualJournalRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            ManualJournal m = a.getArgument(0);
            m.setId(UUID.randomUUID());
            saved.put(m.getId(), m);
            return m;
        });
        when(repo.lockById(any())).thenAnswer(a -> Optional.ofNullable(saved.get(a.<UUID>getArgument(0))));
        when(repo.findById(any())).thenAnswer(a -> Optional.ofNullable(saved.get(a.<UUID>getArgument(0))));
        ManualJournalLineRepository lineRepo = mock(ManualJournalLineRepository.class);
        when(lineRepo.save(any())).thenAnswer(a -> {
            savedLines.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(lineRepo.findByManualJournalIdOrderByLineNo(any())).thenAnswer(a -> savedLines.stream()
                .filter(l -> l.getManualJournalId().equals(a.getArgument(0))).toList());
        AccountRepository accountRepo = mock(AccountRepository.class);
        when(accountRepo.findAllById(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return ids.stream().map(accounts::get).filter(Objects::nonNull).toList();
        });
        journals = mock(JournalService.class);
        when(journals.postLines(any(), any(), any(), any(), any(), anyList())).thenAnswer(a -> entry(a.getArgument(3)));
        when(journals.findById(any())).thenAnswer(a -> {
            JournalEntry e = entry(SEPT_30);
            e.setId(a.getArgument(0));
            return e;
        });
        when(journals.reverse(any(), any(), any())).thenAnswer(a -> entry(a.getArgument(1)));
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.MANUAL_JOURNAL)).thenReturn("MJ-WH-2026-000001");
        service = new ManualJournalService(repo, lineRepo, accountRepo, mock(JournalEntryRepository.class), journals, numbers, CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aBalancedJournalOnUsableAccountsWaitsForApproval() {
        ManualJournal mj = service.create(form(SEPT_30, line(charges, "12500", null, "Statement of 30/09"), line(bank, null, "12500", null)));

        assertThat(mj.getNumber()).isEqualTo("MJ-WH-2026-000001");
        assertThat(mj.getStatus()).isEqualTo(ManualJournalStatus.PENDING_APPROVAL);
        assertThat(mj.getTotal()).isEqualByComparingTo("12500");
        assertThat(mj.getEntryDate()).isEqualTo(SEPT_30);
        assertThat(mj.getRequestedBy()).isEqualTo("accountant");
        assertThat(mj.getRequestedById()).isEqualTo(7L);
        assertThat(savedLines).extracting(ManualJournalLine::getLineNo).containsExactly(1, 2);
        assertThat(savedLines.get(0).getDebit()).isEqualByComparingTo("12500");
        assertThat(savedLines.get(0).getCredit()).isEqualByComparingTo("0");
        assertThat(savedLines.get(1).getMemo()).isNull();
        verifyNoInteractions(journals);                                                 // nothing posted yet
    }

    @Test
    void whatIsRefusedWhenAsking() {
        refused(form(LocalDate.of(2026, 10, 10), line(charges, "100", null, null), line(bank, null, "100", null)), "entryDate", "manualJournal.date.future");
        refused(form(SEPT_30, line(charges, "100", null, null)), null, "manualJournal.lines.required");
        refused(form(SEPT_30, line(charges, "100", "100", null), line(bank, null, "100", null)), "lines[0].debit", "manualJournal.line.oneSide");
        refused(form(SEPT_30, line(charges, null, null, "nothing"), line(bank, null, "100", null)), "lines[0].debit", "manualJournal.line.oneSide");
        refused(form(SEPT_30, line(charges, "100", null, null), line(receivable, null, "100", null)), "lines[1].accountId",
                "manualJournal.line.account.controlled");
        refused(form(SEPT_30, line(retired, "100", null, null), line(bank, null, "100", null)), "lines[0].accountId",
                "manualJournal.line.account.disabled");
        refused(form(SEPT_30, line(charges, "100", null, null), line(bank, null, "99.99", null)), null, "manualJournal.unbalanced");
        assertThat(saved).isEmpty();
    }

    @Test
    void anotherPersonApprovesItAndItsJournalPostsOnItsDate() {
        ManualJournal mj = service.create(form(SEPT_30, line(charges, "12500", null, "Statement of 30/09"), line(bank, null, "12500", null)));
        assertThatThrownBy(() -> service.approve(mj.getId(), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.ownApproval");
        assertThatThrownBy(() -> service.reject(mj.getId(), "Wrong"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.ownReject");

        signIn(9L, "owner");
        service.approve(mj.getId(), "Checked with the statement");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JournalService.AccountLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(journals).postLines(eq(JournalSource.MANUAL_JOURNAL), eq(mj.getId()), eq("MJ-WH-2026-000001"), eq(SEPT_30), any(), lines.capture());
        assertThat(lines.getValue()).extracting(l -> l.account().getCode() + " " + l.debit().toPlainString() + "/" + l.credit().toPlainString())
                .containsExactly("5190 12500.00/0.00", "1030 0.00/12500.00");
        assertThat(mj.getStatus()).isEqualTo(ManualJournalStatus.POSTED);
        assertThat(mj.getJournalId()).isNotNull();
        assertThat(mj.getDecidedBy()).isEqualTo("owner");
        assertThat(mj.getDecisionNote()).isEqualTo("Checked with the statement");
        assertThatThrownBy(() -> service.approve(mj.getId(), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.notPending");
    }

    @Test
    void anAccountDisabledWhileItWaitedStopsTheApproval() {
        ManualJournal mj = service.create(form(SEPT_30, line(charges, "500", null, null), line(bank, null, "500", null)));
        charges.setEnabled(false);
        signIn(9L, "owner");
        assertThatThrownBy(() -> service.approve(mj.getId(), null))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.line.account.disabled");
        assertThat(mj.getStatus()).isEqualTo(ManualJournalStatus.PENDING_APPROVAL);
    }

    @Test
    void theRequesterWithdrawsItAndAnotherPersonRejectsIt() {
        ManualJournal mj = service.create(form(SEPT_30, line(charges, "500", null, null), line(bank, null, "500", null)));
        signIn(9L, "owner");
        assertThatThrownBy(() -> service.cancel(mj.getId(), "Not mine"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.notYours");
        service.reject(mj.getId(), "  Wrong account  ");
        assertThat(mj.getStatus()).isEqualTo(ManualJournalStatus.REJECTED);
        assertThat(mj.getDecisionNote()).isEqualTo("Wrong account");

        signIn(7L, "accountant");
        ManualJournal second = service.create(form(SEPT_30, line(charges, "500", null, null), line(bank, null, "500", null)));
        service.cancel(second.getId(), "Entered twice");
        assertThat(second.getStatus()).isEqualTo(ManualJournalStatus.CANCELLED);
        assertThat(second.getDecidedBy()).isEqualTo("accountant");
        verify(journals, never()).postLines(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void aPostedJournalIsReversedOnceFromItsDateToToday() {
        ManualJournal mj = service.create(form(SEPT_30, line(charges, "12500", null, null), line(bank, null, "12500", null)));
        assertThatThrownBy(() -> service.reverse(mj.getId(), null, "Too early"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.notPosted");
        signIn(9L, "owner");
        service.approve(mj.getId(), null);
        signIn(7L, "accountant");

        assertThatThrownBy(() -> service.reverse(mj.getId(), LocalDate.of(2026, 9, 29), "Booked twice"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.reversal.early");
        assertThatThrownBy(() -> service.reverse(mj.getId(), LocalDate.of(2026, 10, 10), "Booked twice"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.reversal.future");

        service.reverse(mj.getId(), null, " Booked twice ");                           // today by default

        ArgumentCaptor<JournalEntry> original = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journals).reverse(original.capture(), eq(LocalDate.of(2026, 10, 9)), eq("Reversal of MJ-WH-2026-000001: Booked twice"));
        assertThat(original.getValue().getId()).isEqualTo(mj.getJournalId());
        assertThat(mj.getStatus()).isEqualTo(ManualJournalStatus.REVERSED);
        assertThat(mj.getReversalJournalId()).isNotNull();
        assertThat(mj.getReversalDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(mj.getReversalReason()).isEqualTo("Booked twice");
        assertThat(mj.getReversedBy()).isEqualTo("accountant");
        assertThatThrownBy(() -> service.reverse(mj.getId(), null, "Again"))
                .isInstanceOf(BusinessException.class).extracting("messageKey").isEqualTo("manualJournal.notPosted");
    }

    @Test
    void aCopyKeepsTheLinesAndIsDatedToday() {
        ManualJournal mj = service.create(form(SEPT_30, line(charges, "12500", null, "Statement"), line(bank, null, "12500", null)));
        ManualJournalDto copy = service.newForm(mj.getId());
        assertThat(copy.getEntryDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(copy.getDescription()).isEqualTo("Bank charges of September");
        assertThat(copy.getLines()).extracting(ManualJournalDto.Line::getAccountId).containsExactly(charges.getId(), bank.getId());
        assertThat(copy.getLines().get(0).getCredit()).isNull();
        assertThat(service.newForm(null).getLines()).hasSize(2);                       // two empty rows to start with
    }

    // ---------------------------------------------------------------- helpers

    private void refused(ManualJournalDto dto, String field, String key) {
        assertThatThrownBy(() -> service.create(dto)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo(key);
            assertThat(e.getField()).isEqualTo(field);
        });
    }

    private static ManualJournalDto form(LocalDate date, ManualJournalDto.Line... lines) {
        ManualJournalDto dto = new ManualJournalDto();
        dto.setEntryDate(date);
        dto.setDescription("Bank charges of September");
        dto.setLines(new ArrayList<>(List.of(lines)));
        return dto;
    }

    private static ManualJournalDto.Line line(Account account, String debit, String credit, String memo) {
        return new ManualJournalDto.Line(account.getId(), debit == null ? null : new BigDecimal(debit),
                credit == null ? null : new BigDecimal(credit), memo);
    }

    private Account account(String code, String name, AccountType type, AccountKey key, boolean enabled) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(name);
        a.setType(type);
        a.setSystemKey(key);
        a.setEnabled(enabled);
        accounts.put(a.getId(), a);
        return a;
    }

    private static JournalEntry entry(LocalDate date) {
        JournalEntry e = new JournalEntry();
        e.setId(UUID.randomUUID());
        e.setEntryDate(date);
        return e;
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
