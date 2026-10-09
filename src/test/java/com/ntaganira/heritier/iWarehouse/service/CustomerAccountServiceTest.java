package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Customer accounts (ACC-09): the statement with its running balance, the ageing at the customer's terms, the aged
 * receivables, and a payment on the account that settles at most what is owed.
 */
class CustomerAccountServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private CustomerPaymentRepository repo;
    private JournalService journals;
    private TillService tills;
    private PostingService postings;
    private CustomerAccountService service;
    private Customer builders;
    private Customer walkIn;
    private final List<JournalLine> lines = new ArrayList<>();
    private BigDecimal owed = new BigDecimal("150000");

    @BeforeEach
    void setUp() {
        AppUserPrincipal principal = new AppUserPrincipal(7L, "accountant", "accountant", "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        builders = customer("Umucyo Builders", CustomerType.CONTRACTOR, 30);
        walkIn = customer("Walk-in customer", CustomerType.WALK_IN, 0);
        repo = mock(CustomerPaymentRepository.class);
        when(repo.save(any())).thenAnswer(a -> {
            CustomerPayment p = a.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        when(customerRepo.lockById(builders.getId())).thenReturn(Optional.of(builders));
        when(customerRepo.findAllById(any())).thenReturn(List.of(builders, walkIn));
        journals = mock(JournalService.class);
        when(journals.customerLines(builders.getId())).thenAnswer(a -> lines);
        when(journals.receivable(builders.getId())).thenAnswer(a -> owed);
        tills = mock(TillService.class);
        TillSession till = new TillSession();
        till.setId(UUID.randomUUID());
        till.setNumber("TILL-WH-2026-000030");
        when(tills.lockCurrent()).thenReturn(till);
        postings = mock(PostingService.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.RECEIPT)).thenReturn("RCT-WH-2026-000001");
        service = new CustomerAccountService(repo, customerRepo, journals, tills, postings, numbers, CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theStatementRunsTheBalanceAndTheAgeingFollowsTheTerms() {
        lines.add(line("2026-08-01", "JV-WH-2026-000010", JournalSource.SALES_INVOICE, "100000", "0"));   // due 31/08: 39 days
        lines.add(line("2026-09-20", "JV-WH-2026-000020", JournalSource.SALES_INVOICE, "80000", "0"));    // due 20/10
        lines.add(line("2026-09-25", "JV-WH-2026-000025", JournalSource.CUSTOMER_PAYMENT, "0", "30000"));

        CustomerAccountService.Account account = service.account(builders);

        assertThat(account.balance()).isEqualByComparingTo("150000");
        assertThat(account.statement()).extracting(r -> r.balance().intValue()).containsExactly(150000, 180000, 100000);   // newest first
        assertThat(account.ageing().get(Ageing.Bucket.DAYS_31_60)).isEqualByComparingTo("70000");
        assertThat(account.ageing().get(Ageing.Bucket.NOT_DUE)).isEqualByComparingTo("80000");
    }

    @Test
    void theAgedReceivablesListWhoOwesLargestFirstWithTotals() {
        UUID b = builders.getId();
        UUID w = walkIn.getId();
        when(journals.receivableEntries()).thenReturn(List.of(
                new Object[]{b, LocalDate.of(2026, 9, 20), new BigDecimal("100000"), BigDecimal.ZERO},
                new Object[]{w, LocalDate.of(2026, 10, 1), new BigDecimal("6500"), BigDecimal.ZERO},
                new Object[]{w, LocalDate.of(2026, 10, 2), BigDecimal.ZERO, new BigDecimal("6500")},       // paid: off the list
                new Object[]{w, LocalDate.of(2026, 10, 5), new BigDecimal("13500"), BigDecimal.ZERO}));

        CustomerAccountService.Receivables r = service.receivables();

        assertThat(r.customers()).extracting(a -> a.customer().getName()).containsExactly("Umucyo Builders", "Walk-in customer");
        assertThat(r.total()).isEqualByComparingTo("113500");
        assertThat(r.totals().get(Ageing.Bucket.NOT_DUE)).isEqualByComparingTo("100000");              // due 20/10 (30 days)
        assertThat(r.totals().get(Ageing.Bucket.DAYS_1_30)).isEqualByComparingTo("13500");             // walk-in: on the spot
        assertThat(r.totals().get(Ageing.Bucket.OVER_90)).isEqualByComparingTo("0");
    }

    @Test
    void aPaymentSettlesAtMostWhatIsOwedAndIsPosted() {
        assertThatThrownBy(() -> service.receive(builders.getId(), form("200000", PaymentMethod.BANK_TRANSFER, "TRF-1", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("amount");
                    assertThat(e.getMessageKey()).isEqualTo("customerPayment.tooMuch");
                });
        assertThatThrownBy(() -> service.receive(builders.getId(), form("50000", PaymentMethod.MOBILE_MONEY, " ", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("customerPayment.refRequired"));
        assertThatThrownBy(() -> service.receive(builders.getId(), form("50000", PaymentMethod.CREDIT, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("customerPayment.method.required"));
        assertThatThrownBy(() -> service.receive(builders.getId(), form("50000", PaymentMethod.CASH, null, "40000")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("customerPayment.tenderedShort"));

        CustomerPayment cash = service.receive(builders.getId(), form("50000", PaymentMethod.CASH, null, "60000"));

        assertThat(cash.getNumber()).isEqualTo("RCT-WH-2026-000001");
        assertThat(cash.getTillSessionId()).isNotNull();
        assertThat(cash.getChange()).isEqualByComparingTo("10000");
        assertThat(cash.getPostedBy()).isEqualTo("accountant");
        verify(postings).customerPayment(cash);

        CustomerPayment transfer = service.receive(builders.getId(), form("100000", PaymentMethod.BANK_TRANSFER, " TRF-77 ", null));
        assertThat(transfer.getTillSessionId()).isNull();
        assertThat(transfer.getReference()).isEqualTo("TRF-77");
        assertThat(transfer.getCashTendered()).isNull();
        verify(tills, times(2)).lockCurrent();                                        // the cash attempts only

        owed = BigDecimal.ZERO;
        assertThatThrownBy(() -> service.receive(builders.getId(), form("1", PaymentMethod.BANK_TRANSFER, "TRF-2", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("customerPayment.nothingOwed"));
    }

    private static CustomerAccountService.PaymentForm form(String amount, PaymentMethod method, String reference, String tendered) {
        return new CustomerAccountService.PaymentForm(new BigDecimal(amount), method, reference, tendered == null ? null : new BigDecimal(tendered), null);
    }

    private static JournalLine line(String date, String number, JournalSource source, String debit, String credit) {
        JournalEntry entry = new JournalEntry();
        entry.setId(UUID.randomUUID());
        entry.setNumber(number);
        entry.setEntryDate(LocalDate.parse(date));
        entry.setSourceType(source);
        JournalLine line = new JournalLine();
        line.setEntry(entry);
        line.setDebit(new BigDecimal(debit));
        line.setCredit(new BigDecimal(credit));
        return line;
    }

    private static Customer customer(String name, CustomerType type, int termsDays) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setType(type);
        c.setPaymentTermsDays(termsDays);
        c.setEnabled(true);
        return c;
    }
}
