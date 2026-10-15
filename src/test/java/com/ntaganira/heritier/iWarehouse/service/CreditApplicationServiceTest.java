package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CreditApplication;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.CreditApplicationRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.PriceListRepository;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Credit applications (F-02 C-D): Credit Control assesses, the Director of Finance reviews, a fourth person approves within
 * the Delegation of Authority and the terms are set with the next review date; reviews fall due after the Settings' months
 * or when an invoice is long overdue.
 */
class CreditApplicationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final Customer builders = new Customer();
    private CreditApplicationRepository repo;
    private CustomerRepository customerRepo;
    private CustomerService customerService;
    private CustomerAgeing ageing;
    private DelegationOfAuthority authority;
    private SettingService settings;
    private CreditApplicationService service;

    @BeforeEach
    void setUp() {
        builders.setId(UUID.randomUUID());
        builders.setCode("CUS-WH-00001");
        builders.setName("Umucyo Builders Ltd");
        builders.setType(CustomerType.ACCOUNT);
        repo = mock(CreditApplicationRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            CreditApplication app = a.getArgument(0);
            app.setId(UUID.randomUUID());
            when(repo.lockById(app.getId())).thenReturn(Optional.of(app));
            when(repo.findDetailedById(app.getId())).thenReturn(Optional.of(app));
            return app;
        });
        customerRepo = mock(CustomerRepository.class);
        when(customerRepo.findWithPriceListById(builders.getId())).thenReturn(Optional.of(builders));
        when(customerRepo.findAll()).thenReturn(List.of(builders));
        customerService = mock(CustomerService.class);
        JournalService journals = mock(JournalService.class);
        when(journals.receivable(builders.getId())).thenReturn(new BigDecimal("150000.00"));
        ageing = mock(CustomerAgeing.class);
        when(ageing.overdue(eq(builders), eq(0))).thenReturn(CustomerAgeing.Overdue.NONE);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.CREDIT_APPLICATION)).thenReturn("CAP-WH-2026-000001");
        authority = mock(DelegationOfAuthority.class);
        when(authority.required(eq(AuthorityDecision.CREDIT_LIMIT), any())).thenReturn(AuthorityLevel.COO);
        settings = mock(SettingService.class);
        when(settings.getBoolean(SettingKey.APPROVAL_CREDIT_APPLICATION_REVIEW)).thenReturn(true);
        when(settings.getInt(SettingKey.CREDIT_REVIEW_MONTHS)).thenReturn(12);
        when(settings.getInt(SettingKey.CREDIT_REVIEW_OVERDUE_DAYS)).thenReturn(90);
        service = new CreditApplicationService(repo, customerRepo, mock(PriceListRepository.class), customerService, journals, ageing,
                numbers, authority, settings, mock(AlertStateRepository.class), mock(Notifier.class), CLOCK);
        signIn(4L, "sales");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void creditControlAssessesTheDirectorReviewsAndAFourthPersonApproves() {
        CreditApplication a = service.request(builders.getId(), form("5000000", 30, "Regular contractor, pays on time"));
        assertThat(a.getKind()).isEqualTo(CreditApplicationKind.NEW);
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.PENDING_ASSESSMENT);
        assertThat(a.getOwedWhenAsked()).isEqualByComparingTo("150000");
        assertThat(a.getPreviousLimit()).isEqualByComparingTo("0");
        assertThat(a.getAuthorityLevel()).isEqualTo(AuthorityLevel.COO);

        assertThatThrownBy(() -> service.assess(a.getId(), "fine"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("creditApp.ownAssessment"));
        signIn(5L, "credit");
        assertThatThrownBy(() -> service.assess(a.getId(), "  "))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("creditApp.assessment.required"));
        service.assess(a.getId(), "Pays on time; RDB and TIN papers kept");
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.PENDING_REVIEW);
        assertThatThrownBy(() -> service.review(a.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("creditApp.ownReview"));
        assertThatThrownBy(() -> service.approve(a.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("creditApp.notYetForApproval"));

        signIn(6L, "finance");
        service.review(a.getId(), "agreed");
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> service.approve(a.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("creditApp.ownApproval"));

        signIn(9L, "coo");
        service.approve(a.getId(), "ok");
        verify(authority).require(AuthorityDecision.CREDIT_LIMIT, new BigDecimal("5000000.00"), "ok");
        verify(customerService).applyApprovedTerms(builders.getId(), new BigDecimal("5000000.00"), 30, null,
                LocalDate.of(2026, 10, 15), LocalDate.of(2027, 10, 15));
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.APPROVED);
        assertThat(a.getDecidedBy()).isEqualTo("coo");
    }

    @Test
    void withoutTheReviewStepItWaitsForApprovalOnceAssessed() {
        when(settings.getBoolean(SettingKey.APPROVAL_CREDIT_APPLICATION_REVIEW)).thenReturn(false);
        CreditApplication a = service.request(builders.getId(), form("1000000", 15, "First order on account"));
        signIn(5L, "credit");
        service.assess(a.getId(), "ok");
        assertThat(a.getStatus()).isEqualTo(CreditApplicationStatus.PENDING_APPROVAL);
    }

    @Test
    void onlyAccountCustomersAndOneApplicationAtATime() {
        Customer walkIn = new Customer();
        walkIn.setId(UUID.randomUUID());
        walkIn.setName("Walk-in customer");
        walkIn.setType(CustomerType.WALK_IN);
        when(customerRepo.findWithPriceListById(walkIn.getId())).thenReturn(Optional.of(walkIn));
        assertThatThrownBy(() -> service.request(walkIn.getId(), form("100000", 7, "x")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("creditApp.walkIn"));

        CreditApplication first = service.request(builders.getId(), form("1000000", 15, "First order on account"));
        when(repo.findByCustomer_IdAndStatusIn(eq(builders.getId()), anyCollection())).thenReturn(List.of(first));
        assertThatThrownBy(() -> service.request(builders.getId(), form("2000000", 30, "More")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("creditApp.waiting"));
    }

    @Test
    void creditFallsDueForReviewByDateNeverOrALongOverdueInvoice() {
        builders.setCreditLimit(new BigDecimal("5000000"));
        builders.setPaymentTermsDays(30);
        assertThat(service.reviewsDue()).extracting(CreditApplicationService.ReviewDue::reason).containsExactly(CreditApplicationService.DueReason.NEVER);
        builders.setCreditReviewDue(LocalDate.of(2027, 1, 1));
        assertThat(service.reviewsDue()).isEmpty();
        builders.setCreditReviewDue(LocalDate.of(2026, 10, 15));
        assertThat(service.reviewsDue()).extracting(CreditApplicationService.ReviewDue::reason).containsExactly(CreditApplicationService.DueReason.DATE);
        builders.setCreditReviewDue(LocalDate.of(2027, 1, 1));
        when(ageing.overdue(eq(builders), eq(0))).thenReturn(new CustomerAgeing.Overdue(new BigDecimal("100000"), 95));
        assertThat(service.reviewsDue()).extracting(CreditApplicationService.ReviewDue::reason).containsExactly(CreditApplicationService.DueReason.OVERDUE);
        builders.setCreditLimit(BigDecimal.ZERO);
        builders.setPaymentTermsDays(0);
        assertThat(service.reviewsDue()).isEmpty();                                // no credit, nothing to review
    }

    private static CreditApplicationService.Form form(String limit, int days, String reason) {
        return new CreditApplicationService.Form(new BigDecimal(limit), days, null, reason);
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
