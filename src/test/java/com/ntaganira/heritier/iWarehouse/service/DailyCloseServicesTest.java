package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DailyCloseServicesTest.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The rules of M14's services: the till counted note by note and verified by another person (F-13), the
 *               takings banked in full (SDP 10), the daily close prepared, signed by another person once its figures
 *               still hold (F-12), transfers confirmed by another person (SDP 14), balance confirmations (SDP 10) and the
 *               month's sign-off by the COO then the MD (BP 4.2).
 * </pre>
 */
class DailyCloseServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneId.of("Africa/Kigali"));
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- the till (F-13)

    @Test
    void theTillIsCountedNoteByNoteAndVerifiedByAnotherPerson() {
        TillSessionRepository tillRepo = mock(TillSessionRepository.class);
        SalesInvoiceRepository invoiceRepo = mock(SalesInvoiceRepository.class);
        SalesPaymentRepository paymentRepo = mock(SalesPaymentRepository.class);
        CreditNoteRepository creditNoteRepo = mock(CreditNoteRepository.class);
        CustomerPaymentRepository customerPaymentRepo = mock(CustomerPaymentRepository.class);
        SettingService settings = mock(SettingService.class);
        when(settings.get(SettingKey.CASH_DENOMINATIONS)).thenReturn("5000,2000,1000,500,100,50,20,10,5,1");
        TillService tills = new TillService(tillRepo, invoiceRepo, paymentRepo, creditNoteRepo, customerPaymentRepo, mock(PostingService.class),
                mock(DocumentNumberService.class), settings, CLOCK);
        TillSession till = till(7L, "cashier1", "20000.00");
        when(tillRepo.lockById(till.getId())).thenReturn(Optional.of(till));
        when(paymentRepo.totalsOfSession(till.getId())).thenReturn(List.<Object[]>of(new Object[]{PaymentMethod.CASH, new BigDecimal("43035.00")}));

        signIn(7L, "cashier1", "PERM_SELL");
        tills.close(till.getId(), Map.of(5000, 12, 1000, 3, 5, 7), null, null);    // 63,035 counted = 20,000 float + 43,035 taken
        assertThat(till.getCountedCash()).isEqualByComparingTo("63035");
        assertThat(till.getDifference()).isEqualByComparingTo("0");
        assertThat(till.getDenominations()).isEqualTo("5000:12,1000:3,5:7");
        assertThat(till.getCashToBank()).isEqualByComparingTo("43035");

        assertKey(() -> tills.verify(till.getId(), null), "till.verify.own");
        signIn(9L, "treasury1", "PERM_VERIFY_CASH_COUNT");
        tills.verify(till.getId(), "Recounted, agrees");
        assertThat(till.isVerified()).isTrue();
        assertThat(till.getVerifiedBy()).isEqualTo("treasury1");
        assertKey(() -> tills.verify(till.getId(), null), "till.verify.done");
    }

    // ---------------------------------------------------------------- banking (SDP 10)

    @Test
    void takingsAreBankedInFullOnceVerified() {
        TillSessionRepository tillRepo = mock(TillSessionRepository.class);
        CashDepositRepository repo = mock(CashDepositRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        PostingService postings = mock(PostingService.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        SettingService settings = mock(SettingService.class);
        when(settings.getBoolean(SettingKey.CASH_VERIFY_COUNT)).thenReturn(true);
        when(numbers.next(DocumentType.CASH_DEPOSIT)).thenReturn("BNK-WH-2026-000001");
        Account bank = new Account();
        bank.setId(UUID.randomUUID());
        when(accountRepo.findBySystemKey(AccountKey.BANK)).thenReturn(Optional.of(bank));
        when(repo.save(any())).thenAnswer(a -> {
            CashDeposit d = a.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        CashDepositService service = new CashDepositService(repo, tillRepo, accountRepo, postings, numbers, settings,
                mock(AlertStateRepository.class), mock(Notifier.class), CLOCK);
        TillSession a = closed(till(7L, "cashier1", "20000.00"), "63035.00");
        TillSession b = closed(till(8L, "cashier2", "10000.00"), "25000.00");
        when(tillRepo.lockAllById(any())).thenReturn(List.of(a, b));
        signIn(9L, "treasury1", "PERM_BANK_CASH");

        assertKey(() -> service.bank(new CashDepositService.Form(List.of(a.getId(), b.getId()), "BK-1", new BigDecimal("58035"), null)),
                "deposit.till.notVerified");
        a.setVerifiedAt(LocalDateTime.now(CLOCK));
        a.setVerifiedBy("treasury1");
        b.setVerifiedAt(LocalDateTime.now(CLOCK));
        b.setVerifiedBy("treasury1");
        // 43,035 + 15,000 = 58,035 to bank: anything less is netted
        assertKey(() -> service.bank(new CashDepositService.Form(List.of(a.getId(), b.getId()), "BK-1", new BigDecimal("50000"), null)),
                "deposit.amount.netted");
        assertKey(() -> service.bank(new CashDepositService.Form(List.of(a.getId(), b.getId()), " ", new BigDecimal("58035"), null)),
                "deposit.slip.required");
        CashDeposit deposit = service.bank(new CashDepositService.Form(List.of(a.getId(), b.getId()), "BK-778812", new BigDecimal("58035"), null));
        assertThat(deposit.getAmount()).isEqualByComparingTo("58035");
        assertThat(a.getDepositId()).isEqualTo(deposit.getId());
        assertThat(b.isBanked()).isTrue();
        verify(postings).cashDeposit(deposit);
        assertKey(() -> service.bank(new CashDepositService.Form(List.of(a.getId(), b.getId()), "BK-2", new BigDecimal("58035"), null)),
                "deposit.till.banked");
    }

    // ---------------------------------------------------------------- the daily close (F-12)

    @Test
    void theDailyCloseIsPreparedThenSignedByAnotherPerson() {
        DailyCloseRepository repo = mock(DailyCloseRepository.class);
        SalesInvoiceRepository invoiceRepo = mock(SalesInvoiceRepository.class);
        SalesPaymentRepository paymentRepo = mock(SalesPaymentRepository.class);
        TillSessionRepository tillRepo = mock(TillSessionRepository.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.DAILY_CLOSE)).thenReturn("DC-WH-2026-000001");
        List<DailyClose> saved = new ArrayList<>();
        when(repo.save(any())).thenAnswer(a -> {
            DailyClose c = a.getArgument(0);
            c.setId(UUID.randomUUID());
            saved.add(c);
            return c;
        });
        DailyCloseService service = new DailyCloseService(repo, invoiceRepo, paymentRepo, mock(CustomerPaymentRepository.class),
                mock(CreditNoteRepository.class), tillRepo, mock(CashDepositRepository.class), mock(EbmReceiptRepository.class),
                mock(StockUnitRepository.class), mock(DeliveryNoteRepository.class), numbers, mock(SettingService.class),
                mock(AlertStateRepository.class), mock(Notifier.class), CLOCK);
        // One invoice of 15,000: 10,000 cash and 5,000 MoMo; the till closed 1,000 short
        Customer walkIn = new Customer();
        walkIn.setName("Walk-in");
        SalesInvoice inv = new SalesInvoice();
        inv.setId(UUID.randomUUID());
        inv.setNumber("INV-WH-2026-000030");
        inv.setCustomer(walkIn);
        inv.setTotalAmount(new BigDecimal("15000.00"));
        inv.setPostedBy("cashier1");
        when(invoiceRepo.findByStatusAndInvoiceDateOrderByNumber(SalesInvoiceStatus.POSTED, DAY)).thenReturn(List.of(inv));
        when(paymentRepo.findByInvoiceIdIn(any())).thenReturn(List.of(payment(inv, PaymentMethod.CASH, "10000.00"),
                payment(inv, PaymentMethod.MOBILE_MONEY, "5000.00")));
        TillSession till = closed(till(7L, "cashier1", "20000.00"), "29000.00");
        till.setExpectedCash(new BigDecimal("30000.00"));
        when(tillRepo.findByClosedAtBetweenOrderByClosedAtAsc(any(), any())).thenReturn(List.of(till));

        DailyCloseService.Report report = service.report(DAY);
        assertThat(report.getSalesTotal()).isEqualByComparingTo("15000");
        assertThat(report.momoReceived()).isEqualByComparingTo("5000");
        assertThat(report.getCashVariance()).isEqualByComparingTo("-1000");
        assertThat(report.channels()).extracting(DailyCloses.ChannelRow::channel).containsExactly("COUNTER");

        signIn(11L, "recording1", "PERM_PREPARE_DAILY_CLOSE");
        assertKey(() -> service.prepare(DAY, null, "Short at the till"), "dailyClose.momo.required");
        assertKey(() -> service.prepare(DAY, new BigDecimal("5000"), null), "dailyClose.note.required");
        DailyClose close = service.prepare(DAY, new BigDecimal("5000"), "Cashier short 1,000: recovered");
        assertThat(close.getNumber()).isEqualTo("DC-WH-2026-000001");
        assertThat(close.getCashVariance()).isEqualByComparingTo("-1000");
        assertThat(close.getMomoVariance()).isEqualByComparingTo("0");
        assertThat(close.getPreparedById()).isEqualTo(11L);
        when(repo.lockById(close.getId())).thenReturn(Optional.of(close));
        when(repo.findByBusinessDay(DAY)).thenReturn(Optional.of(close));

        assertKey(() -> service.sign(close.getId(), null), "dailyClose.ownSign");
        signIn(12L, "dof1", "PERM_SIGN_DAILY_CLOSE");
        // A sale synced after the close was prepared: the figures no longer hold
        SalesInvoice late = new SalesInvoice();
        late.setId(UUID.randomUUID());
        late.setCustomer(walkIn);
        late.setTotalAmount(new BigDecimal("3000.00"));
        late.setPostedBy("driver1");
        when(invoiceRepo.findByStatusAndInvoiceDateOrderByNumber(SalesInvoiceStatus.POSTED, DAY)).thenReturn(List.of(inv, late));
        assertKey(() -> service.sign(close.getId(), null), "dailyClose.changed");
        when(invoiceRepo.findByStatusAndInvoiceDateOrderByNumber(SalesInvoiceStatus.POSTED, DAY)).thenReturn(List.of(inv));
        service.sign(close.getId(), "Seen");
        assertThat(close.isSigned()).isTrue();
        assertKey(() -> service.prepare(DAY, new BigDecimal("5000"), "x"), "dailyClose.signed");

        // The test: a third person, with what was checked
        assertKey(() -> service.test(close.getId(), "Checked"), "dailyClose.test.own");
        signIn(13L, "ic1", "PERM_TEST_DAILY_CLOSE");
        assertKey(() -> service.test(close.getId(), " "), "dailyClose.test.note");
        service.test(close.getId(), "Tills, slips and MoMo statement checked");
        assertThat(close.isTested()).isTrue();
    }

    @Test
    void aDayIsNotClosedWhileOneOfItsTillsIsOpen() {
        TillSessionRepository tillRepo = mock(TillSessionRepository.class);
        DailyCloseService service = new DailyCloseService(mock(DailyCloseRepository.class), mock(SalesInvoiceRepository.class),
                mock(SalesPaymentRepository.class), mock(CustomerPaymentRepository.class), mock(CreditNoteRepository.class), tillRepo,
                mock(CashDepositRepository.class), mock(EbmReceiptRepository.class), mock(StockUnitRepository.class),
                mock(DeliveryNoteRepository.class), mock(DocumentNumberService.class), mock(SettingService.class),
                mock(AlertStateRepository.class), mock(Notifier.class), CLOCK);
        when(tillRepo.findByStatusAndOpenedAtBeforeOrderByOpenedAtAsc(eq(TillStatus.OPEN), any())).thenReturn(List.of(till(7L, "cashier1", "0.00")));
        signIn(11L, "recording1", "PERM_PREPARE_DAILY_CLOSE");
        assertKey(() -> service.prepare(DAY, null, null), "dailyClose.tillsOpen");
        assertKey(() -> service.prepare(DAY.plusDays(2), null, null), "dailyClose.day.invalid");
    }

    // ---------------------------------------------------------------- transfers (SDP 14)

    @Test
    void aTransferIsConfirmedOnceByAnotherPerson() {
        SalesPaymentRepository paymentRepo = mock(SalesPaymentRepository.class);
        PaymentConfirmationRepository repo = mock(PaymentConfirmationRepository.class);
        SalesInvoiceRepository invoiceRepo = mock(SalesInvoiceRepository.class);
        TransferConfirmationService service = new TransferConfirmationService(paymentRepo, repo, invoiceRepo, mock(Notifier.class), CLOCK);
        SalesInvoice inv = new SalesInvoice();
        inv.setId(UUID.randomUUID());
        inv.setNumber("INV-WH-2026-000031");
        SalesPayment p = payment(inv, PaymentMethod.BANK_TRANSFER, "250000.00");
        p.setUsername("cashier1");
        when(paymentRepo.findById(p.getId())).thenReturn(Optional.of(p));
        when(invoiceRepo.findById(inv.getId())).thenReturn(Optional.of(inv));

        signIn(7L, "cashier1", "PERM_CONFIRM_TRANSFER");
        assertKey(() -> service.confirm(p.getId(), "FT2610150001", null), "bankTransfer.own");
        signIn(9L, "treasury1", "PERM_CONFIRM_TRANSFER");
        PaymentConfirmation c = service.confirm(p.getId(), "FT2610150001", null);
        assertThat(c.getInvoiceId()).isEqualTo(inv.getId());
        verify(repo).save(c);
        when(repo.findBySalesPaymentId(p.getId())).thenReturn(Optional.of(c));
        assertKey(() -> service.confirm(p.getId(), null, null), "bankTransfer.done");
        SalesPayment cash = payment(inv, PaymentMethod.CASH, "1000.00");
        when(paymentRepo.findById(cash.getId())).thenReturn(Optional.of(cash));
        assertKey(() -> service.confirm(cash.getId(), null, null), "bankTransfer.notTransfer");
    }

    // ---------------------------------------------------------------- balance confirmations (SDP 10)

    @Test
    void aDisputedBalanceNeedsTheCustomersFigureAndExplanation() {
        BalanceConfirmationRepository repo = mock(BalanceConfirmationRepository.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        JournalLineRepository lineRepo = mock(JournalLineRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        Notifier notifier = mock(Notifier.class);
        DocumentNumberService numbers = mock(DocumentNumberService.class);
        when(numbers.next(DocumentType.BALANCE_CONFIRMATION)).thenReturn("BC-WH-2026-000001");
        Account receivable = new Account();
        receivable.setId(UUID.randomUUID());
        when(accountRepo.findBySystemKey(AccountKey.RECEIVABLE)).thenReturn(Optional.of(receivable));
        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setName("Umucyo Builders Ltd");
        when(customerRepo.lockById(customer.getId())).thenReturn(Optional.of(customer));
        when(lineRepo.balanceOfCustomerAt(receivable.getId(), customer.getId(), DAY)).thenReturn(new BigDecimal("85000.00"));
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
        BalanceConfirmationService service = new BalanceConfirmationService(repo, customerRepo, lineRepo, accountRepo, numbers, notifier, CLOCK);

        signIn(13L, "ic1", "PERM_CONFIRM_BALANCES");
        assertKey(() -> service.send(customer.getId(), DAY.plusDays(5)), "confirmation.asOf.invalid");
        BalanceConfirmation c = service.send(customer.getId(), DAY);
        assertThat(c.getBalance()).isEqualByComparingTo("85000");
        assertThat(c.isWaiting()).isTrue();
        when(repo.existsByCustomer_IdAndStatus(customer.getId(), ConfirmationStatus.SENT)).thenReturn(true);
        assertKey(() -> service.send(customer.getId(), DAY), "confirmation.waiting");

        c.setId(UUID.randomUUID());
        when(repo.lockById(c.getId())).thenReturn(Optional.of(c));
        LocalDate replied = LocalDate.now(CLOCK);
        assertKey(() -> service.reply(c.getId(), new BalanceConfirmationService.Reply(false, replied, null, "We paid 20,000")),
                "confirmation.theirBalance.required");
        assertKey(() -> service.reply(c.getId(), new BalanceConfirmationService.Reply(false, replied, new BigDecimal("65000"), null)),
                "confirmation.note.required");
        service.reply(c.getId(), new BalanceConfirmationService.Reply(false, replied, new BigDecimal("65000"), "We paid 20,000 on 10/10"));
        assertThat(c.getStatus()).isEqualTo(ConfirmationStatus.DISAGREED);
        assertThat(c.getDifference()).isEqualByComparingTo("-20000");
        verify(notifier).holders(eq("ALERT_DAILY_CLOSE"), eq(13L), eq(NotificationKind.CLOSE), eq("notify.confirmation.disputed"), any(), any(),
                any(String[].class));
        assertKey(() -> service.reply(c.getId(), new BalanceConfirmationService.Reply(true, replied, null, null)), "confirmation.replied");
    }

    // ---------------------------------------------------------------- the month's sign-off (BP 4.2)

    @Test
    void theMonthIsSignedOffByTheCooThenTheMd() {
        PeriodSignOffRepository repo = mock(PeriodSignOffRepository.class);
        AccountingPeriodRepository periodRepo = mock(AccountingPeriodRepository.class);
        PeriodSignOffService service = new PeriodSignOffService(repo, periodRepo, mock(Notifier.class), CLOCK);
        AccountingPeriod period = new AccountingPeriod();
        period.setId(UUID.randomUUID());
        period.setPeriodEnd(LocalDate.of(2026, 9, 30));
        period.setStatus(PeriodStatus.CLOSED);
        period.setClosedAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        when(periodRepo.findById(period.getId())).thenReturn(Optional.of(period));
        List<PeriodSignOff> signOffs = new ArrayList<>();
        when(repo.save(any())).thenAnswer(a -> {
            PeriodSignOff s = a.getArgument(0);
            s.setId(UUID.randomUUID());
            signOffs.add(s);
            return s;
        });
        when(repo.findByPeriodIdOrderByCreatedAtAsc(period.getId())).thenAnswer(a -> List.copyOf(signOffs));

        signIn(20L, "director1", "PERM_SIGN_OFF_PERIOD", "PERM_AUTHORITY_DIRECTOR");
        assertKey(() -> service.signOff(period.getId(), null), "period.signOff.office");
        signIn(21L, "coo1", "PERM_SIGN_OFF_PERIOD", "PERM_AUTHORITY_COO");
        assertThat(service.nextFor(period)).contains(AuthorityLevel.COO);
        service.signOff(period.getId(), "Reviewed");
        assertThat(service.nextFor(period)).isEmpty();                                   // the COO may not sign as MD
        signIn(22L, "md1", "PERM_SIGN_OFF_PERIOD", "PERM_AUTHORITY_MD");
        service.signOff(period.getId(), null);
        PeriodSignOffService.SignOffs s = service.of(period);
        assertThat(s.isComplete()).isTrue();
        assertThat(s.md().get().getUsername()).isEqualTo("md1");
        assertKey(() -> service.signOff(period.getId(), null), "period.signOff.done");

        // Reopened and closed again: the earlier sign-offs stay on record but no longer count
        period.setClosedAt(LocalDateTime.now(CLOCK).plusMinutes(1));
        assertThat(service.of(period).isComplete()).isFalse();
        assertThat(service.of(period).all()).hasSize(2);
    }

    // ---------------------------------------------------------------- helpers

    private static TillSession till(long cashierId, String cashier, String openingFloat) {
        TillSession t = new TillSession();
        t.setId(UUID.randomUUID());
        t.setNumber("TILL-WH-2026-0000" + cashierId);
        t.setCashierId(cashierId);
        t.setCashierUsername(cashier);
        t.setOpenedAt(LocalDateTime.of(2026, 10, 15, 8, 0));
        t.setOpeningFloat(new BigDecimal(openingFloat));
        return t;
    }

    private static TillSession closed(TillSession t, String counted) {
        t.setStatus(TillStatus.CLOSED);
        t.setCountedCash(new BigDecimal(counted));
        t.setExpectedCash(new BigDecimal(counted));
        t.setDifference(BigDecimal.ZERO.setScale(2));
        t.setClosedAt(LocalDateTime.of(2026, 10, 15, 18, 0));
        return t;
    }

    private static SalesPayment payment(SalesInvoice inv, PaymentMethod method, String amount) {
        SalesPayment p = new SalesPayment();
        p.setId(UUID.randomUUID());
        p.setInvoiceId(inv.getId());
        p.setMethod(method);
        p.setAmount(new BigDecimal(amount));
        p.setCreatedAt(LocalDateTime.of(2026, 10, 15, 11, 0));
        return p;
    }

    private static void assertKey(Runnable call, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo(key));
    }

    private static void signIn(long id, String username, String... authorities) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
