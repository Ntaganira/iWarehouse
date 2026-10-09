package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CuttingJobDto;
import com.ntaganira.heritier.iWarehouse.dto.QuotationDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Counter sales (POS-01, POS-03..POS-06, POS-10, TAX-01, TAX-04): ringing up units from stock, pricing them from the
 * customer's list, quotations rung up at their prices, changing a price or giving credit beyond the limits with a manager's
 * approval, paying with split payments, and the till session around it.
 */
class CounterSalesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    private final List<StockUnit> units = new ArrayList<>();
    private final List<SalesInvoice> invoices = new ArrayList<>();
    private final List<SalesPayment> payments = new ArrayList<>();
    private final List<Object[]> adjustmentHolds = new ArrayList<>();
    private TillSessionRepository tillRepo;
    private SalesInvoiceRepository invoiceRepo;
    private StockMovementRepository movementRepo;
    private PostingService postings;
    private JournalService journals;
    private PriceListService priceLists;
    private TillService tills;
    private SalesService sales;
    private DocumentNumberService numbers;
    private CuttingJobService cuttingJobs;
    private CuttingJobRepository jobRepo;
    private CuttingJobOutputRepository outputRepo;
    private StockUnitRepository unitRepo;
    private final List<SalesDelivery> deliveries = new ArrayList<>();
    private final List<CuttingJobDto> jobRequests = new ArrayList<>();
    private final List<CuttingJob> jobs = new ArrayList<>();
    private final List<CuttingJobOutput> outputs = new ArrayList<>();
    private final List<SaleApproval> approvals = new ArrayList<>();
    private final Map<Long, List<Role>> rolesOf = new HashMap<>();
    private SaleApprovalService approvalService;
    private final List<Quotation> quotations = new ArrayList<>();
    private QuotationService quotationService;
    private ProcessingService edging;
    private ProcessingService drilling;
    private ProcessingService polishing;
    private Product tempered;
    private TillSession till;
    private Customer walkIn;
    private Customer builders;
    private PriceList retail;
    private PriceList contractor;
    private Product clear6;
    private Product exemptGlass;
    private int invoiceNo = 1;

    @BeforeEach
    void setUp() {
        signIn(5L, "cashier1");
        TaxCategory standard = tax("B", "18.00");
        TaxCategory exempt = tax("A", "0.00");
        clear6 = product("CLR-6", standard);
        clear6.setGlassType(GlassType.CLEAR);
        exemptGlass = product("EXP-4", exempt);
        exemptGlass.setGlassType(GlassType.CLEAR);
        tempered = product("TMP-8", standard);
        tempered.setGlassType(GlassType.TEMPERED);
        edging = service("EDGE", "Edging", ChargeUnit.METRE);
        drilling = service("DRILL", "Drilling", ChargeUnit.HOLE);
        polishing = service("POLISH", "Polishing", ChargeUnit.M2);
        retail = priceList("RETAIL", true);
        contractor = priceList("CONTRACTOR", false);
        walkIn = customer("WALK-IN", CustomerType.WALK_IN, "0", null, null);
        builders = customer("Umucyo Builders", CustomerType.CONTRACTOR, "500000", contractor, "100123456");

        tillRepo = mock(TillSessionRepository.class);
        invoiceRepo = mock(SalesInvoiceRepository.class);
        SalesPaymentRepository paymentRepo = mock(SalesPaymentRepository.class);
        unitRepo = mock(StockUnitRepository.class);
        movementRepo = mock(StockMovementRepository.class);
        StockAdjustmentLineRepository adjustmentLineRepo = mock(StockAdjustmentLineRepository.class);
        SalesInvoiceLineRepository saleLineRepo = mock(SalesInvoiceLineRepository.class);
        CustomerRepository customerRepo = mock(CustomerRepository.class);
        CurrencyRepository currencyRepo = mock(CurrencyRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        numbers = mock(DocumentNumberService.class);
        postings = mock(PostingService.class);
        journals = mock(JournalService.class);
        priceLists = mock(PriceListService.class);

        Currency rwf = new Currency();
        rwf.setCode("RWF");
        rwf.setDecimals(0);
        when(currencyRepo.findByBaseCurrencyTrue()).thenReturn(Optional.of(rwf));
        when(customerRepo.findByDefaultCustomerTrue()).thenReturn(Optional.of(walkIn));
        when(customerRepo.findById(walkIn.getId())).thenReturn(Optional.of(walkIn));
        when(customerRepo.findById(builders.getId())).thenReturn(Optional.of(builders));
        when(productRepo.lockAllById(any())).thenReturn(List.of(clear6, exemptGlass));
        when(unitRepo.findByCodeIgnoreCase(any())).thenAnswer(a -> units.stream()
                .filter(u -> u.getCode().equalsIgnoreCase(a.getArgument(0))).findFirst());
        when(unitRepo.findById(any())).thenAnswer(a -> units.stream().filter(u -> u.getId().equals(a.getArgument(0))).findFirst());
        when(unitRepo.findAllById(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(unitRepo.findByIdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return units.stream().filter(u -> ids.contains(u.getId())).toList();
        });
        when(adjustmentLineRepo.findHolds(any(), any())).thenAnswer(a -> adjustmentHolds);
        // A sale being rung up holds its units: (unit id, till number)
        when(saleLineRepo.findHolds(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            List<Object[]> rows = new ArrayList<>();
            for (SalesInvoice i : invoices) {
                if (i.isDraft()) {
                    i.getLines().stream().filter(l -> l.getStockUnitId() != null && ids.contains(l.getStockUnitId()))
                            .forEach(l -> rows.add(new Object[]{l.getStockUnitId(), i.getTillSession().getNumber()}));
                }
            }
            return rows;
        });
        when(invoiceRepo.save(any())).thenAnswer(a -> {
            SalesInvoice i = a.getArgument(0);
            if (i.getId() == null) {
                i.setId(UUID.randomUUID());
                invoices.add(i);
            }
            return i;
        });
        when(invoiceRepo.findFirstByTillSession_IdAndStatus(any(), eq(SalesInvoiceStatus.DRAFT))).thenAnswer(a -> invoices.stream()
                .filter(i -> i.isDraft() && i.getTillSession().getId().equals(a.getArgument(0))).findFirst());
        when(paymentRepo.save(any())).thenAnswer(a -> {
            payments.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(paymentRepo.totalsOfSession(any())).thenAnswer(a -> {
            Map<PaymentMethod, BigDecimal> sums = new EnumMap<>(PaymentMethod.class);
            payments.forEach(p -> sums.merge(p.getMethod(), p.getAmount(), BigDecimal::add));
            return sums.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).toList();
        });
        when(tillRepo.save(any())).thenAnswer(a -> {
            TillSession t = a.getArgument(0);
            t.setId(UUID.randomUUID());
            till = t;
            return t;
        });
        when(tillRepo.findByCashierIdAndStatus(any(), eq(TillStatus.OPEN)))
                .thenAnswer(a -> Optional.ofNullable(till).filter(t -> t.isOpen() && t.getCashierId().equals(a.getArgument(0))));
        when(tillRepo.lockById(any())).thenAnswer(a -> Optional.ofNullable(till).filter(t -> t.getId().equals(a.getArgument(0))));
        when(numbers.next(DocumentType.TILL_SESSION)).thenReturn("TILL-WH-2026-000001");
        when(numbers.next(DocumentType.INVOICE)).thenAnswer(a -> String.format("INV-WH-2026-%06d", invoiceNo++));
        when(priceLists.priceFor(any(), any())).thenAnswer(a -> {
            Customer c = a.getArgument(0);
            Product p = a.getArgument(1);
            PriceList list = c.getPriceList() != null ? c.getPriceList() : retail;
            BigDecimal price = list == retail ? new BigDecimal("27000") : new BigDecimal("22881.36");
            return p == clear6 || p == exemptGlass ? Optional.of(new PriceListService.UnitPrice(price, list, false)) : Optional.empty();
        });
        when(priceLists.minChargeableArea(any())).thenReturn(new BigDecimal("0.25"));
        when(journals.receivable(any())).thenReturn(BigDecimal.ZERO);

        StockService stockService = new StockService(unitRepo, movementRepo, mock(StockCostEntryRepository.class), adjustmentLineRepo,
                mock(StockCountRepository.class), saleLineRepo, mock(LocationRepository.class), numbers, CLOCK);
        tills = new TillService(tillRepo, invoiceRepo, paymentRepo, postings, numbers, CLOCK);
        ProcessingServiceRepository serviceRepo = mock(ProcessingServiceRepository.class);
        when(serviceRepo.findAllById(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return List.of(edging, drilling, polishing).stream().filter(sv -> ids.contains(sv.getId())).toList();
        });
        TaxCategoryRepository taxRepo = mock(TaxCategoryRepository.class);
        when(taxRepo.findByDefaultCategoryTrue()).thenReturn(Optional.of(standard));
        when(productRepo.findById(any())).thenAnswer(a -> List.of(clear6, exemptGlass, tempered).stream()
                .filter(pr -> pr.getId().equals(a.getArgument(0))).findFirst());
        // Edging 1,500/m and drilling 500/hole on every list; polishing is priced nowhere
        when(priceLists.servicePriceFor(any(), any())).thenAnswer(a -> {
            ProcessingService sv = a.getArgument(1);
            return sv == polishing ? Optional.empty()
                    : Optional.of(new PriceListService.ServicePriceFor(sv == edging ? new BigDecimal("1500") : new BigDecimal("500"), retail));
        });
        jobRepo = mock(CuttingJobRepository.class);
        outputRepo = mock(CuttingJobOutputRepository.class);
        SalesDeliveryRepository deliveryRepo = mock(SalesDeliveryRepository.class);
        when(deliveryRepo.save(any())).thenAnswer(a -> {
            deliveries.add(a.getArgument(0));
            return a.getArgument(0);
        });
        when(deliveryRepo.findByInvoiceIdOrderByDeliveredAtAscUnitCodeAsc(any())).thenAnswer(a -> deliveries.stream()
                .filter(d -> d.getInvoiceId().equals(a.getArgument(0))).toList());
        // The jobs made for sales, their lines and what they cut
        when(jobRepo.findBySalesInvoiceIdOrderByNumberAsc(any())).thenAnswer(a -> jobs.stream()
                .filter(j -> a.getArgument(0).equals(j.getSalesInvoiceId())).toList());
        when(jobRepo.findById(any())).thenAnswer(a -> jobs.stream().filter(j -> j.getId().equals(a.getArgument(0))).findFirst());
        CuttingJobLineRepository jobLineRepo = mock(CuttingJobLineRepository.class);
        when(jobLineRepo.findByJob_IdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return jobs.stream().filter(j -> ids.contains(j.getId())).flatMap(j -> j.getLines().stream()).toList();
        });
        when(outputRepo.findByCuttingJobIdIn(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return outputs.stream().filter(o -> ids.contains(o.getCuttingJobId())).toList();
        });
        when(outputRepo.findByStockUnitId(any())).thenAnswer(a -> outputs.stream()
                .filter(o -> a.getArgument(0).equals(o.getStockUnitId())).findFirst());
        when(unitRepo.findByCodeIn(any())).thenAnswer(a -> {
            Collection<String> codes = a.getArgument(0);
            return units.stream().filter(u -> codes.contains(u.getCode())).toList();
        });
        when(invoiceRepo.lockById(any())).thenAnswer(a -> invoices.stream().filter(i -> i.getId().equals(a.getArgument(0))).findFirst());
        when(invoiceRepo.findDetailedById(any())).thenAnswer(a -> invoices.stream().filter(i -> i.getId().equals(a.getArgument(0))).findFirst());
        // The cutting job made for a sale: what was asked, and a job with those lines
        cuttingJobs = mock(CuttingJobService.class);
        when(cuttingJobs.create(any())).thenAnswer(a -> {
            CuttingJobDto dto = a.getArgument(0);
            jobRequests.add(dto);
            CuttingJob job = new CuttingJob();
            job.setId(UUID.randomUUID());
            job.setNumber(String.format("CUT-WH-2026-%06d", jobRequests.size() + 10));
            jobs.add(job);
            for (CuttingJobDto.Line row : dto.getLines()) {
                CuttingJobLine line = new CuttingJobLine();
                line.setId(UUID.randomUUID());
                line.setJob(job);
                line.setWidthMm(row.getWidthMm());
                line.setHeightMm(row.getHeightMm());
                line.setQuantity(row.getQuantity());
                job.getLines().add(line);
            }
            return job;
        });
        // Approval requests (POS-05, POS-06); the cashier's role takes the Settings limit (5%), the owner's gives any discount
        SaleApprovalRepository approvalRepo = mock(SaleApprovalRepository.class);
        when(approvalRepo.save(any())).thenAnswer(a -> {
            SaleApproval r = a.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
                r.setCreatedAt(LocalDateTime.now(CLOCK));
                approvals.add(r);
            }
            return r;
        });
        when(approvalRepo.findByInvoice_IdOrderByNumberAsc(any())).thenAnswer(a -> approvals.stream()
                .filter(r -> r.getInvoice().getId().equals(a.getArgument(0))).toList());
        when(approvalRepo.findByInvoice_IdAndStatusIn(any(), any())).thenAnswer(a -> {
            Collection<SaleApprovalStatus> statuses = a.getArgument(1);
            return approvals.stream().filter(r -> r.getInvoice().getId().equals(a.getArgument(0)) && statuses.contains(r.getStatus())).toList();
        });
        when(approvalRepo.lockById(any())).thenAnswer(a -> approvals.stream().filter(r -> r.getId().equals(a.getArgument(0))).findFirst());
        when(approvalRepo.findDetailedById(any())).thenAnswer(a -> approvals.stream().filter(r -> r.getId().equals(a.getArgument(0))).findFirst());
        when(approvalRepo.tillOf(any())).thenAnswer(a -> approvals.stream().filter(r -> r.getId().equals(a.getArgument(0))).findFirst()
                .map(r -> r.getInvoice().getTillSession().getId()));
        when(numbers.next(DocumentType.SALE_APPROVAL)).thenAnswer(a -> String.format("APR-WH-2026-%06d", approvals.size() + 1));
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(any())).thenAnswer(a -> Optional.of(User.builder().id(a.getArgument(0))
                .roles(new HashSet<>(rolesOf.getOrDefault((Long) a.getArgument(0), List.of()))).build()));
        rolesOf.put(5L, List.of(role("CASHIER", null)));
        rolesOf.put(6L, List.of(role("CASHIER", null)));
        rolesOf.put(9L, List.of(role("OWNER", "100")));
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.DISCOUNT_APPROVAL_PERCENT)).thenReturn(new BigDecimal("5"));

        // Quotations (POS-03), valid 14 days by default; the units a quotation's whole sheets can take
        QuotationRepository quotationRepo = mock(QuotationRepository.class);
        when(quotationRepo.save(any())).thenAnswer(a -> {
            Quotation q = a.getArgument(0);
            if (q.getId() == null) {
                q.setId(UUID.randomUUID());
                quotations.add(q);
            }
            q.getLines().forEach(l -> { if (l.getId() == null) l.setId(UUID.randomUUID()); });
            return q;
        });
        when(quotationRepo.findById(any())).thenAnswer(a -> quotations.stream().filter(q -> q.getId().equals(a.getArgument(0))).findFirst());
        when(quotationRepo.findDetailedById(any())).thenAnswer(a -> quotations.stream().filter(q -> q.getId().equals(a.getArgument(0))).findFirst());
        when(quotationRepo.lockById(any())).thenAnswer(a -> quotations.stream().filter(q -> q.getId().equals(a.getArgument(0))).findFirst());
        when(numbers.next(DocumentType.QUOTATION)).thenAnswer(a -> String.format("QUO-WH-2026-%06d", quotations.size() + 1));
        when(settings.getInt(SettingKey.QUOTATION_VALIDITY_DAYS)).thenReturn(14);
        when(invoiceRepo.findFirstByQuotationIdAndStatus(any(), any())).thenAnswer(a -> invoices.stream()
                .filter(i -> a.getArgument(0).equals(i.getQuotationId()) && i.getStatus() == a.getArgument(1)).findFirst());
        when(productRepo.findAllById(any())).thenAnswer(a -> {
            Collection<UUID> ids = a.getArgument(0);
            return List.of(clear6, exemptGlass, tempered).stream().filter(pr -> ids.contains(pr.getId())).toList();
        });
        when(unitRepo.findOfSize(any(), anyInt(), anyInt(), any())).thenAnswer(a -> units.stream()
                .filter(u -> u.getProduct().getId().equals(a.getArgument(0)) && u.getStatus() == a.getArgument(3)
                        && ((u.getWidthMm() == (int) a.getArgument(1) && u.getHeightMm() == (int) a.getArgument(2))
                        || (u.getWidthMm() == (int) a.getArgument(2) && u.getHeightMm() == (int) a.getArgument(1))))
                .sorted(Comparator.comparing(StockUnit::getCode)).toList());

        LinePricing pricing = new LinePricing(priceLists, taxRepo, currencyRepo);
        sales = new SalesService(invoiceRepo, paymentRepo, unitRepo, productRepo, customerRepo, serviceRepo, jobRepo, outputRepo,
                jobLineRepo, deliveryRepo, approvalRepo, userRepo, quotationRepo, tills, cuttingJobs, stockService, pricing, postings,
                journals, numbers, settings, CLOCK);
        approvalService = new SaleApprovalService(approvalRepo, invoiceRepo, tillRepo, sales, CLOCK);
        quotationService = new QuotationService(quotationRepo, customerRepo, productRepo, serviceRepo, invoiceRepo, pricing, sales,
                numbers, settings, CLOCK);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- till sessions (POS-10)

    @Test
    void aTillOpensOnceWithItsFloatAndClosesWithTheCashCounted() {
        TillSession session = tills.open(new BigDecimal("50000"));

        assertThat(session.getNumber()).isEqualTo("TILL-WH-2026-000001");
        assertThat(session.getCashierUsername()).isEqualTo("cashier1");
        assertThat(session.getOpenedAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 0));
        verify(postings).tillOpened(session);
        assertThatThrownBy(() -> tills.open(BigDecimal.ZERO)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("till.alreadyOpen"));

        payment(PaymentMethod.CASH, "95008");
        payment(PaymentMethod.MOBILE_MONEY, "100000");
        assertThat(tills.summary(session).getExpectedCash()).isEqualByComparingTo("145008");

        assertThatThrownBy(() -> tills.close(session.getId(), new BigDecimal("144008"), " "))   // 1,000 short, no reason
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("note"));
        tills.close(session.getId(), new BigDecimal("144008"), "Change given twice");

        assertThat(session.getStatus()).isEqualTo(TillStatus.CLOSED);
        assertThat(session.getCashSales()).isEqualByComparingTo("95008");
        assertThat(session.getExpectedCash()).isEqualByComparingTo("145008");
        assertThat(session.getDifference()).isEqualByComparingTo("-1000");
        assertThat(session.getCloseNote()).isEqualTo("Change given twice");
        verify(postings).tillClosed(session);
    }

    @Test
    void aTillDoesNotCloseWhileASaleIsRungUpInItNorForSomeoneElse() {
        TillSession session = tills.open(new BigDecimal("0"));
        unit("U-WH-000060", clear6, 2000, 1000, StockStatus.AVAILABLE, null);
        sales.addUnit("U-WH-000060", null);

        assertThatThrownBy(() -> tills.close(session.getId(), BigDecimal.ZERO, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("till.close.saleOpen"));
        signIn(6L, "cashier2");
        assertThatThrownBy(() -> tills.close(session.getId(), BigDecimal.ZERO, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("till.notYours"));
        assertThatThrownBy(() -> sales.addUnit("U-WH-000060", null))                      // no till of their own
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("till.notOpen"));
    }

    // ---------------------------------------------------------------- ringing up (POS-01, TAX-01)

    @Test
    void aScannedUnitIsPricedFromTheCustomersListOverItsChargeableAreaAndHeld() {
        tills.open(BigDecimal.ZERO);
        StockUnit sheet = unit("U-WH-000002", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        unit("U-WH-000061", clear6, 400, 300, StockStatus.AVAILABLE, null);

        SalesInvoice sale = sales.addUnit(" u-wh-000002 ", null);
        sales.addUnit("U-WH-000061", null);

        assertThat(sale.getCustomer()).isEqualTo(walkIn);                                 // a new sale starts as walk-in
        SalesInvoiceLine line = sale.getLines().get(0);
        assertThat(line.getUnitCode()).isEqualTo("U-WH-000002");
        assertThat(line.getChargeableAreaM2()).isEqualByComparingTo("7.2225");
        assertThat(line.getPriceList()).isEqualTo(retail);
        assertThat(line.getTaxCode()).isEqualTo("B");
        assertThat(line.getAmount()).isEqualByComparingTo("195008");                      // 27,000 x 7.2225, VAT included
        assertThat(sale.getLines().get(1).getChargeableAreaM2()).isEqualByComparingTo("0.25");   // 0.12 m² charged as 0.25
        assertThat(sale.getLines().get(1).getAmount()).isEqualByComparingTo("6750");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);                   // held, not sold yet
        assertThat(sales.totals(sale).gross()).isEqualByComparingTo("201758");

        assertThatThrownBy(() -> sales.addUnit("U-WH-000002", null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.unit.already"));
        assertThatThrownBy(() -> sales.addUnit("U-WH-999999", null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.unit.unknown"));
    }

    @Test
    void unitsThatCannotBeSoldAreRefusedWithTheReason() {
        tills.open(BigDecimal.ZERO);
        unit("U-WH-000003", clear6, 1000, 1000, StockStatus.SOLD, null);
        unit("U-WH-000004", clear6, 1000, 1000, StockStatus.RESERVED, builders);
        StockUnit held = unit("U-WH-000005", clear6, 1000, 1000, StockStatus.AVAILABLE, null);
        unit("U-WH-000006", product("NOP-8", tax("B", "18.00")), 1000, 1000, StockStatus.AVAILABLE, null);
        adjustmentHolds.add(new Object[]{held.getId(), "ADJ-WH-2026-000007"});

        assertThatThrownBy(() -> sales.addUnit("U-WH-000003", null)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo("sale.unit.notForSale");
            assertThat(((MessageSourceResolvable) e.getArgs()[1]).getCodes()).containsExactly("stock.status.SOLD");
        });
        assertThatThrownBy(() -> sales.addUnit("U-WH-000004", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getArgs()).containsExactly("U-WH-000004", "Umucyo Builders"));     // reserved for another
        assertThatThrownBy(() -> sales.addUnit("U-WH-000005", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getArgs()).containsExactly("U-WH-000005", "ADJ-WH-2026-000007"));  // held by an adjustment
        assertThatThrownBy(() -> sales.addUnit("U-WH-000006", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("sale.noPrice"));
    }

    @Test
    void theCustomerRepricesTheLinesAndGivesTheBuyersTin() {
        tills.open(BigDecimal.ZERO);
        unit("U-WH-000007", clear6, 2000, 1000, StockStatus.AVAILABLE, null);
        unit("U-WH-000008", clear6, 2000, 1000, StockStatus.RESERVED, builders);
        SalesInvoice sale = sales.addUnit("U-WH-000007", null);
        assertThat(sale.getLines().get(0).getAmount()).isEqualByComparingTo("54000");     // RETAIL: 27,000 x 2 m²

        sales.setCustomer(builders.getId(), null, null);
        assertThat(sale.getLines().get(0).getPriceList()).isEqualTo(contractor);
        assertThat(sale.getLines().get(0).isPricesIncludeVat()).isFalse();
        assertThat(sale.getLines().get(0).getAmount()).isEqualByComparingTo("54000");     // 22,881.36 x 2 + 18% VAT
        assertThat(sale.getBuyerTin()).isEqualTo("100123456");                            // the customer's TIN (TAX-04)
        sales.addUnit("U-WH-000008", null);                                               // reserved for them: allowed now

        assertThatThrownBy(() -> sales.setCustomer(walkIn.getId(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.unit.reservedFor"));
        assertThatThrownBy(() -> sales.setCustomer(builders.getId(), null, "12345"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("buyerTin"));
    }

    // ---------------------------------------------------------------- paying (POS-04, POS-05)

    @Test
    void payingIssuesTheInvoiceSellsTheUnitsAndPostsTheJournal() {
        TillSession session = tills.open(new BigDecimal("20000"));
        StockUnit sheet = unit("U-WH-000002", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        StockUnit exemptPiece = unit("U-WH-000009", exemptGlass, 1000, 1000, StockStatus.AVAILABLE, null);
        SalesInvoice sale = sales.addUnit("U-WH-000002", null);
        sales.addUnit("U-WH-000009", null);
        sales.setCustomer(null, "Jean Habimana", "102938475");

        SalesService.Paid paid = sales.pay(new SalePayments.Entered(new BigDecimal("150000"), new BigDecimal("72008"), "MP-55",
                null, null, null, null, null));

        assertThat(sale.getStatus()).isEqualTo(SalesInvoiceStatus.POSTED);
        assertThat(sale.getNumber()).isEqualTo("INV-WH-2026-000001");
        assertThat(sale.getInvoiceDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("222008");                  // 195,008 + 27,000 exempt
        assertThat(sale.getVatAmount()).isEqualByComparingTo("29746.98");                 // 195,008 x 18 / 118
        assertThat(sale.getNetAmount()).isEqualByComparingTo("192261.02");
        assertThat(sale.getBillTo()).isEqualTo("Jean Habimana");
        assertThat(sale.getBuyerTin()).isEqualTo("102938475");
        assertThat(paid.change()).isEqualByComparingTo("0");
        assertThat(sale.getCashTendered()).isEqualByComparingTo("150000");
        assertThat(payments).extracting(SalesPayment::getMethod).containsExactly(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo("150000");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.SOLD);
        assertThat(exemptPiece.getStatus()).isEqualTo(StockStatus.SOLD);
        assertThat(sheet.getLocation()).isNull();
        ArgumentCaptor<StockMovement> moves = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(2)).save(moves.capture());
        assertThat(moves.getAllValues()).allSatisfy(m -> {
            assertThat(m.getType()).isEqualTo(MovementType.SALE);
            assertThat(m.getRefNumber()).isEqualTo("INV-WH-2026-000001");
        });
        verify(postings).sale(eq(sale), eq(payments), any());
        assertThat(tills.summary(session).getExpectedCash()).isEqualByComparingTo("170000");
    }

    @Test
    void customerCreditIsForAccountCustomersWithinTheirLimit() {
        tills.open(BigDecimal.ZERO);
        unit("U-WH-000010", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        sales.addUnit("U-WH-000010", null);
        assertThatThrownBy(() -> sales.pay(new SalePayments.Entered(null, null, null, null, null, null, null, new BigDecimal("195008"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.pay.credit.notAllowed"));

        sales.setCustomer(builders.getId(), null, null);                                  // contractor list: 22,881.36 + VAT
        when(journals.receivable(builders.getId())).thenReturn(new BigDecimal("400000"));  // owes 400,000 of 500,000
        assertThatThrownBy(() -> sales.pay(new SalePayments.Entered(null, null, null, null, null, null, null, new BigDecimal("195008"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.pay.credit.over"));

        when(journals.receivable(builders.getId())).thenReturn(BigDecimal.ZERO);
        SalesService.Paid paid = sales.pay(new SalePayments.Entered(null, null, null, null, null, null, null, new BigDecimal("195008")));
        assertThat(paid.invoice().getTotalAmount()).isEqualByComparingTo("195008");
        assertThat(payments).singleElement().extracting(SalesPayment::getMethod).isEqualTo(PaymentMethod.CREDIT);
    }

    @Test
    void anEmptySaleIsNotPaidAndACancelledSaleFreesItsUnits() {
        tills.open(BigDecimal.ZERO);
        StockUnit unit = unit("U-WH-000011", clear6, 1000, 1000, StockStatus.AVAILABLE, null);
        assertThatThrownBy(() -> sales.pay(new SalePayments.Entered(BigDecimal.TEN, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class);
        SalesInvoice sale = sales.addUnit("U-WH-000011", null);
        sales.removeLine(assignIds(sale));                                                  // ids come from the database
        assertThatThrownBy(() -> sales.pay(new SalePayments.Entered(BigDecimal.TEN, null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.empty"));

        sales.addUnit("U-WH-000011", null);
        sales.cancel();
        assertThat(sale.getStatus()).isEqualTo(SalesInvoiceStatus.CANCELLED);
        assertThat(unit.getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    // ---------------------------------------------------------------- quotations (POS-03)

    @Test
    void aQuotationIsPricedLikeASaleWithinItsAuthorsDiscountLimit() {
        QuotationDto dto = quote(walkIn, size(clear6, 600, 400, 2, "5", 2, edging, drilling), sheets(clear6, 3210, 2250, 1));
        assertThat(dto.getValidUntil()).isEqualTo(LocalDate.of(2026, 10, 23));            // 14 days (Settings)

        Quotation q = quotationService.create(dto);
        assertThat(q.getNumber()).isEqualTo("QUO-WH-2026-000001");
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(q.getQuoteDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        List<QuotationLine> lines = q.getLines();
        assertThat(lines).extracting(QuotationLine::getKind).containsExactly(QuoteLineKind.CUSTOM_PIECE, QuoteLineKind.SERVICE,
                QuoteLineKind.SERVICE, QuoteLineKind.SHEET);
        assertThat(lines).extracting(QuotationLine::getLineNo).containsExactly(1, 2, 3, 4);
        QuotationLine piece = lines.get(0);                            // 2 x 0.25 m² (minimum) x 27,000 less 5%
        assertThat(piece.getListPrice()).isEqualByComparingTo("27000");
        assertThat(piece.getDiscountPercent()).isEqualByComparingTo("5");
        assertThat(piece.getPrice()).isEqualByComparingTo("25650");
        assertThat(piece.getAmount()).isEqualByComparingTo("12825");
        assertThat(piece.getProcessing()).isEqualTo("DRILL,EDGE");
        assertThat(lines.get(1).getService()).isEqualTo(drilling);     // 4 holes x 475
        assertThat(lines.get(1).getAmount()).isEqualByComparingTo("1900");
        assertThat(lines.get(2).getParentLine()).isSameAs(piece);      // 4 m of edge x 1,425
        assertThat(lines.get(2).getAmount()).isEqualByComparingTo("5700");
        assertThat(lines.get(3).getAmount()).isEqualByComparingTo("195008");   // 7.2225 m² x 27,000, no discount
        assertThat(q.getTotalAmount()).isEqualByComparingTo("215433");
        assertThat(quotationService.totals(q).gross()).isEqualByComparingTo("215433");

        assertThatThrownBy(() -> quotationService.create(quote(walkIn, size(clear6, 600, 400, 2, "6", null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("quote.line.discount.overLimit");
                    assertThat(e.getField()).isEqualTo("lines[0].discountPercent");
                });
        QuotationDto.Line edgedSheet = sheets(clear6, 3210, 2250, 1);
        edgedSheet.getServiceIds().add(edging.getId());
        assertThatThrownBy(() -> quotationService.create(quote(walkIn, edgedSheet)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("quote.line.sheetProcessing"));
        assertThatThrownBy(() -> quotationService.create(quote(walkIn, size(tempered, 600, 400, 1, null, null))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.custom.notCuttable"));

        // Edited while a draft: rows keep their lines, a size loses its drilling; sent, it is fixed
        QuotationDto edit = quotationService.formOf(q, false);
        edit.getLines().get(0).getServiceIds().remove(drilling.getId());
        edit.getLines().get(0).setHoles(null);
        quotationService.update(q.getId(), edit);
        assertThat(q.getLines()).hasSize(3);
        assertThat(q.getLines().get(0)).isSameAs(piece);
        assertThat(q.getTotalAmount()).isEqualByComparingTo("213533");
        quotationService.send(q.getId());
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(q.getSentBy()).isEqualTo("cashier1");
        assertThatThrownBy(() -> quotationService.update(q.getId(), edit))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("quote.notDraft"));
        QuotationDto copy = quotationService.formOf(q, true);
        assertThat(copy.getId()).isNull();
        assertThat(copy.getLines()).hasSize(2).allSatisfy(r -> assertThat(r.getId()).isNull());
    }

    @Test
    void aSentQuotationIsRungUpAtItsPricesAndConvertedWhenPaid() {
        Quotation q = quotationService.create(quote(builders, size(clear6, 600, 400, 2, "5", null, edging), sheets(clear6, 3210, 2250, 1)));
        TillSession session = tills.open(BigDecimal.ZERO);
        assertThatThrownBy(() -> sales.ringUp(q.getId()))                               // not sent yet
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.quote.notSent"));
        quotationService.send(q.getId());
        assertThatThrownBy(() -> sales.ringUp(q.getId()))                               // no whole sheet in stock
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("sale.quote.noSheets");
                    assertThat(e.getArgs()).containsExactly("CLR-6", "3210 x 2250", 1, 0);
                });
        unit("U-WH-000041", clear6, 2250, 3210, StockStatus.AVAILABLE, null);            // either way round
        doAnswer(a -> {                                                                    // the list went up since
            Product p = a.getArgument(1);
            return p == clear6 ? Optional.of(new PriceListService.UnitPrice(new BigDecimal("24000"), contractor, false)) : Optional.empty();
        }).when(priceLists).priceFor(any(), any());

        SalesInvoice sale = sales.ringUp(q.getId());
        assertThat(sale.getCustomer()).isSameAs(builders);
        assertThat(sale.getBuyerTin()).isEqualTo("100123456");
        assertThat(sale.getQuotationId()).isEqualTo(q.getId());
        List<SalesInvoiceLine> lines = sale.getLines();
        assertThat(lines).extracting(SalesInvoiceLine::getKind).containsExactly(SaleLineKind.CUSTOM_PIECE, SaleLineKind.SERVICE,
                SaleLineKind.STOCK_UNIT);
        SalesInvoiceLine piece = lines.get(0);                          // the quotation's price, today's list kept with the reason
        assertThat(piece.getPricePerM2()).isEqualByComparingTo(q.getLines().get(0).getPrice());
        assertThat(piece.getListPrice()).isEqualByComparingTo("24000");
        assertThat(piece.getPriceReason()).isEqualTo("Quotation QUO-WH-2026-000001");
        assertThat(lines.get(1).getParentLine()).isSameAs(piece);
        assertThat(lines.get(2).getUnitCode()).isEqualTo("U-WH-000041");
        assertThat(sales.totals(sale).gross()).isEqualByComparingTo(q.getTotalAmount());
        assertThat(quotationService.onTill(q)).contains("TILL-WH-2026-000001");
        assertThatThrownBy(() -> quotationService.cancel(q.getId(), "changed mind"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("quote.cancel.onTill"));
        assertThatThrownBy(() -> sales.ringUp(q.getId()))                               // the sale is no longer empty
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.quote.saleNotEmpty"));

        sales.pay(new SalePayments.Entered(sales.totals(sale).gross(), null, null, null, null, null, null, null));
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.CONVERTED);
        assertThat(q.getInvoiceId()).isEqualTo(sale.getId());
        assertThat(q.getConvertedAt()).isNotNull();
        assertThatThrownBy(() -> sales.ringUp(q.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.quote.notSent"));
        assertThat(tills.summary(session).getCashSales()).isEqualByComparingTo(q.getTotalAmount());
    }

    @Test
    void anExpiredQuotationIsNotRungUpAndChangingTheCustomerDropsTheQuotation() {
        Quotation old = quotationService.create(quote(walkIn, size(clear6, 600, 400, 1, null, null)));
        quotationService.send(old.getId());
        old.setValidUntil(LocalDate.of(2026, 10, 8));                                    // its prices held until yesterday
        assertThat(old.isExpiredOn(LocalDate.of(2026, 10, 9))).isTrue();
        tills.open(BigDecimal.ZERO);
        assertThatThrownBy(() -> sales.ringUp(old.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("sale.quote.expired");
                    assertThat(e.getArgs()).containsExactly("QUO-WH-2026-000001", "08/10/2026");
                });

        Quotation q = quotationService.create(quote(walkIn, size(clear6, 600, 400, 1, "5", null)));
        quotationService.send(q.getId());
        SalesInvoice sale = sales.ringUp(q.getId());
        sales.setCustomer(builders.getId(), null, null);                                 // priced again on the contractor list
        assertThat(sale.getQuotationId()).isNull();
        assertThat(sale.getLines().get(0).isPriceChanged()).isFalse();
        sales.cancel();
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.SENT);                      // can be rung up again
        quotationService.cancel(q.getId(), "Bought elsewhere");
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.CANCELLED);
        assertThat(q.getCancelReason()).isEqualTo("Bought elsewhere");
    }

    // ---------------------------------------------------------------- price changes and credit (POS-05, POS-06)

    @Test
    void aPriceWithinTheCashiersLimitAppliesAtOnceWithItsReason() {
        tills.open(BigDecimal.ZERO);
        unit("U-WH-000030", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        SalesInvoice sale = sales.addUnit("U-WH-000030", null);                      // 7.2225 m² x 27,000 = 195,008
        UUID lineId = assignIds(sale);
        SalesInvoiceLine line = sale.getLines().get(0);
        assertThat(sales.discountLimit()).isEqualByComparingTo("5");

        assertThatThrownBy(() -> sales.changePrice(lineId, new BigDecimal("26000"), " "))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.price.reason"));
        assertThatThrownBy(() -> sales.changePrice(lineId, BigDecimal.ZERO, "free"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.price.invalid"));

        SalesService.PriceChange change = sales.changePrice(lineId, new BigDecimal("26000"), "Regular customer");   // 3.70% off
        assertThat(change.approval()).isNull();
        assertThat(change.discount()).isEqualByComparingTo("3.70");
        assertThat(line.getPricePerM2()).isEqualByComparingTo("26000");
        assertThat(line.getListPrice()).isEqualByComparingTo("27000");
        assertThat(line.getPriceReason()).isEqualTo("Regular customer");
        assertThat(line.getAmount()).isEqualByComparingTo("187785");                  // 7.2225 x 26,000
        assertThat(approvals).isEmpty();

        sales.changePrice(lineId, new BigDecimal("28000"), "Delivered to site");       // a higher price never needs approval
        assertThat(line.getDiscountPercent()).isEqualByComparingTo("-3.70");
        assertThat(approvals).isEmpty();

        sales.changePrice(lineId, new BigDecimal("27000"), null);                      // back to the list price: no reason
        assertThat(line.isPriceChanged()).isFalse();
        assertThat(line.getPriceReason()).isNull();
        assertThat(line.getAmount()).isEqualByComparingTo("195008");
    }

    @Test
    void aDeeperDiscountWaitsForAnotherPersonsApprovalBeforeTheSaleIsPaid() {
        TillSession session = tills.open(BigDecimal.ZERO);
        unit("U-WH-000031", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        SalesInvoice sale = sales.addUnit("U-WH-000031", null);
        UUID lineId = assignIds(sale);
        SalesInvoiceLine line = sale.getLines().get(0);

        SalesService.PriceChange change = sales.changePrice(lineId, new BigDecimal("24000"), "Buys 20 sheets a month");
        SaleApproval request = change.approval();
        assertThat(request).isNotNull();
        assertThat(request.getNumber()).isEqualTo("APR-WH-2026-000001");
        assertThat(request.getStatus()).isEqualTo(SaleApprovalStatus.PENDING);
        assertThat(request.getKind()).isEqualTo(SaleApprovalKind.PRICE);
        assertThat(request.getLineId()).isEqualTo(lineId);
        assertThat(request.getSubject()).isEqualTo("U-WH-000031 · CLR-6 3210 x 2250");
        assertThat(request.getListPrice()).isEqualByComparingTo("27000");
        assertThat(request.getRequestedPrice()).isEqualByComparingTo("24000");
        assertThat(request.getDiscountPercent()).isEqualByComparingTo("11.11");
        assertThat(request.getLimitPercent()).isEqualByComparingTo("5");
        assertThat(request.getAmountBefore()).isEqualByComparingTo("195008");
        assertThat(request.getAmountAfter()).isEqualByComparingTo("173340");
        assertThat(request.getRequestedBy()).isEqualTo("cashier1");
        assertThat(line.getAmount()).isEqualByComparingTo("195008");                  // unchanged until approved

        assertThatThrownBy(() -> sales.pay(new SalePayments.Entered(new BigDecimal("195008"), null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("sale.pay.pending");
                    assertThat(e.getArgs()).containsExactly("APR-WH-2026-000001");
                });
        assertThatThrownBy(() -> sales.changePrice(lineId, new BigDecimal("25000"), "less"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.price.pending"));
        assertThatThrownBy(() -> approvalService.approve(request.getId(), null))         // never your own
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("saleApproval.own"));

        signIn(9L, "owner");
        approvalService.approve(request.getId(), "Good customer");
        assertThat(request.getStatus()).isEqualTo(SaleApprovalStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo("owner");
        assertThat(request.getDecisionNote()).isEqualTo("Good customer");
        assertThat(line.getPricePerM2()).isEqualByComparingTo("24000");
        assertThat(line.getListPrice()).isEqualByComparingTo("27000");
        assertThat(line.getPriceReason()).isEqualTo("Buys 20 sheets a month");
        assertThat(line.getAmount()).isEqualByComparingTo("173340");
        assertThatThrownBy(() -> approvalService.reject(request.getId(), "late"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("saleApproval.notPending"));

        signIn(5L, "cashier1");
        SalesService.Paid paid = sales.pay(new SalePayments.Entered(new BigDecimal("173340"), null, null, null, null, null, null, null));
        assertThat(paid.invoice().getTotalAmount()).isEqualByComparingTo("173340");
        assertThat(tills.summary(session).getExpectedCash()).isEqualByComparingTo("173340");
    }

    @Test
    void aRejectedRequestLeavesTheSaleAndRequestsLapseWithTheirLineCustomerOrSale() {
        tills.open(BigDecimal.ZERO);
        unit("U-WH-000032", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        unit("U-WH-000033", clear6, 1000, 1000, StockStatus.AVAILABLE, null);
        sales.addUnit("U-WH-000032", null);
        SalesInvoice sale = sales.addUnit("U-WH-000033", null);
        sale.getLines().forEach(l -> l.setId(UUID.randomUUID()));
        SalesInvoiceLine first = sale.getLines().get(0);
        SalesInvoiceLine second = sale.getLines().get(1);

        SaleApproval refused = sales.changePrice(first.getId(), new BigDecimal("20000"), "Competitor price").approval();
        signIn(9L, "owner");
        approvalService.reject(refused.getId(), "Too deep");
        assertThat(refused.getStatus()).isEqualTo(SaleApprovalStatus.REJECTED);
        assertThat(refused.getDecisionNote()).isEqualTo("Too deep");
        assertThat(first.isPriceChanged()).isFalse();
        assertThat(first.getAmount()).isEqualByComparingTo("195008");

        // Its line leaves the sale: the request lapses and lets go of the line
        signIn(5L, "cashier1");
        SaleApproval lapsed = sales.changePrice(second.getId(), new BigDecimal("20000"), "Scratched").approval();
        sales.removeLine(second.getId());
        assertThat(lapsed.getStatus()).isEqualTo(SaleApprovalStatus.WITHDRAWN);
        assertThat(lapsed.getDecisionNote()).isEqualTo("The line was removed from the sale");
        assertThat(lapsed.getLineId()).isNull();

        // Another customer prices the sale again: changed prices and approved requests go
        sales.changePrice(first.getId(), new BigDecimal("26000"), "Regular customer");
        SaleApproval waiting = sales.changePrice(first.getId(), new BigDecimal("21000"), "Even less").approval();
        sales.setCustomer(builders.getId(), null, null);
        assertThat(waiting.getStatus()).isEqualTo(SaleApprovalStatus.WITHDRAWN);
        assertThat(first.isPriceChanged()).isFalse();
        assertThat(first.getPricePerM2()).isEqualByComparingTo("22881.36");

        // The cashier withdraws one, with a reason; a cancelled sale takes the rest with it
        SaleApproval mine = sales.changePrice(first.getId(), new BigDecimal("15000"), "Old stock").approval();
        assertThatThrownBy(() -> sales.withdrawRequest(mine.getId(), " "))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("po.reason.required"));
        sales.withdrawRequest(mine.getId(), "Customer changed their mind");
        assertThat(mine.getStatus()).isEqualTo(SaleApprovalStatus.WITHDRAWN);
        assertThat(mine.getDecidedBy()).isEqualTo("cashier1");
        SaleApproval last = sales.changePrice(first.getId(), new BigDecimal("15000"), "Old stock").approval();
        sales.cancel();
        assertThat(last.getStatus()).isEqualTo(SaleApprovalStatus.WITHDRAWN);
        assertThat(last.getDecisionNote()).isEqualTo("The sale was cancelled");
    }

    @Test
    void creditAboveTheCustomersLimitNeedsAManagersApproval() {
        tills.open(BigDecimal.ZERO);
        unit("U-WH-000034", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        sales.addUnit("U-WH-000034", null);
        assertThatThrownBy(() -> sales.requestCredit(new BigDecimal("1000"), "x"))      // a walk-in never buys on credit
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.pay.credit.notAllowed"));

        sales.setCustomer(builders.getId(), null, null);                                // 195,008 on the contractor list
        when(journals.receivable(builders.getId())).thenReturn(new BigDecimal("400000"));  // 100,000 of 500,000 left
        assertThatThrownBy(() -> sales.requestCredit(new BigDecimal("50000"), "x"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.credit.withinLimit"));
        assertThatThrownBy(() -> sales.requestCredit(new BigDecimal("300000"), "x"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.credit.overTotal"));
        assertThatThrownBy(() -> sales.requestCredit(new BigDecimal("150000"), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.credit.reason"));

        SaleApproval request = sales.requestCredit(new BigDecimal("150000"), "Pays every Friday");
        assertThat(request.getKind()).isEqualTo(SaleApprovalKind.CREDIT);
        assertThat(request.getSubject()).isEqualTo("Umucyo Builders · " + builders.getCode());
        assertThat(request.getCreditLimit()).isEqualByComparingTo("500000");
        assertThat(request.getOwed()).isEqualByComparingTo("400000");
        assertThat(request.getCreditAmount()).isEqualByComparingTo("150000");
        assertThat(request.getOverLimit()).isEqualByComparingTo("50000");
        assertThatThrownBy(() -> sales.requestCredit(new BigDecimal("160000"), "more"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.credit.pending"));
        assertThatThrownBy(() -> sales.pay(new SalePayments.Entered(new BigDecimal("45008"), null, null, null, null, null, null, new BigDecimal("150000"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.pay.pending"));

        signIn(9L, "owner");
        approvalService.approve(request.getId(), null);
        signIn(5L, "cashier1");
        assertThatThrownBy(() -> sales.pay(new SalePayments.Entered(null, null, null, null, null, null, null, new BigDecimal("195008"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("sale.pay.credit.overApproved");
                    assertThat(e.getArgs()).containsExactly(new BigDecimal("150000.00"), new BigDecimal("100000"));
                });
        SalesService.Paid paid = sales.pay(new SalePayments.Entered(new BigDecimal("45008"), null, null, null, null, null, null, new BigDecimal("150000")));
        assertThat(paid.invoice().getTotalAmount()).isEqualByComparingTo("195008");
        assertThat(payments).extracting(SalesPayment::getMethod).containsExactly(PaymentMethod.CASH, PaymentMethod.CREDIT);

        // A credit request on a sale emptied line by line lapses (closing the till would drop the empty sale)
        unit("U-WH-000035", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        SalesInvoice next = sales.addUnit("U-WH-000035", null);
        sales.setCustomer(builders.getId(), null, null);
        SaleApproval asked = sales.requestCredit(new BigDecimal("150000"), "Pays every Friday");
        sales.removeLine(assignIds(next));
        assertThat(asked.getStatus()).isEqualTo(SaleApprovalStatus.WITHDRAWN);
        assertThat(asked.getDecisionNote()).isEqualTo("The sale was emptied");
    }

    // ---------------------------------------------------------------- sizes to cut (POS-02)

    @Test
    void aSizeIsPricedByChargeableAreaWithItsProcessingAsServiceLines() {
        tills.open(BigDecimal.ZERO);

        SalesInvoice sale = sales.addCustom(new SalesService.CustomSize(clear6.getId(), 600, 400, 3,
                List.of(edging.getId(), drilling.getId()), 2, " kitchen "));

        assertThat(sale.getLines()).extracting(SalesInvoiceLine::getKind)
                .containsExactly(SaleLineKind.CUSTOM_PIECE, SaleLineKind.SERVICE, SaleLineKind.SERVICE);
        SalesInvoiceLine size = sale.getLines().get(0);
        assertThat(size.getChargeableAreaM2()).isEqualByComparingTo("0.25");            // 0.24 m² charged as 0.25 (MD-06)
        assertThat(size.getAmount()).isEqualByComparingTo("20250");                    // 3 x 0.25 x 27,000
        assertThat(size.getMark()).isEqualTo("kitchen");
        assertThat(size.getProcessing()).isEqualTo("DRILL,EDGE");
        assertThat(size.getStockUnitId()).isNull();
        SalesInvoiceLine drill = sale.getLines().get(1);                               // services in code order
        assertThat(drill.getService()).isEqualTo(drilling);
        assertThat(drill.getParentLine()).isEqualTo(size);
        assertThat(drill.getServiceQuantity()).isEqualByComparingTo("6");               // 2 holes x 3 pieces
        assertThat(drill.getAmount()).isEqualByComparingTo("3000");
        assertThat(drill.getTaxCode()).isEqualTo("B");                                 // processing at the standard rate
        SalesInvoiceLine edge = sale.getLines().get(2);
        assertThat(edge.getServiceQuantity()).isEqualByComparingTo("6");                // 2 m of edge x 3 pieces
        assertThat(edge.getAmount()).isEqualByComparingTo("9000");
        assertThat(sale.getLines()).extracting(SalesInvoiceLine::getLineNo).containsExactly(1, 2, 3);
        assertThat(sales.totals(sale).gross()).isEqualByComparingTo("32250");
    }

    @Test
    void aSizeTakesItsProcessingWhenItIsRemovedAndWrongSizesAreRefused() {
        tills.open(BigDecimal.ZERO);
        SalesInvoice sale = sales.addCustom(new SalesService.CustomSize(clear6.getId(), 600, 400, 1, List.of(edging.getId()), null, null));
        sale.getLines().forEach(l -> l.setId(UUID.randomUUID()));
        sales.removeLine(sale.getLines().get(1).getId());                               // the edging alone
        assertThat(sale.getLines()).singleElement().extracting(SalesInvoiceLine::getKind).isEqualTo(SaleLineKind.CUSTOM_PIECE);
        sales.addCustom(new SalesService.CustomSize(clear6.getId(), 800, 500, 2, List.of(edging.getId()), null, null));
        sale.getLines().forEach(l -> l.setId(l.getId() == null ? UUID.randomUUID() : l.getId()));
        sales.removeLine(sale.getLines().get(1).getId());                               // the second size, with its edging
        assertThat(sale.getLines()).singleElement().satisfies(l -> assertThat(l.getWidthMm()).isEqualTo(600));

        assertThatThrownBy(() -> sales.addCustom(new SalesService.CustomSize(tempered.getId(), 600, 400, 1, List.of(), null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.custom.notCuttable"));
        assertThatThrownBy(() -> sales.addCustom(new SalesService.CustomSize(clear6.getId(), 600, 0, 1, List.of(), null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("heightMm"));
        assertThatThrownBy(() -> sales.addCustom(new SalesService.CustomSize(clear6.getId(), 600, 400, 1, List.of(drilling.getId()), null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getField()).isEqualTo("holes"));
        assertThatThrownBy(() -> sales.addCustom(new SalesService.CustomSize(clear6.getId(), 600, 400, 1, List.of(polishing.getId()), null, null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.custom.noServicePrice"));
    }

    @Test
    void payingForSizesCreatesTheirCuttingJobsAndSellsNothingYet() {
        tills.open(BigDecimal.ZERO);
        StockUnit sheet = unit("U-WH-000020", clear6, 3210, 2250, StockStatus.AVAILABLE, null);
        SalesInvoice sale = sales.addCustom(new SalesService.CustomSize(clear6.getId(), 600, 400, 3, List.of(edging.getId()), null, "K1"));
        sales.addCustom(new SalesService.CustomSize(clear6.getId(), 1200, 800, 1, List.of(), null, null));
        sales.addCustom(new SalesService.CustomSize(exemptGlass.getId(), 500, 500, 2, List.of(), null, null));
        sale.getLines().forEach(l -> l.setId(UUID.randomUUID()));

        SalesService.Paid paid = sales.pay(new SalePayments.Entered(new BigDecimal("200000"), null, null, null, null, null, null, null));

        assertThat(sale.getStatus()).isEqualTo(SalesInvoiceStatus.POSTED);
        assertThat(paid.jobs()).hasSize(2);                                             // one per glass
        CuttingJobDto clear = jobRequests.get(0);
        assertThat(clear.getPurpose()).isEqualTo(CuttingPurpose.CUSTOMER);
        assertThat(clear.getCustomerId()).isEqualTo(walkIn.getId());
        assertThat(clear.getCustomerRef()).isEqualTo("INV-WH-2026-000001");
        assertThat(clear.getProductId()).isEqualTo(clear6.getId());
        assertThat(clear.getLines()).extracting(CuttingJobDto.Line::getQuantity).containsExactly(3, 1);
        assertThat(clear.getLines().get(0).getProcessing()).containsExactly("EDGE");
        assertThat(clear.getLines().get(0).getMark()).isEqualTo("K1");
        CuttingJob job = paid.jobs().get(0);
        assertThat(job.getSalesInvoiceId()).isEqualTo(sale.getId());
        assertThat(job.getLines().get(0).getSalesLineId()).isEqualTo(sale.getLines().get(0).getId());
        assertThat(job.getLines().get(1).getSalesLineId()).isEqualTo(sale.getLines().get(2).getId());
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);                // nothing leaves stock until cut and handed over
        verify(movementRepo, never()).save(any());
    }

    @Test
    void piecesCutForTheSaleAreHandedOverAndTheirCostPosted() {
        tills.open(BigDecimal.ZERO);
        SalesInvoice sale = sales.addCustom(new SalesService.CustomSize(clear6.getId(), 600, 400, 2, List.of(), null, null));
        sale.getLines().forEach(l -> l.setId(UUID.randomUUID()));
        CuttingJobLine size = sales.pay(new SalePayments.Entered(new BigDecimal("13500"), null, null, null, null, null, null, null))
                .jobs().get(0).getLines().get(0);
        StockUnit first = cut(size, CuttingOutputKind.PIECE, unit("U-WH-000071", clear6, 400, 600, StockStatus.RESERVED, walkIn));
        StockUnit second = cut(size, CuttingOutputKind.PIECE, unit("U-WH-000072", clear6, 600, 400, StockStatus.RESERVED, walkIn));
        StockUnit third = cut(size, CuttingOutputKind.PIECE, unit("U-WH-000073", clear6, 600, 400, StockStatus.RESERVED, walkIn));
        cut(size, CuttingOutputKind.PIECE, unit("U-WH-000076", clear6, 600, 400, StockStatus.BROKEN, null));
        cut(size, CuttingOutputKind.OFFCUT, unit("U-WH-000074", clear6, 700, 400, StockStatus.AVAILABLE, null));
        // Same glass, same size, reserved for the same walk-in customer, but cut for another sale
        CuttingJob otherSales = new CuttingJob();
        otherSales.setId(UUID.randomUUID());
        otherSales.setSalesInvoiceId(UUID.randomUUID());
        CuttingJobLine otherSize = new CuttingJobLine();
        otherSize.setId(UUID.randomUUID());
        otherSize.setSalesLineId(UUID.randomUUID());
        otherSales.getLines().add(otherSize);
        jobs.add(otherSales);
        StockUnit someoneElses = cut(otherSize, CuttingOutputKind.PIECE, unit("U-WH-000075", clear6, 600, 400, StockStatus.RESERVED, walkIn));

        assertThat(sales.readyPieces(sale)).extracting(StockUnit::getCode).containsExactly("U-WH-000071", "U-WH-000072", "U-WH-000073");
        assertThatThrownBy(() -> sales.deliver(sale.getId(), List.of(someoneElses.getId()), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessageKey()).isEqualTo("sale.deliver.notThisSale");
                    assertThat(e.getArgs()).containsExactly("U-WH-000075", "INV-WH-2026-000001");
                });
        assertThatThrownBy(() -> sales.deliver(sale.getId(), null, "U-WH-000074"))               // the off-cut stays in stock
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.deliver.notThisSale"));
        assertThatThrownBy(() -> sales.deliver(sale.getId(), null, "U-WH-000076"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("sale.deliver.notReady"));
        assertThatThrownBy(() -> sales.deliver(sale.getId(), List.of(first.getId(), second.getId(), third.getId()), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {                       // 2 ordered
                    assertThat(e.getMessageKey()).isEqualTo("sale.deliver.noSize");
                    assertThat(e.getArgs()).containsExactly("U-WH-000073");
                });

        SalesService.Delivered delivered = sales.deliver(sale.getId(), List.of(first.getId()), " u-wh-000072 ");

        assertThat(delivered.units()).extracting(StockUnit::getCode).containsExactly("U-WH-000071", "U-WH-000072");
        assertThat(first.getStatus()).isEqualTo(StockStatus.SOLD);
        assertThat(second.getStatus()).isEqualTo(StockStatus.SOLD);
        assertThat(someoneElses.getStatus()).isEqualTo(StockStatus.RESERVED);
        assertThat(deliveries).extracting(SalesDelivery::getLineId).containsOnly(sale.getLines().get(0).getId());
        assertThat(sales.progress(sale).get(sale.getLines().get(0).getId()).getRemaining()).isZero();
        assertThat(sales.readyPieces(sale)).isEmpty();
        verify(postings).saleDelivery(eq(sale), any());

        // The third piece was cut for this sale: another walk-in sale at the counter cannot take it
        when(invoiceRepo.findById(sale.getId())).thenReturn(Optional.of(sale));
        assertThatThrownBy(() -> sales.addUnit("U-WH-000073", null)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo("sale.unit.cutForSale");
            assertThat(e.getArgs()).containsExactly("U-WH-000073", "INV-WH-2026-000001");
        });
    }

    // ---------------------------------------------------------------- helpers

    private static ProcessingService service(String code, String name, ChargeUnit unit) {
        ProcessingService sv = new ProcessingService();
        sv.setId(UUID.randomUUID());
        sv.setCode(code);
        sv.setName(name);
        sv.setChargeUnit(unit);
        sv.setEnabled(true);
        return sv;
    }

    private QuotationDto quote(Customer customer, QuotationDto.Line... rows) {
        QuotationDto dto = quotationService.newForm();
        dto.setCustomerId(customer.getId());
        dto.getLines().clear();
        dto.getLines().addAll(List.of(rows));
        return dto;
    }

    private static QuotationDto.Line size(Product product, int w, int h, int qty, String discount, Integer holes, ProcessingService... services) {
        QuotationDto.Line row = new QuotationDto.Line();
        row.setKind(QuoteLineKind.CUSTOM_PIECE);
        row.setProductId(product.getId());
        row.setWidthMm(w);
        row.setHeightMm(h);
        row.setQuantity(qty);
        row.setHoles(holes);
        row.setDiscountPercent(discount == null ? null : new BigDecimal(discount));
        for (ProcessingService sv : services) {
            row.getServiceIds().add(sv.getId());
        }
        return row;
    }

    private static QuotationDto.Line sheets(Product product, int w, int h, int qty) {
        QuotationDto.Line row = size(product, w, h, qty, null, null);
        row.setKind(QuoteLineKind.SHEET);
        return row;
    }

    private static Role role(String code, String discountLimit) {
        return Role.builder().code(code).name("ROLE_" + code).enabled(true)
                .discountLimitPercent(discountLimit == null ? null : new BigDecimal(discountLimit)).build();
    }

    /** Records a unit as an output of a cutting job line. */
    private StockUnit cut(CuttingJobLine line, CuttingOutputKind kind, StockUnit unit) {
        CuttingJobOutput output = new CuttingJobOutput();
        output.setCuttingJobId(line.getJob() != null ? line.getJob().getId()
                : jobs.stream().filter(j -> j.getLines().contains(line)).findFirst().orElseThrow().getId());
        output.setJobLineId(line.getId());
        output.setKind(kind);
        output.setStockUnitId(unit.getId());
        outputs.add(output);
        return unit;
    }

    private UUID assignIds(SalesInvoice sale) {
        sale.getLines().forEach(l -> l.setId(UUID.randomUUID()));
        return sale.getLines().get(0).getId();
    }

    private void payment(PaymentMethod method, String amount) {
        SalesPayment p = new SalesPayment();
        p.setMethod(method);
        p.setAmount(new BigDecimal(amount));
        payments.add(p);
    }

    private StockUnit unit(String code, Product product, int w, int h, StockStatus status, Customer reservedFor) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(product);
        u.setKind(UnitKind.SHEET);
        u.setWidthMm(w);
        u.setHeightMm(h);
        u.setAreaM2(Pricing.areaM2(w, h));
        u.setStatus(status);
        u.setReservedCustomer(reservedFor);
        units.add(u);
        return u;
    }

    private static TaxCategory tax(String letter, String rate) {
        TaxCategory t = new TaxCategory();
        t.setId(UUID.randomUUID());
        t.setEbmCode(letter);
        t.setRate(new BigDecimal(rate));
        return t;
    }

    private static Product product(String code, TaxCategory tax) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setTaxCategory(tax);
        return p;
    }

    private static PriceList priceList(String code, boolean includesVat) {
        PriceList l = new PriceList();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setPricesIncludeVat(includesVat);
        return l;
    }

    private static Customer customer(String name, CustomerType type, String limit, PriceList list, String tin) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setType(type);
        c.setCreditLimit(new BigDecimal(limit));
        c.setPriceList(list);
        c.setTin(tin);
        c.setEnabled(true);
        return c;
    }

    private static void signIn(long id, String username) {
        AppUserPrincipal principal = new AppUserPrincipal(id, username, username, "x", true, true, List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
