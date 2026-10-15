package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.ReceivableWriteOff;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.ReceivableWriteOffRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Receivable write-offs (F-14, Board Paper Table 6): Credit Control prepares one, at most what the customer owes; Finance
 * reviews it; a third person approves it within the Delegation of Authority, which posts bad debts. Nobody decides their own.
 */
class ReceivableWriteOffServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final Customer builders = new Customer();
    private ReceivableWriteOffRepository repo;
    private JournalService journals;
    private PostingService postings;
    private DelegationOfAuthority authority;
    private SettingService settings;
    private ReceivableWriteOffService service;

    @BeforeEach
    void setUp() {
        builders.setId(UUID.randomUUID());
        builders.setName("Umucyo Builders");
        builders.setType(CustomerType.ACCOUNT);
        repo = mock(ReceivableWriteOffRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            ReceivableWriteOff w = a.getArgument(0);
            w.setId(UUID.randomUUID());
            when(repo.lockById(w.getId())).thenReturn(Optional.of(w));
            when(repo.findDetailedById(w.getId())).thenReturn(Optional.of(w));
            return w;
        });
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        when(customerRepo.findById(builders.getId())).thenReturn(Optional.of(builders));
        when(customerRepo.lockById(builders.getId())).thenReturn(Optional.of(builders));
        journals = mock(JournalService.class);
        when(journals.receivable(builders.getId())).thenReturn(new BigDecimal("392038.00"));
        CustomerAgeing ageing = mock(CustomerAgeing.class);
        when(ageing.overdue(builders, 0)).thenReturn(new CustomerAgeing.Overdue(new BigDecimal("300000"), 120));
        postings = mock(PostingService.class);
        JournalEntry entry = new JournalEntry();
        entry.setId(UUID.randomUUID());
        when(postings.receivableWriteOff(any(), any())).thenReturn(entry);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.RECEIVABLE_WRITE_OFF)).thenReturn("RWO-WH-2026-000001");
        authority = mock(DelegationOfAuthority.class);
        when(authority.required(eq(AuthorityDecision.RECEIVABLE_WRITE_OFF), any())).thenReturn(AuthorityLevel.COO);
        settings = mock(SettingService.class);
        when(settings.getBoolean(SettingKey.APPROVAL_WRITE_OFF_REVIEW)).thenReturn(true);
        service = new ReceivableWriteOffService(repo, customerRepo, mock(SalesInvoiceRepository.class), journals, ageing, postings, numbers,
                authority, settings, mock(Notifier.class), CLOCK);
        signIn(4L, "credit");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void creditControlAsksAtMostWhatTheCustomerOwesWithAReason() {
        assertThatThrownBy(() -> service.prepare(builders.getId(), form("400000", "Company closed")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.amount.moreThanOwed"));
        assertThatThrownBy(() -> service.prepare(builders.getId(), form("1000", "  ")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.reason.required"));
        assertThatThrownBy(() -> service.prepare(builders.getId(), form("0", "Company closed")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.amount.invalid"));

        ReceivableWriteOff w = service.prepare(builders.getId(), form("250000", " Company closed "));
        assertThat(w.getStatus()).isEqualTo(WriteOffStatus.PENDING_REVIEW);
        assertThat(w.getOwedWhenAsked()).isEqualByComparingTo("392038");
        assertThat(w.getOldestDays()).isEqualTo(120);
        assertThat(w.getAuthorityLevel()).isEqualTo(AuthorityLevel.COO);
        assertThat(w.getReason()).isEqualTo("Company closed");
        verify(postings, never()).receivableWriteOff(any(), any());
    }

    @Test
    void financeReviewsThenAThirdPersonApprovesAndBadDebtsArePosted() {
        ReceivableWriteOff w = service.prepare(builders.getId(), form("250000", "Company closed"));
        assertThatThrownBy(() -> service.review(w.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.ownReview"));
        signIn(7L, "accountant");
        assertThatThrownBy(() -> service.approve(w.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.reviewFirst"));
        service.review(w.getId(), "recovery tried for 6 months");
        assertThat(w.getStatus()).isEqualTo(WriteOffStatus.PENDING_APPROVAL);
        assertThat(w.getReviewedBy()).isEqualTo("accountant");
        assertThatThrownBy(() -> service.approve(w.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.reviewerApproval"));
        signIn(4L, "credit");
        assertThatThrownBy(() -> service.approve(w.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.ownApproval"));

        signIn(9L, "coo");
        service.approve(w.getId(), "ok");
        verify(authority).require(AuthorityDecision.RECEIVABLE_WRITE_OFF, new BigDecimal("250000.00"), "ok");
        verify(postings).receivableWriteOff(w, LocalDate.of(2026, 10, 15));
        assertThat(w.getStatus()).isEqualTo(WriteOffStatus.POSTED);
        assertThat(w.getDecidedBy()).isEqualTo("coo");
        assertThat(w.getJournalId()).isNotNull();
        assertThat(w.getPostedAt()).isNotNull();
    }

    @Test
    void aWriteOffIsNeverMoreThanWhatIsOwedWhenApproved() {
        when(settings.getBoolean(SettingKey.APPROVAL_WRITE_OFF_REVIEW)).thenReturn(false);
        ReceivableWriteOff w = service.prepare(builders.getId(), form("250000", "Company closed"));
        assertThat(w.getStatus()).isEqualTo(WriteOffStatus.PENDING_APPROVAL);
        when(journals.receivable(builders.getId())).thenReturn(new BigDecimal("200000.00"));     // a payment came in meanwhile
        signIn(9L, "coo");
        assertThatThrownBy(() -> service.approve(w.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.owedNow"));
        assertThat(w.getStatus()).isEqualTo(WriteOffStatus.PENDING_APPROVAL);
        verify(postings, never()).receivableWriteOff(any(), any());

        assertThatThrownBy(() -> service.withdraw(w.getId(), "paid in part"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("writeOff.notYours"));
        signIn(4L, "credit");
        service.withdraw(w.getId(), " paid in part ");
        assertThat(w.getStatus()).isEqualTo(WriteOffStatus.WITHDRAWN);
        assertThat(w.getDecisionNote()).isEqualTo("paid in part");
    }

    private static ReceivableWriteOffService.Form form(String amount, String reason) {
        return new ReceivableWriteOffService.Form(new BigDecimal(amount), null, reason, "Three reminders");
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
