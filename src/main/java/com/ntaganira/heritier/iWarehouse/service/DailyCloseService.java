package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.CashDeposit;
import com.ntaganira.heritier.iWarehouse.entity.CreditNote;
import com.ntaganira.heritier.iWarehouse.entity.CustomerPayment;
import com.ntaganira.heritier.iWarehouse.entity.DailyClose;
import com.ntaganira.heritier.iWarehouse.entity.DeliveryNote;
import com.ntaganira.heritier.iWarehouse.entity.EbmReceipt;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesPayment;
import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.DailyCloseStatus;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.OrderChannel;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.SaleChannel;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AlertStateRepository;
import com.ntaganira.heritier.iWarehouse.repository.CashDepositRepository;
import com.ntaganira.heritier.iWarehouse.repository.CreditNoteRepository;
import com.ntaganira.heritier.iWarehouse.repository.CustomerPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.DailyCloseRepository;
import com.ntaganira.heritier.iWarehouse.repository.DeliveryNoteRepository;
import com.ntaganira.heritier.iWarehouse.repository.EbmReceiptRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesInvoiceRepository;
import com.ntaganira.heritier.iWarehouse.repository.SalesPaymentRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.TillSessionRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DailyCloseService.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The daily close (F-12; BP 7.1 rule 5, 7.5; SDP 15-16): the end-of-day sales report of any day, read
 *               from the documents (EBM receipts issued and their first and last numbers, sales by channel split into
 *               cash, MoMo, card and transfer, and credit, by salesperson and customer, collections, refunds, the credit
 *               granted for Credit Control, the tills' cash expected and counted and what is to bank, MoMo received,
 *               the glass on the vehicles, the goods released). Preparing it (the Sales Recording Officer: decision D4
 *               makes their work reviewing the day) keeps its figures with the MoMo merchant statement and an
 *               explanation of any variance, refused while a till of the day is open; a variance is escalated to the
 *               Director of Finance at once and the credit list goes to Credit Control. Another person signs it, once its
 *               figures still match the documents; the Internal Controller tests signed closes. A day with sales not
 *               prepared by the Settings' time the next day is told once.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class DailyCloseService {

    static final String ALERT_PREFIX = "DAILY_CLOSE:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<String> CHANNELS = Arrays.stream(OrderChannel.values()).map(Enum::name).toList();

    private final DailyCloseRepository repo;
    private final SalesInvoiceRepository invoiceRepo;
    private final SalesPaymentRepository paymentRepo;
    private final CustomerPaymentRepository customerPaymentRepo;
    private final CreditNoteRepository creditNoteRepo;
    private final TillSessionRepository tillRepo;
    private final CashDepositRepository depositRepo;
    private final EbmReceiptRepository receiptRepo;
    private final StockUnitRepository unitRepo;
    private final DeliveryNoteRepository noteRepo;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final AlertStateRepository alertRepo;
    private final Notifier notifier;
    private final Clock clock;

    public DailyCloseService(DailyCloseRepository repo, SalesInvoiceRepository invoiceRepo, SalesPaymentRepository paymentRepo,
                             CustomerPaymentRepository customerPaymentRepo, CreditNoteRepository creditNoteRepo, TillSessionRepository tillRepo,
                             CashDepositRepository depositRepo, EbmReceiptRepository receiptRepo, StockUnitRepository unitRepo,
                             DeliveryNoteRepository noteRepo, DocumentNumberService numbers, SettingService settingService,
                             AlertStateRepository alertRepo, Notifier notifier, Clock clock) {
        this.repo = repo;
        this.invoiceRepo = invoiceRepo;
        this.paymentRepo = paymentRepo;
        this.customerPaymentRepo = customerPaymentRepo;
        this.creditNoteRepo = creditNoteRepo;
        this.tillRepo = tillRepo;
        this.depositRepo = depositRepo;
        this.receiptRepo = receiptRepo;
        this.unitRepo = unitRepo;
        this.noteRepo = noteRepo;
        this.numbers = numbers;
        this.settingService = settingService;
        this.alertRepo = alertRepo;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** A salesperson's or a customer's sales of the day. */
    public record Party(String name, int invoices, BigDecimal total) {
    }

    /** Money taken that day on an earlier sale: a deposit's balance or a payment on an account. */
    public record Collection(String document, String link, String customer, PaymentMethod method, BigDecimal amount) {
    }

    /** Credit granted that day: on account, or a deposit's balance left to pay. */
    public record Credit(SalesInvoice invoice, BigDecimal amount) {
    }

    /** The glass on a vehicle now. */
    public record VanStock(String vehicle, long units, BigDecimal m2) {
    }

    /** The end-of-day sales report of a day (F-12). */
    public record Report(LocalDate day, List<SalesInvoice> invoices, List<DailyCloses.ChannelRow> channels, DailyCloses.ChannelRow totals,
                         List<Party> salespeople, List<Party> customers, List<Collection> collections, List<CreditNote> creditNotes,
                         BigDecimal cashRefunds, List<Credit> credits, List<TillSession> tills, List<TillSession> openTills,
                         List<CashDeposit> deposits, BigDecimal momoReceived, List<EbmReceipt> receipts, List<VanStock> vans,
                         long notesReleased, long ebmOverrides) {

        public BigDecimal getSalesTotal() {
            return totals.getTotal();
        }

        public BigDecimal getCollectionsTotal() {
            return sum(collections.stream().map(Collection::amount));
        }

        public Map<PaymentMethod, BigDecimal> getCollectionsByMethod() {
            Map<PaymentMethod, BigDecimal> by = new EnumMap<>(PaymentMethod.class);
            collections.forEach(c -> by.merge(c.method(), c.amount(), BigDecimal::add));
            return by;
        }

        public BigDecimal getRefundsTotal() {
            return sum(creditNotes.stream().map(CreditNote::getTotalAmount));
        }

        public BigDecimal getCreditGranted() {
            return sum(credits.stream().map(Credit::amount));
        }

        public BigDecimal getCashExpected() {
            return sum(tills.stream().map(TillSession::getExpectedCash));
        }

        public BigDecimal getCashCounted() {
            return sum(tills.stream().map(TillSession::getCountedCash));
        }

        public BigDecimal getCashVariance() {
            return DailyCloses.variance(getCashExpected(), getCashCounted());
        }

        public BigDecimal getCashToBank() {
            return sum(tills.stream().map(TillSession::getCashToBank));
        }

        public BigDecimal getBanked() {
            return sum(deposits.stream().map(CashDeposit::getAmount));
        }

        public int getEbmCount() {
            return receipts.size();
        }

        public Long getEbmFirst() {
            return receipts.stream().map(EbmReceipt::getInvcNo).min(Long::compare).orElse(null);
        }

        public Long getEbmLast() {
            return receipts.stream().map(EbmReceipt::getInvcNo).max(Long::compare).orElse(null);
        }

        public int getEbmUnsigned() {
            return (int) receipts.stream().filter(r -> !r.isSigned()).count();
        }

        public long getEbmRefunds() {
            return receipts.stream().filter(EbmReceipt::isRefund).count();
        }

        /** Something happened that day: a sale, a till closed, money taken or refunded. */
        public boolean isActive() {
            return !invoices.isEmpty() || !tills.isEmpty() || !collections.isEmpty() || !creditNotes.isEmpty();
        }

        private static BigDecimal sum(java.util.stream.Stream<BigDecimal> values) {
            return values.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    // ---------------------------------------------------------------- reading

    public Report report(LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay().minusNanos(1);
        List<SalesInvoice> invoices = invoiceRepo.findByStatusAndInvoiceDateOrderByNumber(SalesInvoiceStatus.POSTED, day);
        Map<UUID, List<SalesPayment>> paid = invoices.isEmpty() ? Map.of()
                : paymentRepo.findByInvoiceIdIn(invoices.stream().map(SalesInvoice::getId).toList()).stream()
                .filter(p -> !p.isBalancePayment()).collect(Collectors.groupingBy(SalesPayment::getInvoiceId));
        List<DailyCloses.Sale> sales = new ArrayList<>();
        List<Credit> credits = new ArrayList<>();
        BigDecimal momo = BigDecimal.ZERO;
        Map<String, Party> salespeople = new LinkedHashMap<>();
        Map<String, Party> customers = new LinkedHashMap<>();
        for (SalesInvoice inv : invoices) {
            Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
            paid.getOrDefault(inv.getId(), List.of()).forEach(p -> byMethod.merge(p.getMethod(), p.getAmount(), BigDecimal::add));
            BigDecimal atIssue = byMethod.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal balance = inv.getTotalAmount().subtract(atIssue).max(BigDecimal.ZERO);
            sales.add(new DailyCloses.Sale(channelOf(inv), byMethod, balance));
            BigDecimal credit = byMethod.getOrDefault(PaymentMethod.CREDIT, BigDecimal.ZERO).add(balance);
            if (credit.signum() > 0) {
                credits.add(new Credit(inv, credit));
            }
            momo = momo.add(byMethod.getOrDefault(PaymentMethod.MOBILE_MONEY, BigDecimal.ZERO));
            String seller = inv.getSalesperson() != null ? inv.getSalesperson() : inv.getPostedBy();
            salespeople.merge(seller, new Party(seller, 1, inv.getTotalAmount()), DailyCloseService::plus);
            String customer = inv.getCustomer().getName();
            customers.merge(customer, new Party(customer, 1, inv.getTotalAmount()), DailyCloseService::plus);
        }
        List<DailyCloses.ChannelRow> channels = DailyCloses.byChannel(sales, CHANNELS);

        List<Collection> collections = new ArrayList<>();
        List<SalesPayment> balances = paymentRepo.findByBalancePaymentTrueAndCreatedAtBetweenOrderByCreatedAtAsc(from, to);
        Map<UUID, SalesInvoice> balanceInvoices = balances.isEmpty() ? Map.of()
                : invoiceRepo.findByIdIn(balances.stream().map(SalesPayment::getInvoiceId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesInvoice::getId, i -> i));
        for (SalesPayment p : balances) {
            SalesInvoice inv = balanceInvoices.get(p.getInvoiceId());
            collections.add(new Collection(inv == null ? "" : inv.getNumber(), "/invoices/" + p.getInvoiceId(),
                    inv == null ? "" : inv.getCustomer().getName(), p.getMethod(), p.getAmount()));
        }
        for (CustomerPayment p : customerPaymentRepo.findByPaymentDateOrderByNumber(day)) {
            collections.add(new Collection(p.getNumber(), "/customer-payments/" + p.getId(), p.getCustomer().getName(), p.getMethod(), p.getAmount()));
        }
        momo = momo.add(collections.stream().filter(c -> c.method() == PaymentMethod.MOBILE_MONEY).map(Collection::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        List<CreditNote> creditNotes = creditNoteRepo.findByCreditDateOrderByNumber(day);
        BigDecimal cashRefunds = creditNotes.stream().filter(c -> c.getRefundMethod() == PaymentMethod.CASH && c.getRefundAmount() != null)
                .map(CreditNote::getRefundAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<TillSession> tills = tillRepo.findByClosedAtBetweenOrderByClosedAtAsc(from, to);
        List<TillSession> open = tillRepo.findByStatusAndOpenedAtBeforeOrderByOpenedAtAsc(TillStatus.OPEN, to);
        List<VanStock> vans = unitRepo.countByLocation(StockStatus.ON_VEHICLE).stream()
                .map(r -> new VanStock((String) r[0], ((Number) r[1]).longValue(),
                        BigDecimal.valueOf(((Number) r[2]).longValue()).divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP)))
                .toList();
        List<DeliveryNote> released = noteRepo.findByStatusAndReleasedAtBetween(DeliveryNoteStatus.RELEASED, from, to);
        return new Report(day, invoices, channels, DailyCloses.total(channels), sorted(salespeople), sorted(customers), collections,
                creditNotes, cashRefunds, credits, tills, open, depositRepo.findByDepositDateOrderByNumber(day), momo,
                receiptRepo.findByCreatedAtBetweenOrderByInvcNoAsc(from, to), vans, released.size(),
                released.stream().filter(n -> n.getEbmOverrideReason() != null).count());
    }

    public Optional<DailyClose> findByDay(LocalDate day) {
        return repo.findByBusinessDay(day);
    }

    public DailyClose findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("DailyClose", id));
    }

    /** The closes, newest day first: all, PREPARED (to sign), SIGNED, or "untested" (signed, not tested yet). */
    public Page<DailyClose> findPage(String filter, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "businessDay"));
        if ("untested".equals(filter)) {
            return repo.findByStatusAndTestedAtIsNull(DailyCloseStatus.SIGNED, request);
        }
        for (DailyCloseStatus s : DailyCloseStatus.values()) {
            if (s.name().equals(filter)) {
                return repo.findByStatus(s, request);
            }
        }
        return repo.findAll(request);
    }

    /** Days of the last week that had sales and have no close yet, newest first (the list page's reminder). */
    public List<LocalDate> daysToClose() {
        LocalDate last = LocalDate.now(clock).minusDays(1);
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = last.minusDays(i);
            if (repo.findByBusinessDay(day).isEmpty() && active(day)) {
                days.add(day);
            }
        }
        return days;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public static boolean isPreparer(DailyClose close) {
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        return me != null && me.equals(close.getPreparedById());
    }

    // ---------------------------------------------------------------- preparing, signing, testing

    /**
     * Prepares the day's close (or prepares it again while it waits for its signature) with the MoMo merchant statement's
     * total (needed when MoMo came in) and the explanation of any cash or MoMo variance. Refused for a day to come, while
     * a till of the day is still open, or once signed.
     */
    @Transactional
    public DailyClose prepare(LocalDate day, BigDecimal momoStatement, String varianceNote) {
        LocalDate today = today();
        if (day == null || day.isAfter(today)) {
            throw BusinessException.onField("day", "dailyClose.day.invalid");
        }
        Optional<DailyClose> existing = repo.findByBusinessDay(day);
        DailyClose close = existing.flatMap(c -> repo.lockById(c.getId())).orElseGet(DailyClose::new);
        if (close.isSigned()) {
            throw BusinessException.of("dailyClose.signed", day.format(DAY), close.getSignedBy());
        }
        Report report = report(day);
        if (!report.openTills().isEmpty()) {
            throw BusinessException.of("dailyClose.tillsOpen", report.openTills().get(0).getNumber(), report.openTills().get(0).getCashierUsername());
        }
        if (momoStatement != null && (momoStatement.signum() < 0 || momoStatement.stripTrailingZeros().scale() > 2)) {
            throw BusinessException.onField("momoStatement", "dailyClose.momo.invalid");
        }
        if (momoStatement == null && report.momoReceived().signum() != 0) {
            throw BusinessException.onField("momoStatement", "dailyClose.momo.required", CashDepositService.rwf(report.momoReceived()));
        }
        BigDecimal momoVariance = momoStatement == null ? BigDecimal.ZERO : DailyCloses.variance(report.momoReceived(), momoStatement);
        BigDecimal cashVariance = report.getCashVariance();
        String note = PartyRules.clean(varianceNote);
        boolean variance = momoVariance.signum() != 0 || cashVariance.signum() != 0;
        if (variance && note == null) {
            throw BusinessException.onField("varianceNote", "dailyClose.note.required", CashDepositService.rwf(cashVariance),
                    CashDepositService.rwf(momoVariance));
        }
        if (note != null && note.length() > 500) {
            throw BusinessException.onField("varianceNote", "dailyClose.note.size");
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (close.getNumber() == null) {
            close.setNumber(numbers.next(DocumentType.DAILY_CLOSE));
            close.setBusinessDay(day);
        }
        DailyCloses.ChannelRow t = report.totals();
        close.setInvoices(report.invoices().size());
        close.setSalesTotal(t.getTotal());
        close.setCashSales(t.cash());
        close.setMomoSales(t.momo());
        close.setOtherSales(t.other());
        close.setCreditSales(t.credit());
        close.setCollections(report.getCollectionsTotal());
        close.setRefunds(report.getRefundsTotal());
        close.setCashExpected(report.getCashExpected());
        close.setCashCounted(report.getCashCounted());
        close.setCashVariance(cashVariance);
        close.setCashToBank(report.getCashToBank());
        close.setMomoReceived(report.momoReceived());
        close.setMomoStatement(momoStatement == null ? null : momoStatement.setScale(2));
        close.setMomoVariance(momoVariance);
        close.setEbmReceipts(report.getEbmCount());
        close.setEbmFirst(report.getEbmFirst());
        close.setEbmLast(report.getEbmLast());
        close.setEbmUnsigned(report.getEbmUnsigned());
        close.setVarianceNote(note);
        close.setPreparedBy(user.getUsername());
        close.setPreparedById(user.getId());
        close.setPreparedAt(LocalDateTime.now(clock));
        close.setStatus(DailyCloseStatus.PREPARED);
        repo.save(close);
        AlertOnce.clear(alertRepo, ALERT_PREFIX + day, LocalDateTime.now(clock));
        String link = "/daily-close/" + day;
        if (variance) {
            notifier.holders("ALERT_DAILY_CLOSE", user.getId(), NotificationKind.CLOSE, "notify.close.variance", "notify.close.varianceText",
                    link, day.format(DAY), CashDepositService.rwf(cashVariance), CashDepositService.rwf(momoVariance), note);
        }
        notifier.holders("SIGN_DAILY_CLOSE", user.getId(), NotificationKind.CLOSE, "notify.close.prepared",
                "notify.close.preparedText", link, day.format(DAY), user.getUsername());
        if (report.getCreditGranted().signum() > 0) {
            notifier.holders("ALERT_CREDIT_LIST", user.getId(), NotificationKind.CLOSE, "notify.close.creditList", "notify.close.creditListText",
                    link + "#credit", day.format(DAY), String.valueOf(report.credits().size()), CashDepositService.rwf(report.getCreditGranted()));
        }
        return close;
    }

    /**
     * Signs a prepared close (the Director of Finance): never who prepared it, and only while its figures still match the
     * day's documents (a sale synced or a till closed since asks for it to be prepared again).
     */
    @Transactional
    public DailyClose sign(UUID id, String note) {
        DailyClose close = repo.lockById(id).orElseThrow(() -> new NotFoundException("DailyClose", id));
        if (close.isSigned()) {
            throw BusinessException.of("dailyClose.signed", close.getBusinessDay().format(DAY), close.getSignedBy());
        }
        if (isPreparer(close)) {
            throw BusinessException.of("dailyClose.ownSign");
        }
        Report report = report(close.getBusinessDay());
        if (report.invoices().size() != close.getInvoices() || report.getSalesTotal().compareTo(close.getSalesTotal()) != 0
                || report.getCashCounted().compareTo(close.getCashCounted()) != 0 || report.momoReceived().compareTo(close.getMomoReceived()) != 0
                || report.getCollectionsTotal().compareTo(close.getCollections()) != 0 || report.getEbmCount() != close.getEbmReceipts()) {
            throw BusinessException.of("dailyClose.changed", close.getBusinessDay().format(DAY));
        }
        String clean = PartyRules.clean(note);
        if (clean != null && clean.length() > 255) {
            throw BusinessException.onField("signNote", "dailyClose.signNote.size");
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        close.setSignNote(clean);
        close.setSignedBy(user.getUsername());
        close.setSignedById(user.getId());
        close.setSignedAt(LocalDateTime.now(clock));
        close.setStatus(DailyCloseStatus.SIGNED);
        return close;
    }

    /** Records the Internal Controller's test of a signed close (BP 7.1 rule 5: tested weekly): what was checked, once, by a third person. */
    @Transactional
    public DailyClose test(UUID id, String note) {
        DailyClose close = repo.lockById(id).orElseThrow(() -> new NotFoundException("DailyClose", id));
        if (!close.isSigned()) {
            throw BusinessException.of("dailyClose.test.notSigned", close.getBusinessDay().format(DAY));
        }
        if (close.isTested()) {
            throw BusinessException.of("dailyClose.test.done", close.getBusinessDay().format(DAY), close.getTestedBy());
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (user.getId().equals(close.getPreparedById()) || user.getId().equals(close.getSignedById())) {
            throw BusinessException.of("dailyClose.test.own");
        }
        String clean = PartyRules.clean(note);
        if (clean == null || clean.length() > 500) {
            throw BusinessException.onField("testNote", "dailyClose.test.note");
        }
        close.setTestNote(clean);
        close.setTestedBy(user.getUsername());
        close.setTestedById(user.getId());
        close.setTestedAt(LocalDateTime.now(clock));
        return close;
    }

    /**
     * Tells ALERT_DAILY_CLOSE holders once per day of the last week with sales or tills that has no close once the
     * Settings' time of the next day has passed (BP 5.3: the daily cash report by 10:00); how many were told.
     */
    @Scheduled(fixedDelayString = "${app.alerts.close-every:PT30M}", initialDelayString = "${app.alerts.first-check:PT1M}")
    @Transactional
    public int checkLate() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalTime by = ReportCalendar.time(settingService.get(SettingKey.CLOSE_DAILY_REPORT_BY), LocalTime.of(10, 0));
        LocalDate last = ReportCalendar.lastDueClose(now, by);
        int count = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate day = last.minusDays(i);
            if (repo.findByBusinessDay(day).isPresent() || !active(day) || !AlertOnce.raise(alertRepo, ALERT_PREFIX + day, now)) {
                continue;
            }
            notifier.holders("ALERT_DAILY_CLOSE", null, NotificationKind.CLOSE, "notify.close.late", "notify.close.lateText",
                    "/daily-close/" + day, day.format(DAY), by.toString());
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- helpers

    private boolean active(LocalDate day) {
        return !invoiceRepo.findByStatusAndInvoiceDateOrderByNumber(SalesInvoiceStatus.POSTED, day).isEmpty()
                || !tillRepo.findByClosedAtBetweenOrderByClosedAtAsc(day.atStartOfDay(), day.plusDays(1).atStartOfDay().minusNanos(1)).isEmpty();
    }

    /** An invoice's channel: its order's, else a vehicle's sale is FIELD and the till's COUNTER. */
    static String channelOf(SalesInvoice invoice) {
        if (invoice.getOrderChannel() != null) {
            return invoice.getOrderChannel().name();
        }
        return invoice.getChannel() == SaleChannel.MOBILE ? OrderChannel.FIELD.name() : OrderChannel.COUNTER.name();
    }

    private static Party plus(Party a, Party b) {
        return new Party(a.name(), a.invoices() + b.invoices(), a.total().add(b.total()));
    }

    private static List<Party> sorted(Map<String, Party> parties) {
        return parties.values().stream().sorted(Comparator.comparing(Party::total).reversed().thenComparing(Party::name)).toList();
    }
}
