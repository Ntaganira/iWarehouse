package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.dto.CuttingJobDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SalesService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Counter sales (POS-01, POS-04, TAX-01, TAX-04). The sale being rung up is a draft of the
 *               cashier's open till: units from stock are added by scanning or from the search, priced from
 *               the customer's price list over their chargeable area (MD-06), and held meanwhile. Paying splits
 *               the total over cash, mobile money, card, bank transfer and customer credit (within the limit,
 *               POS-05), issues the invoice (INV number), sells the units (SOLD) and posts the journal: sales,
 *               VAT output, and the glass at MAC. Sizes to cut (POS-02) are priced by chargeable area with their
 *               processing as service lines; paying creates their cutting jobs, and the pieces are handed over
 *               later (sold then, their cost posted: SRS 5.3). A line's price can be changed with a reason
 *               (POS-06): within the cashier's discount limit at once, above it once a manager approves; credit
 *               above what the customer has left needs a manager's approval too (POS-05). Requests still pending
 *               stop the payment. A sent quotation is rung up at the till at its prices (POS-03) and converted when
 *               the sale is paid. An order with sizes to cut may be paid by a deposit (POS-08): the invoice is issued
 *               with a balance due, paid at collection in the till of whoever takes it, before any piece is handed over.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SalesService {

    /** The highest price a line can be given (NUMERIC(18,2) with room for the area). */
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999999");

    private final SalesInvoiceRepository repo;
    private final SalesPaymentRepository paymentRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;
    private final ProcessingServiceRepository serviceRepo;
    private final CuttingJobRepository jobRepo;
    private final CuttingJobOutputRepository outputRepo;
    private final CuttingJobLineRepository jobLineRepo;
    private final SalesDeliveryRepository deliveryRepo;
    private final SaleApprovalRepository approvalRepo;
    private final UserRepository userRepo;
    private final QuotationRepository quotationRepo;
    private final CreditNoteLineRepository creditLineRepo;
    private final TillService tillService;
    private final CuttingJobService cuttingJobService;
    private final StockService stockService;
    private final LinePricing pricing;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final Notifier notifier;
    private final Clock clock;

    public SalesService(SalesInvoiceRepository repo, SalesPaymentRepository paymentRepo, StockUnitRepository unitRepo,
                        ProductRepository productRepo, CustomerRepository customerRepo, ProcessingServiceRepository serviceRepo,
                        CuttingJobRepository jobRepo, CuttingJobOutputRepository outputRepo, CuttingJobLineRepository jobLineRepo,
                        SalesDeliveryRepository deliveryRepo, SaleApprovalRepository approvalRepo, UserRepository userRepo,
                        QuotationRepository quotationRepo, CreditNoteLineRepository creditLineRepo, TillService tillService,
                        CuttingJobService cuttingJobService,
                        StockService stockService, LinePricing pricing, PostingService postingService, JournalService journalService,
                        DocumentNumberService numbers, SettingService settingService, Notifier notifier, Clock clock) {
        this.notifier = notifier;
        this.repo = repo;
        this.paymentRepo = paymentRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
        this.serviceRepo = serviceRepo;
        this.jobRepo = jobRepo;
        this.outputRepo = outputRepo;
        this.jobLineRepo = jobLineRepo;
        this.deliveryRepo = deliveryRepo;
        this.approvalRepo = approvalRepo;
        this.userRepo = userRepo;
        this.quotationRepo = quotationRepo;
        this.creditLineRepo = creditLineRepo;
        this.tillService = tillService;
        this.cuttingJobService = cuttingJobService;
        this.stockService = stockService;
        this.pricing = pricing;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.clock = clock;
    }

    /** A customer's credit at the counter (POS-05): allowed for account customers with a limit, what they owe. */
    public record Credit(boolean allowed, BigDecimal limit, BigDecimal owed) {

        public BigDecimal getAvailable() {
            return allowed ? limit.subtract(owed).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        }
    }

    /** An invoice paid: the invoice, its change, its journal and the cutting jobs of its custom sizes. */
    public record Paid(SalesInvoice invoice, BigDecimal change, JournalEntry journal, List<CuttingJob> jobs) {
    }

    /** A size to cut for the customer (POS-02): glass, size, quantity, processing (holes per piece for drilling), mark. */
    public record CustomSize(UUID productId, Integer widthMm, Integer heightMm, Integer quantity, List<UUID> serviceIds,
                             Integer holes, String mark) {
    }

    /** An order's balance paid (POS-08): the invoice, the amount paid, the change and the journal. */
    public record BalancePaid(SalesInvoice invoice, BigDecimal paid, BigDecimal change, JournalEntry journal) {
    }

    /** A balance paid in a till: the payment and its invoice's number. */
    public record BalanceTaken(SalesPayment payment, String invoiceNumber) {
    }

    /** Pieces handed over: the units sold and the journal of their cost. */
    public record Delivered(SalesInvoice invoice, List<StockUnit> units, JournalEntry journal) {
    }

    /** A line's price changed (POS-06): applied at once (no approval), or waiting for the approval. */
    public record PriceChange(SalesInvoiceLine line, BigDecimal listPrice, BigDecimal price, BigDecimal discount,
                              SaleApproval approval) {
    }

    /** How far a custom size is: pieces ordered, handed over, given up (cancelled, POS-09) and still to hand over. */
    public record Progress(int ordered, int delivered, int cancelled) {

        public int getRemaining() {
            return Math.max(ordered - delivered - cancelled, 0);
        }
    }

    // ---------------------------------------------------------------- reading

    /** The sale the till is ringing up, if any. */
    public Optional<SalesInvoice> cart(TillSession session) {
        return repo.findDraftOfTill(session.getId());
    }

    /** An invoice's number, for a link to it (a cutting job cut for a sale). */
    public Optional<String> numberOf(UUID id) {
        return id == null ? Optional.empty() : repo.findById(id).map(SalesInvoice::getNumber);
    }

    public SalesInvoice findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("SalesInvoice", id));
    }

    public List<SalesPayment> payments(UUID invoiceId) {
        return paymentRepo.findByInvoiceIdOrderByLineNo(invoiceId);
    }

    /** VAT per tax letter and the totals of a sale's lines (TAX-01). */
    public Vat.Totals totals(SalesInvoice invoice) {
        return Vat.totals(invoice.getLines().stream().map(l -> new Vat.Line(l.getTaxCode(), l.getVatRate(), l.getAmount())).toList());
    }

    /** Issued invoices, newest first; only those with a balance due (POS-08) if asked. */
    public Page<SalesInvoice> findPage(String search, boolean balanceDue, int page, int size) {
        Specification<SalesInvoice> spec = (root, query, cb) -> {
            Predicate p = cb.equal(root.get("status"), SalesInvoiceStatus.POSTED);
            if (balanceDue) {
                p = cb.and(p, cb.greaterThan(root.get("balanceDue"), BigDecimal.ZERO));
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("customer").get("name")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("buyerName"), "")), term),
                        cb.like(cb.coalesce(root.get("buyerTin"), ""), term),
                        cb.like(cb.lower(root.get("postedBy")), term)));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "postedAt")));
    }

    /** The issued invoices of a till session, in the order they were paid. */
    public List<SalesInvoice> invoicesOf(TillSession session) {
        return repo.findByTillSession_IdAndStatusOrderByPostedAtAsc(session.getId(), SalesInvoiceStatus.POSTED);
    }

    /** Balances of orders paid in a till session (POS-08), in the order they were taken. */
    public List<BalanceTaken> balancesTaken(TillSession session) {
        List<SalesPayment> payments = paymentRepo.findByTillSessionIdAndBalancePaymentTrueOrderByCreatedAtAscLineNoAsc(session.getId());
        Map<UUID, String> numbers = repo.findAllById(payments.stream().map(SalesPayment::getInvoiceId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesInvoice::getId, SalesInvoice::getNumber));
        return payments.stream().map(p -> new BalanceTaken(p, numbers.get(p.getInvoiceId()))).toList();
    }

    /**
     * The smallest deposit the sale takes as an order (POS-08): the Settings percentage of its total, and the glass from
     * stock in full (it leaves at once). Null when it has no size to cut: then it is paid in full.
     */
    public BigDecimal depositMinimum(SalesInvoice sale) {
        if (sale == null || sale.getLines().stream().noneMatch(SalesInvoiceLine::isCustomPiece)) {
            return null;
        }
        BigDecimal takenNow = sale.getLines().stream().filter(SalesInvoiceLine::isStockUnit).map(SalesInvoiceLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return SalePayments.depositMinimum(totals(sale).gross(), settingService.getDecimal(SettingKey.DEPOSIT_MIN_PERCENT), takenNow);
    }

    /** A sale's approval requests (POS-05, POS-06), oldest first. */
    public List<SaleApproval> approvals(SalesInvoice sale) {
        return sale == null || sale.getId() == null ? List.of() : approvalRepo.findByInvoice_IdOrderByNumberAsc(sale.getId());
    }

    /**
     * The signed-in user's discount limit (POS-06): the largest of their active roles' limits, a role without its own
     * taking the Settings value.
     */
    public BigDecimal discountLimit() {
        List<BigDecimal> limits = AppUserPrincipal.current().flatMap(u -> userRepo.findById(u.getId()))
                .map(u -> u.getRoles().stream().filter(Role::isEnabled).map(Role::getDiscountLimitPercent).toList())
                .orElse(List.of());
        return Discounts.limitOf(limits, settingService.getDecimal(SettingKey.DISCOUNT_APPROVAL_PERCENT));
    }

    public List<Customer> customers() {
        return customerRepo.findByEnabledTrueOrderByNameAsc();
    }

    public Credit credit(Customer customer) {
        boolean allowed = customer.getType() != CustomerType.WALK_IN && customer.getCreditLimit() != null
                && customer.getCreditLimit().signum() > 0;
        return new Credit(allowed, customer.getCreditLimit() == null ? BigDecimal.ZERO : customer.getCreditLimit(),
                journalService.receivable(customer.getId()));
    }

    /** What each unit would cost this customer, VAT included (null when its glass has no price on their lists). */
    public Map<UUID, BigDecimal> prices(Customer customer, Collection<StockUnit> units) {
        // The units come from another query: their glass (and its tax category) is read again here
        Map<UUID, Product> products = productRepo.findAllById(units.stream().map(u -> u.getProduct().getId()).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, BigDecimal> prices = new HashMap<>();
        for (StockUnit u : units) {
            Product product = products.get(u.getProduct().getId());
            pricing.glass(customer, product, u.getWidthMm(), u.getHeightMm()).ifPresent(g -> prices.put(u.getId(),
                    pricing.amount(g.pricePerM2(), g.chargeableArea(), 1, g.pricesIncludeVat(), g.vatRate())));
        }
        return prices;
    }

    // ---------------------------------------------------------------- the sale being rung up

    /** Adds a unit from stock to the till's sale, by its label code or id (POS-01). */
    @Transactional
    public SalesInvoice addUnit(String code, UUID unitId) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cartOrNew(session);
        StockUnit unit = unitId != null ? unitRepo.findById(unitId).orElse(null) : stockService.findByCode(code).orElse(null);
        if (unit == null) {
            throw BusinessException.onField("code", "sale.unit.unknown", code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
        }
        if (sale.getLines().stream().anyMatch(l -> unit.getId().equals(l.getStockUnitId()))) {
            throw BusinessException.onField("code", "sale.unit.already", unit.getCode());
        }
        requireSellable(unit, sale.getCustomer(), session.getNumber());
        SalesInvoiceLine line = new SalesInvoiceLine();
        line.setInvoice(sale);
        line.setLineNo(sale.getLines().size() + 1);
        line.setKind(SaleLineKind.STOCK_UNIT);
        line.setStockUnitId(unit.getId());
        line.setUnitCode(unit.getCode());
        line.setProduct(unit.getProduct());
        line.setWidthMm(unit.getWidthMm());
        line.setHeightMm(unit.getHeightMm());
        line.setQuantity(1);
        price(line, sale.getCustomer());
        sale.getLines().add(line);
        return sale;
    }

    /** Takes a line off the till's sale; the others keep their order. */
    @Transactional
    public SalesInvoiceLine removeLine(UUID lineId) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cart(session).orElseThrow(() -> BusinessException.of("sale.none"));
        SalesInvoiceLine line = lineOf(sale, lineId);
        Set<UUID> leaving = new HashSet<>();
        leaving.add(line.getId());
        sale.getLines().stream().filter(l -> l.getParentLine() == line).forEach(l -> leaving.add(l.getId()));
        for (SaleApproval a : approvalRepo.findByInvoice_IdOrderByNumberAsc(sale.getId())) {
            if (a.getLineId() != null && leaving.contains(a.getLineId())) {
                if (a.isPending() || a.isApproved()) {
                    withdraw(a, "The line was removed from the sale");
                }
                a.setLineId(null);
            }
        }
        sale.getLines().remove(line);
        sale.getLines().removeIf(l -> l.getParentLine() == line);   // a size takes its processing with it
        if (sale.getLines().isEmpty()) {
            withdrawAll(sale, "The sale was emptied");                // a credit request too: closing the till drops the sale
        }
        renumber(sale);
        return line;
    }

    /** Who the sale is for: the customer (their price list reprices the lines), and the name and TIN printed (TAX-04). */
    @Transactional
    public SalesInvoice setCustomer(UUID customerId, String buyerName, String buyerTin) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cartOrNew(session);
        Customer customer = customerRepo.findById(customerId == null ? sale.getCustomer().getId() : customerId)
                .filter(Customer::isEnabled)
                .orElseThrow(() -> BusinessException.onField("customerId", "sale.customer.invalid"));
        String tin = PartyRules.clean(buyerTin);
        if (tin != null && !tin.matches("\\d{9}")) {
            throw BusinessException.onField("buyerTin", "sale.buyerTin.invalid");
        }
        String name = PartyRules.clean(buyerName);
        if (name != null && name.length() > 100) {
            throw BusinessException.onField("buyerName", "sale.buyerName.size");
        }
        if (!customer.getId().equals(sale.getCustomer().getId())) {
            Map<UUID, StockUnit> units = unitsOf(sale);
            for (SalesInvoiceLine line : sale.getLines()) {
                StockUnit unit = units.get(line.getStockUnitId());
                if (unit != null && unit.getStatus() == StockStatus.RESERVED && unit.getReservedCustomer() != null
                        && !unit.getReservedCustomer().getId().equals(customer.getId())) {
                    throw BusinessException.onField("customerId", "sale.unit.reservedFor", unit.getCode(), unit.getReservedCustomer().getName());
                }
            }
            sale.setCustomer(customer);
            sale.setQuotationId(null);                                   // priced again: no longer at the quotation's prices
            withdrawAll(sale, "The customer changed: the sale was priced again");
            sale.getLines().forEach(l -> {
                if (l.isServiceLine()) {
                    priceService(l, customer);
                } else {
                    price(l, customer);
                }
            });
        }
        sale.setBuyerName(name);
        sale.setBuyerTin(tin != null ? tin : customer.getType() != CustomerType.WALK_IN ? customer.getTin() : null);
        return sale;
    }

    /** Abandons the sale being rung up: its units are free again. */
    @Transactional
    public SalesInvoice cancel() {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cart(session).orElseThrow(() -> BusinessException.of("sale.none"));
        withdrawAll(sale, "The sale was cancelled");
        sale.setStatus(SalesInvoiceStatus.CANCELLED);
        return sale;
    }

    // ---------------------------------------------------------------- payment

    /**
     * Takes the payment and issues the invoice: checks every unit again, splits the total over the methods
     * (customer credit within the limit), sells the units, numbers the invoice and posts its journal.
     */
    @Transactional
    public Paid pay(SalePayments.Entered entered) {
        return pay(entered, false);
    }

    /**
     * Takes the payment, or a deposit on an order (POS-08): a sale with sizes to cut, for a customer known by name, may be
     * paid in part (at least its deposit minimum); the invoice is issued for its whole amount with the rest as its
     * balance due.
     */
    @Transactional
    public Paid pay(SalePayments.Entered entered, boolean deposit) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cart(session).orElseThrow(() -> BusinessException.of("sale.none"));
        if (sale.getLines().isEmpty()) {
            throw BusinessException.of("sale.empty");
        }
        Set<UUID> productIds = sale.getLines().stream().filter(SalesInvoiceLine::isStockUnit).map(l -> l.getProduct().getId())
                .collect(Collectors.toCollection(TreeSet::new));
        List<Product> products = productIds.isEmpty() ? List.of() : productRepo.lockAllById(productIds);
        PostingService.StockValues before = postingService.stockValues(products);
        Map<UUID, StockUnit> units = unitsOf(sale);
        for (SalesInvoiceLine line : sale.getLines()) {
            if (!line.isStockUnit()) {
                continue;
            }
            StockUnit unit = units.get(line.getStockUnitId());
            if (unit == null) {
                throw BusinessException.of("sale.unit.unknown", line.getUnitCode());
            }
            requireSellable(unit, sale.getCustomer(), session.getNumber());
        }

        List<SaleApproval> approvals = approvalRepo.findByInvoice_IdAndStatusIn(sale.getId(),
                List.of(SaleApprovalStatus.PENDING, SaleApprovalStatus.APPROVED));
        String pending = approvals.stream().filter(SaleApproval::isPending).map(SaleApproval::getNumber).sorted()
                .collect(Collectors.joining(", "));
        if (!pending.isEmpty()) {
            throw BusinessException.of("sale.pay.pending", pending);
        }
        Vat.Totals totals = totals(sale);
        BigDecimal minimum = null;
        if (deposit) {
            minimum = depositMinimum(sale);
            if (minimum == null) {
                throw BusinessException.of("sale.pay.deposit.noSizes");
            }
            // The order is collected later: the counter must know whose it is
            if (sale.getCustomer().isDefaultCustomer() && sale.getBuyerName() == null) {
                throw BusinessException.of("sale.pay.deposit.noName");
            }
        }
        SalePayments.Split split = SalePayments.split(totals.gross(), entered, minimum);
        BigDecimal credit = split.amountOf(PaymentMethod.CREDIT);
        if (credit.signum() > 0) {
            Credit c = credit(sale.getCustomer());
            if (!c.allowed()) {
                throw BusinessException.onField("credit", "sale.pay.credit.notAllowed", sale.getCustomer().getName());
            }
            // Above what the customer has left only as far as a manager approved (POS-05)
            BigDecimal approved = approvedCredit(approvals);
            if (credit.compareTo(c.getAvailable()) > 0 && credit.compareTo(approved) > 0) {
                throw approved.signum() > 0
                        ? BusinessException.onField("credit", "sale.pay.credit.overApproved", approved, c.getAvailable())
                        : BusinessException.onField("credit", "sale.pay.credit.over", c.getAvailable(), c.limit());
            }
        }

        String number = numbers.next(DocumentType.INVOICE);
        for (SalesInvoiceLine line : sale.getLines()) {
            if (line.isStockUnit()) {
                stockService.sell(units.get(line.getStockUnitId()), sale.getId(), number);
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String username = AppUserPrincipal.currentUsername();
        List<SalesPayment> payments = new ArrayList<>();
        int no = 1;
        for (SalePayments.Part part : split.parts()) {
            SalesPayment payment = new SalesPayment();
            payment.setInvoiceId(sale.getId());
            payment.setLineNo(no++);
            payment.setMethod(part.method());
            payment.setAmount(part.amount());
            payment.setReference(part.reference());
            payment.setTillSessionId(session.getId());
            payment.setCashTendered(part.method() == PaymentMethod.CASH ? split.cashTendered() : null);
            payment.setCreatedAt(now);
            payment.setUsername(username);
            payments.add(paymentRepo.save(payment));
        }
        // Status and the fields its check needs, together, after the queries
        sale.setNumber(number);
        sale.setBalanceDue(split.balance());
        sale.setInvoiceDate(now.toLocalDate());
        sale.setNetAmount(totals.net());
        sale.setVatAmount(totals.vat());
        sale.setTotalAmount(totals.gross());
        sale.setCashTendered(split.cashTendered());
        sale.setChangeGiven(split.cashTendered() == null ? null : split.change());
        sale.setPostedAt(now);
        sale.setPostedBy(username);
        sale.setStatus(SalesInvoiceStatus.POSTED);
        if (sale.getQuotationId() != null) {
            quotationRepo.findById(sale.getQuotationId()).filter(Quotation::isSent).ifPresent(q -> {
                q.setInvoiceId(sale.getId());
                q.setConvertedAt(now);
                q.setStatus(QuotationStatus.CONVERTED);
            });
        }
        JournalEntry journal = postingService.sale(sale, payments, before);
        return new Paid(sale, split.change(), journal, createJobs(sale));
    }

    /**
     * The cutting jobs of a paid sale's sizes (POS-02, SRS 5.3 step 3): one per glass, for the customer, its lines
     * the sizes with their processing and mark, each linked to its invoice line.
     */
    private List<CuttingJob> createJobs(SalesInvoice sale) {
        Map<UUID, List<SalesInvoiceLine>> byGlass = new LinkedHashMap<>();
        sale.getLines().stream().filter(SalesInvoiceLine::isCustomPiece)
                .forEach(l -> byGlass.computeIfAbsent(l.getProduct().getId(), k -> new ArrayList<>()).add(l));
        List<CuttingJob> jobs = new ArrayList<>();
        for (Map.Entry<UUID, List<SalesInvoiceLine>> e : byGlass.entrySet()) {
            CuttingJobDto dto = new CuttingJobDto();
            dto.setPurpose(CuttingPurpose.CUSTOMER);
            dto.setCustomerId(sale.getCustomer().getId());
            dto.setCustomerRef(sale.getNumber());
            dto.setProductId(e.getKey());
            dto.setNotes("Invoice " + sale.getNumber());
            for (SalesInvoiceLine size : e.getValue()) {
                CuttingJobDto.Line row = new CuttingJobDto.Line();
                row.setWidthMm(size.getWidthMm());
                row.setHeightMm(size.getHeightMm());
                row.setQuantity(size.getQuantity());
                row.setProcessing(sale.getLines().stream().filter(l -> l.getParentLine() == size)
                        .map(l -> l.getService().getCode()).toList());
                row.setMark(size.getMark());
                dto.getLines().add(row);
            }
            CuttingJob job = cuttingJobService.create(dto);
            job.setSalesInvoiceId(sale.getId());
            for (int i = 0; i < job.getLines().size(); i++) {
                job.getLines().get(i).setSalesLineId(e.getValue().get(i).getId());
            }
            jobs.add(job);
        }
        return jobs;
    }

    /**
     * Takes the balance of an order (POS-08) in the signed-in cashier's till, in full, split over cash, mobile money, card
     * and bank transfer (it is already on the customer's account: no credit). Locks the till, then the invoice.
     */
    @Transactional
    public BalancePaid payBalance(UUID invoiceId, SalePayments.Entered entered) {
        TillSession session = tillService.lockCurrent();
        repo.lockById(invoiceId).orElseThrow(() -> new NotFoundException("SalesInvoice", invoiceId));
        SalesInvoice invoice = findDetailed(invoiceId);
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED) {
            throw new NotFoundException("SalesInvoice", invoiceId);
        }
        if (!invoice.hasBalanceDue()) {
            throw BusinessException.of("sale.balance.none", invoice.getNumber());
        }
        if (entered.credit() != null && entered.credit().signum() > 0) {
            throw BusinessException.onField("credit", "sale.balance.noCredit");
        }
        BigDecimal due = invoice.getBalanceDue();
        SalePayments.Split split = SalePayments.split(due, entered);
        int no = paymentRepo.findByInvoiceIdOrderByLineNo(invoice.getId()).stream().mapToInt(SalesPayment::getLineNo).max().orElse(0) + 1;
        LocalDateTime now = LocalDateTime.now(clock);
        String username = AppUserPrincipal.currentUsername();
        List<SalesPayment> payments = new ArrayList<>();
        for (SalePayments.Part part : split.parts()) {
            SalesPayment payment = new SalesPayment();
            payment.setInvoiceId(invoice.getId());
            payment.setLineNo(no++);
            payment.setMethod(part.method());
            payment.setAmount(part.amount());
            payment.setReference(part.reference());
            payment.setTillSessionId(session.getId());
            payment.setBalancePayment(true);
            payment.setCashTendered(part.method() == PaymentMethod.CASH ? split.cashTendered() : null);
            payment.setCreatedAt(now);
            payment.setUsername(username);
            payments.add(paymentRepo.save(payment));
        }
        invoice.setBalanceDue(BigDecimal.ZERO.setScale(2));
        JournalEntry journal = postingService.saleBalance(invoice, payments);
        return new BalancePaid(invoice, due, split.change(), journal);
    }

    // ---------------------------------------------------------------- price changes and credit (POS-05, POS-06)

    /**
     * Changes a line's price with a reason (POS-06). Back to the list price: no reason, any approved change is dropped.
     * A discount within the cashier's limit (or a higher price) applies at once; above it the line keeps its price and a
     * request waits for another person's approval.
     */
    @Transactional
    public PriceChange changePrice(UUID lineId, BigDecimal price, String reason) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cart(session).orElseThrow(() -> BusinessException.of("sale.none"));
        SalesInvoiceLine line = lineOf(sale, lineId);
        List<SaleApproval> requests = approvalRepo.findByInvoice_IdAndStatusIn(sale.getId(),
                List.of(SaleApprovalStatus.PENDING, SaleApprovalStatus.APPROVED));
        if (requests.stream().anyMatch(a -> a.isPending() && lineId.equals(a.getLineId()))) {
            throw BusinessException.of("sale.price.pending", line.getLabel());
        }
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0) {
            throw BusinessException.onField("price", "sale.price.invalid");
        }
        BigDecimal newPrice = price.setScale(2, RoundingMode.HALF_UP);
        BigDecimal list = listPriceOf(line);
        Optional<SaleApproval> applied = requests.stream().filter(a -> a.isApproved() && lineId.equals(a.getLineId())).findFirst();
        if (newPrice.compareTo(list) == 0) {
            applied.ifPresent(a -> withdraw(a, "Back to the list price"));
            setPrice(line, list, null, null);
            return new PriceChange(line, list, list, BigDecimal.ZERO.setScale(2), null);
        }
        String why = PartyRules.clean(reason);
        if (why == null) {
            throw BusinessException.onField("reason", "sale.price.reason");
        }
        if (why.length() > 200) {
            throw BusinessException.onField("reason", "sale.price.reasonSize");
        }
        BigDecimal discount = Discounts.percent(list, newPrice);
        BigDecimal limit = discountLimit();
        if (!Discounts.needsApproval(discount, limit)) {
            applied.ifPresent(a -> withdraw(a, "Replaced by a new price"));
            setPrice(line, newPrice, list, why);
            return new PriceChange(line, list, newPrice, discount, null);
        }
        SaleApproval request = newRequest(sale, SaleApprovalKind.PRICE, line.getLabel(), why);
        request.setLineId(line.getId());
        request.setListPrice(list);
        request.setRequestedPrice(newPrice);
        request.setDiscountPercent(discount);
        request.setLimitPercent(limit);
        request.setAmountBefore(line.getAmount());
        request.setAmountAfter(amountAt(line, newPrice));
        return new PriceChange(line, list, newPrice, discount, waiting(approvalRepo.save(request)));
    }

    /**
     * Asks a manager to approve customer credit above what the customer has left (POS-05): the credit wanted on this
     * sale, with the limit and what the customer owes now. A newer request replaces an approved one.
     */
    @Transactional
    public SaleApproval requestCredit(BigDecimal amount, String reason) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cart(session).orElseThrow(() -> BusinessException.of("sale.none"));
        if (sale.getLines().isEmpty()) {
            throw BusinessException.of("sale.empty");
        }
        Credit c = credit(sale.getCustomer());
        if (!c.allowed()) {
            throw BusinessException.onField("credit", "sale.pay.credit.notAllowed", sale.getCustomer().getName());
        }
        BigDecimal total = totals(sale).gross();
        if (amount == null || amount.signum() <= 0) {
            throw BusinessException.onField("credit", "sale.credit.amount");
        }
        if (amount.compareTo(total) > 0) {
            throw BusinessException.onField("credit", "sale.credit.overTotal", total);
        }
        if (amount.compareTo(c.getAvailable()) <= 0) {
            throw BusinessException.onField("credit", "sale.credit.withinLimit", c.getAvailable());
        }
        String why = PartyRules.clean(reason);
        if (why == null) {
            throw BusinessException.onField("creditReason", "sale.credit.reason");
        }
        if (why.length() > 200) {
            throw BusinessException.onField("creditReason", "sale.price.reasonSize");
        }
        List<SaleApproval> requests = approvalRepo.findByInvoice_IdAndStatusIn(sale.getId(),
                List.of(SaleApprovalStatus.PENDING, SaleApprovalStatus.APPROVED));
        Optional<SaleApproval> waiting = requests.stream().filter(a -> a.isCredit() && a.isPending()).findFirst();
        if (waiting.isPresent()) {
            throw BusinessException.onField("credit", "sale.credit.pending", waiting.get().getNumber());
        }
        SaleApproval request = newRequest(sale, SaleApprovalKind.CREDIT,
                sale.getCustomer().getName() + " · " + sale.getCustomer().getCode(), why);
        requests.stream().filter(a -> a.isCredit() && a.isApproved()).forEach(a -> withdraw(a, "Replaced by a new request"));
        request.setCreditLimit(c.limit());
        request.setOwed(c.owed());
        request.setCreditAmount(amount.setScale(2, RoundingMode.HALF_UP));
        return waiting(approvalRepo.save(request));
    }

    /** The cashier takes back a request of the till's sale that is still pending, with a reason. */
    @Transactional
    public SaleApproval withdrawRequest(UUID approvalId, String reason) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cart(session).orElseThrow(() -> BusinessException.of("sale.none"));
        approvalRepo.lockById(approvalId).orElseThrow(() -> new NotFoundException("SaleApproval", approvalId));
        SaleApproval request = approvalRepo.findDetailedById(approvalId)
                .filter(a -> a.getInvoice().getId().equals(sale.getId()))
                .orElseThrow(() -> new NotFoundException("SaleApproval", approvalId));
        if (!request.isPending()) {
            throw BusinessException.of("saleApproval.notPending", request.getNumber());
        }
        String why = PartyRules.clean(reason);
        if (why == null || why.length() > 200) {
            throw BusinessException.of("po.reason.required");
        }
        withdraw(request, why);
        return request;
    }

    /**
     * Sets a line's price and its amount (whole RWF, VAT included as the line's list says); a changed price keeps the
     * list price it replaced and the reason. Used by the approval of a price change too.
     */
    void setPrice(SalesInvoiceLine line, BigDecimal price, BigDecimal listPrice, String reason) {
        if (line.isServiceLine()) {
            line.setServiceUnitPrice(price);
        } else {
            line.setPricePerM2(price);
        }
        line.setListPrice(listPrice);
        line.setPriceReason(reason);
        line.setAmount(amountAt(line, price));
    }

    /** The list price of a line, whatever it is charged at. */
    static BigDecimal listPriceOf(SalesInvoiceLine line) {
        return line.getListPrice() != null ? line.getListPrice() : line.getPrice();
    }

    /** The credit a manager approved on a sale, 0 when none. */
    static BigDecimal approvedCredit(Collection<SaleApproval> approvals) {
        return approvals.stream().filter(a -> a.isCredit() && a.isApproved()).map(SaleApproval::getCreditAmount)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    /** What a line would come to at another price. */
    private BigDecimal amountAt(SalesInvoiceLine line, BigDecimal price) {
        return line.isServiceLine()
                ? pricing.amount(price, line.getServiceQuantity(), 1, line.isPricesIncludeVat(), line.getVatRate())
                : pricing.amount(price, line.getChargeableAreaM2(), line.getQuantity(), line.isPricesIncludeVat(), line.getVatRate());
    }

    /** Tells the approvers a counter request waits (RPT-06): the POS asks every 8 s, the approver is told once. */
    private SaleApproval waiting(SaleApproval request) {
        notifier.holders("APPROVE_SALE", request.getRequestedById(), NotificationKind.APPROVAL, "notify.saleApproval.waiting",
                "notify.saleApproval.waitingText", "/sale-approvals/" + request.getId(), request.getNumber(), request.getRequestedBy(),
                request.getSubject());
        return request;
    }

    private SaleApproval newRequest(SalesInvoice sale, SaleApprovalKind kind, String subject, String reason) {
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        SaleApproval request = new SaleApproval();
        request.setNumber(numbers.next(DocumentType.SALE_APPROVAL));
        request.setKind(kind);
        request.setInvoice(sale);
        request.setCustomer(sale.getCustomer());
        request.setSubject(subject.length() > 200 ? subject.substring(0, 200) : subject);
        request.setReason(reason);
        request.setRequestedBy(user.map(AppUserPrincipal::getUsername).orElse("system"));
        request.setRequestedById(user.map(AppUserPrincipal::getId).orElse(null));
        return request;
    }

    /** Takes back a request that is pending, or approved but no longer used, with the note why. */
    void withdraw(SaleApproval request, String note) {
        request.setStatus(SaleApprovalStatus.WITHDRAWN);
        request.setDecidedBy(AppUserPrincipal.current().map(AppUserPrincipal::getUsername).orElse("system"));
        request.setDecidedAt(LocalDateTime.now(clock));
        request.setDecisionNote(note);
    }

    private void withdrawAll(SalesInvoice sale, String note) {
        if (sale.getId() == null) {
            return;
        }
        approvalRepo.findByInvoice_IdAndStatusIn(sale.getId(), List.of(SaleApprovalStatus.PENDING, SaleApprovalStatus.APPROVED))
                .forEach(a -> withdraw(a, note));
    }

    private static SalesInvoiceLine lineOf(SalesInvoice sale, UUID lineId) {
        return sale.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new NotFoundException("SalesInvoiceLine", lineId));
    }

    // ---------------------------------------------------------------- quotations (POS-03)

    /**
     * Rings a sent quotation up at the cashier's till, into an empty sale: its customer and the name and TIN printed;
     * whole sheets as units of that glass and size from stock (available, held by nothing, the first labels); sizes to
     * cut with their processing. Lines are charged the quotation's prices: where one differs from today's list price,
     * the line keeps the list price with the quotation as its reason (the quotation's author gave the discount within
     * their limit). The quotation is converted when the sale is paid.
     */
    @Transactional
    public SalesInvoice ringUp(UUID quotationId) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cartOrNew(session);
        if (!sale.getLines().isEmpty()) {
            throw BusinessException.of("sale.quote.saleNotEmpty");
        }
        quotationRepo.lockById(quotationId).orElseThrow(() -> new NotFoundException("Quotation", quotationId));
        Quotation q = quotationRepo.findDetailedById(quotationId).orElseThrow(() -> new NotFoundException("Quotation", quotationId));
        if (!q.isSent()) {
            throw BusinessException.of("sale.quote.notSent", q.getNumber());
        }
        if (q.isExpiredOn(today())) {
            throw BusinessException.of("sale.quote.expired", q.getNumber(), q.getValidUntil().format(QuotationService.DAY));
        }
        Optional<SalesInvoice> elsewhere = repo.findFirstByQuotationIdAndStatus(q.getId(), SalesInvoiceStatus.DRAFT)
                .filter(i -> !i.getId().equals(sale.getId()));
        if (elsewhere.isPresent()) {
            throw BusinessException.of("sale.quote.onTill", q.getNumber(), elsewhere.get().getTillSession().getNumber());
        }
        Customer customer = q.getCustomer();
        if (!customer.isEnabled()) {
            throw BusinessException.of("sale.customer.invalid");
        }
        // The units of its whole sheets first, so a shortage changes nothing
        Map<QuotationLine, List<StockUnit>> sheets = new HashMap<>();
        Set<UUID> taken = new HashSet<>();
        for (QuotationLine ql : q.getLines()) {
            if (!ql.isSheet()) {
                continue;
            }
            List<StockUnit> found = unitRepo.findOfSize(ql.getProduct().getId(), ql.getWidthMm(), ql.getHeightMm(), StockStatus.AVAILABLE);
            Map<UUID, String> held = found.isEmpty() ? Map.of() : stockService.holds(found.stream().map(StockUnit::getId).toList());
            List<StockUnit> free = found.stream().filter(u -> !taken.contains(u.getId()) && !held.containsKey(u.getId()))
                    .limit(ql.getQuantity()).toList();
            if (free.size() < ql.getQuantity()) {
                throw BusinessException.of("sale.quote.noSheets", ql.getProduct().getCode(), ql.getWidthMm() + " x " + ql.getHeightMm(),
                        ql.getQuantity(), free.size());
            }
            free.forEach(u -> taken.add(u.getId()));
            sheets.put(ql, free);
        }
        sale.setCustomer(customer);
        sale.setBuyerName(q.getBuyerName());
        sale.setBuyerTin(q.getBuyerTin());
        sale.setQuotationId(q.getId());
        String reason = "Quotation " + q.getNumber();
        for (QuotationLine ql : q.getLines()) {
            if (ql.isSheet()) {
                for (StockUnit unit : sheets.get(ql)) {
                    SalesInvoiceLine line = new SalesInvoiceLine();
                    line.setInvoice(sale);
                    line.setKind(SaleLineKind.STOCK_UNIT);
                    line.setStockUnitId(unit.getId());
                    line.setUnitCode(unit.getCode());
                    line.setProduct(unit.getProduct());
                    line.setWidthMm(unit.getWidthMm());
                    line.setHeightMm(unit.getHeightMm());
                    line.setQuantity(1);
                    price(line, customer);
                    quotedPrice(line, ql.getPrice(), reason);
                    sale.getLines().add(line);
                }
            } else if (ql.isCustomPiece()) {
                SalesInvoiceLine piece = new SalesInvoiceLine();
                piece.setInvoice(sale);
                piece.setKind(SaleLineKind.CUSTOM_PIECE);
                piece.setProduct(ql.getProduct());
                piece.setWidthMm(ql.getWidthMm());
                piece.setHeightMm(ql.getHeightMm());
                piece.setQuantity(ql.getQuantity());
                piece.setMark(ql.getMark());
                piece.setProcessing(ql.getProcessing());
                price(piece, customer);
                quotedPrice(piece, ql.getPrice(), reason);
                sale.getLines().add(piece);
                for (QuotationLine qs : q.getLines()) {
                    if (qs.getParentLine() != ql) {
                        continue;
                    }
                    SalesInvoiceLine line = new SalesInvoiceLine();
                    line.setInvoice(sale);
                    line.setKind(SaleLineKind.SERVICE);
                    line.setParentLine(piece);
                    line.setService(qs.getService());
                    line.setProduct(ql.getProduct());
                    line.setWidthMm(ql.getWidthMm());
                    line.setHeightMm(ql.getHeightMm());
                    line.setQuantity(ql.getQuantity());
                    line.setHoles(qs.getHoles());
                    priceService(line, customer);
                    quotedPrice(line, qs.getPrice(), reason);
                    sale.getLines().add(line);
                }
            }
        }
        renumber(sale);
        return sale;
    }

    /** Charges a line the quotation's price; a price other than today's list price keeps the list price and the reason. */
    private void quotedPrice(SalesInvoiceLine line, BigDecimal quoted, String reason) {
        BigDecimal list = line.getPrice();
        if (quoted.compareTo(list) != 0) {
            setPrice(line, quoted, list, reason);
        }
    }

    // ---------------------------------------------------------------- sizes to cut (POS-02)

    /** Adds a size to cut to the till's sale, priced by chargeable area, with its processing as service lines. */
    @Transactional
    public SalesInvoice addCustom(CustomSize c) {
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cartOrNew(session);
        Product product = c.productId() == null ? null : productRepo.findById(c.productId()).filter(Product::isEnabled).orElse(null);
        if (product == null) {
            throw BusinessException.onField("productId", "sale.custom.product");
        }
        if (!product.getGlassType().isCuttable()) {
            throw BusinessException.onField("productId", "sale.custom.notCuttable", product.getCode());
        }
        int width = size(c.widthMm(), "widthMm");
        int height = size(c.heightMm(), "heightMm");
        if (c.quantity() == null || c.quantity() < 1 || c.quantity() > 999) {
            throw BusinessException.onField("quantity", "sale.custom.quantity");
        }
        String mark = PartyRules.clean(c.mark());
        if (mark != null && mark.length() > 60) {
            throw BusinessException.onField("mark", "sale.custom.markSize");
        }
        List<UUID> ids = c.serviceIds() == null ? List.of() : c.serviceIds().stream().filter(Objects::nonNull).distinct().toList();
        List<ProcessingService> services = serviceRepo.findAllById(ids).stream().filter(ProcessingService::isEnabled)
                .sorted(Comparator.comparing(ProcessingService::getCode)).toList();
        if (services.size() != ids.size()) {
            throw BusinessException.onField("serviceIds", "sale.custom.service");
        }
        boolean perHole = services.stream().anyMatch(s -> s.getChargeUnit() == ChargeUnit.HOLE);
        if (perHole && (c.holes() == null || c.holes() < 1 || c.holes() > 50)) {
            throw BusinessException.onField("holes", "sale.custom.holes");
        }

        SalesInvoiceLine piece = new SalesInvoiceLine();
        piece.setInvoice(sale);
        piece.setKind(SaleLineKind.CUSTOM_PIECE);
        piece.setProduct(product);
        piece.setWidthMm(width);
        piece.setHeightMm(height);
        piece.setQuantity(c.quantity());
        piece.setMark(mark);
        piece.setProcessing(services.isEmpty() ? null : services.stream().map(ProcessingService::getCode).collect(Collectors.joining(",")));
        price(piece, sale.getCustomer());
        sale.getLines().add(piece);
        for (ProcessingService service : services) {
            SalesInvoiceLine line = new SalesInvoiceLine();
            line.setInvoice(sale);
            line.setKind(SaleLineKind.SERVICE);
            line.setParentLine(piece);
            line.setService(service);
            line.setProduct(product);
            line.setWidthMm(width);
            line.setHeightMm(height);
            line.setQuantity(c.quantity());
            line.setHoles(service.getChargeUnit() == ChargeUnit.HOLE ? c.holes() : null);
            priceService(line, sale.getCustomer());
            sale.getLines().add(line);
        }
        renumber(sale);
        return sale;
    }

    /** The cutting jobs of an invoice's sizes, "Cut the rest" jobs included. */
    public List<CuttingJob> jobsOf(SalesInvoice invoice) {
        return jobRepo.findBySalesInvoiceIdOrderByNumberAsc(invoice.getId());
    }

    public List<SalesDelivery> deliveries(UUID invoiceId) {
        return deliveryRepo.findByInvoiceIdOrderByDeliveredAtAscUnitCodeAsc(invoiceId);
    }

    /** Each size of an invoice: pieces ordered, handed over and given up. */
    public Map<UUID, Progress> progress(SalesInvoice invoice) {
        Map<UUID, Long> delivered = deliveries(invoice.getId()).stream()
                .collect(Collectors.groupingBy(SalesDelivery::getLineId, Collectors.counting()));
        List<UUID> sizes = invoice.getLines().stream().filter(SalesInvoiceLine::isCustomPiece).map(SalesInvoiceLine::getId)
                .filter(Objects::nonNull).toList();
        Map<UUID, Integer> cancelled = new HashMap<>();
        if (!sizes.isEmpty()) {
            for (Object[] row : creditLineRepo.cancelledPieces(sizes)) {
                cancelled.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        Map<UUID, Progress> progress = new LinkedHashMap<>();
        invoice.getLines().stream().filter(SalesInvoiceLine::isCustomPiece)
                .forEach(l -> progress.put(l.getId(), new Progress(l.getQuantity(), delivered.getOrDefault(l.getId(), 0L).intValue(),
                        cancelled.getOrDefault(l.getId(), 0))));
        return progress;
    }

    /**
     * Pieces ready to hand over: cut by the invoice's own cutting jobs ("Cut the rest" ones included) for a size with pieces
     * left to hand over, and still reserved. Pieces of the same size cut for another sale are not this sale's.
     */
    public List<StockUnit> readyPieces(SalesInvoice invoice) {
        Map<UUID, Progress> progress = progress(invoice);
        Map<UUID, UUID> pieces = piecesOf(invoice);
        if (pieces.isEmpty()) {
            return List.of();
        }
        return unitRepo.findByIdIn(pieces.keySet()).stream()
                .filter(u -> u.getStatus() == StockStatus.RESERVED)
                .filter(u -> progress.containsKey(pieces.get(u.getId())) && progress.get(pieces.get(u.getId())).getRemaining() > 0)
                .sorted(Comparator.comparing(StockUnit::getCode))
                .toList();
    }

    /** The pieces cut for an invoice: unit id -> the invoice line (size) its cutting job line was made for. */
    Map<UUID, UUID> piecesOf(SalesInvoice invoice) {
        List<UUID> jobIds = jobsOf(invoice).stream().map(CuttingJob::getId).toList();
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> salesLineOf = new HashMap<>();
        jobLineRepo.findByJob_IdIn(jobIds).stream().filter(l -> l.getSalesLineId() != null)
                .forEach(l -> salesLineOf.put(l.getId(), l.getSalesLineId()));
        Map<UUID, UUID> pieces = new LinkedHashMap<>();
        for (CuttingJobOutput o : outputRepo.findByCuttingJobIdIn(jobIds)) {
            if (o.getKind() == CuttingOutputKind.PIECE && o.getStockUnitId() != null && salesLineOf.containsKey(o.getJobLineId())) {
                pieces.put(o.getStockUnitId(), salesLineOf.get(o.getJobLineId()));
            }
        }
        return pieces;
    }

    /**
     * Hands over pieces of an invoice's sizes (SRS 5.3 step 5): each must have been cut by the invoice's own cutting jobs,
     * still be reserved, and its size have pieces left to hand over. The units are sold and their cost at MAC posted.
     */
    @Transactional
    public Delivered deliver(UUID invoiceId, Collection<UUID> unitIds, String codes) {
        SalesInvoice invoice = repo.lockById(invoiceId).orElseThrow(() -> new NotFoundException("SalesInvoice", invoiceId));
        invoice = findDetailed(invoiceId);
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED) {
            throw BusinessException.of("sale.deliver.notIssued");
        }
        // An order paid by a deposit is collected once its balance is paid (POS-08)
        if (invoice.hasBalanceDue()) {
            throw BusinessException.of("sale.deliver.balanceDue", invoice.getNumber(), invoice.getBalanceDue());
        }
        Map<UUID, StockUnit> chosen = new LinkedHashMap<>();
        if (unitIds != null && !unitIds.isEmpty()) {
            unitRepo.findAllById(unitIds).forEach(u -> chosen.put(u.getId(), u));
        }
        List<String> scanned = StockTransferService.parseCodes(codes);
        if (!scanned.isEmpty()) {
            Map<String, StockUnit> byCode = unitRepo.findByCodeIn(scanned).stream()
                    .collect(Collectors.toMap(StockUnit::getCode, Function.identity()));
            for (String code : scanned) {
                StockUnit unit = byCode.get(code);
                if (unit == null) {
                    throw BusinessException.onField("codes", "sale.unit.unknown", code);
                }
                chosen.put(unit.getId(), unit);
            }
        }
        if (chosen.isEmpty()) {
            throw BusinessException.onField("codes", "sale.deliver.none");
        }
        Map<UUID, Integer> left = new HashMap<>();
        progress(invoice).forEach((lineId, p) -> left.put(lineId, p.getRemaining()));
        Map<UUID, UUID> pieces = piecesOf(invoice);
        Map<UUID, SalesInvoiceLine> lines = invoice.getLines().stream().filter(l -> l.getId() != null)
                .collect(Collectors.toMap(SalesInvoiceLine::getId, Function.identity()));
        Map<StockUnit, SalesInvoiceLine> plan = new LinkedHashMap<>();
        Map<UUID, String> holds = stockService.holds(chosen.keySet());
        for (StockUnit unit : chosen.values()) {
            SalesInvoiceLine line = lines.get(pieces.get(unit.getId()));
            if (line == null) {
                throw BusinessException.onField("codes", "sale.deliver.notThisSale", unit.getCode(), invoice.getNumber());
            }
            if (unit.getStatus() != StockStatus.RESERVED) {
                throw BusinessException.onField("codes", "sale.deliver.notReady", unit.getCode(),
                        new DefaultMessageSourceResolvable("stock.status." + unit.getStatus().name()));
            }
            if (holds.containsKey(unit.getId())) {
                throw BusinessException.onField("codes", "sale.unit.held", unit.getCode(), holds.get(unit.getId()));
            }
            if (left.getOrDefault(line.getId(), 0) <= 0) {
                throw BusinessException.onField("codes", "sale.deliver.noSize", unit.getCode());
            }
            left.merge(line.getId(), -1, Integer::sum);
            plan.put(unit, line);
        }
        List<Product> products = productRepo.lockAllById(plan.keySet().stream().map(u -> u.getProduct().getId())
                .collect(Collectors.toCollection(TreeSet::new)));
        PostingService.StockValues before = postingService.stockValues(products);
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        for (Map.Entry<StockUnit, SalesInvoiceLine> e : plan.entrySet()) {
            StockUnit unit = e.getKey();
            stockService.sell(unit, invoice.getId(), invoice.getNumber());
            SalesDelivery delivery = new SalesDelivery();
            delivery.setInvoiceId(invoice.getId());
            delivery.setLineId(e.getValue().getId());
            delivery.setStockUnitId(unit.getId());
            delivery.setUnitCode(unit.getCode());
            delivery.setDeliveredAt(now);
            delivery.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
            delivery.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
            deliveryRepo.save(delivery);
        }
        JournalEntry journal = postingService.saleDelivery(invoice, before);
        return new Delivered(invoice, new ArrayList<>(plan.keySet()), journal);
    }

    private static int size(Integer mm, String field) {
        if (mm == null || mm < 1 || mm > 10000) {
            throw BusinessException.onField(field, "sale.custom.size");
        }
        return mm;
    }

    private static void renumber(SalesInvoice sale) {
        int no = 1;
        for (SalesInvoiceLine l : sale.getLines()) {
            l.setLineNo(no++);
        }
    }

    // ---------------------------------------------------------------- helpers

    private SalesInvoice cartOrNew(TillSession session) {
        return cart(session).orElseGet(() -> {
            SalesInvoice sale = new SalesInvoice();
            sale.setTillSession(session);
            sale.setCustomer(customerRepo.findByDefaultCustomerTrue().orElseThrow(() -> BusinessException.of("sale.noWalkIn")));
            return repo.save(sale);
        });
    }

    /** A unit can be sold: available, or reserved for this customer, and held by no other document. */
    private void requireSellable(StockUnit unit, Customer customer, String tillNumber) {
        if (!StockAction.SELL.allows(unit.getStatus())) {
            throw BusinessException.onField("code", "sale.unit.notForSale", unit.getCode(),
                    new DefaultMessageSourceResolvable("stock.status." + unit.getStatus().name()));
        }
        if (unit.getStatus() == StockStatus.RESERVED && unit.getReservedCustomer() != null
                && !unit.getReservedCustomer().getId().equals(customer.getId())) {
            throw BusinessException.onField("code", "sale.unit.reservedFor", unit.getCode(), unit.getReservedCustomer().getName());
        }
        String held = stockService.holds(List.of(unit.getId())).get(unit.getId());
        if (held != null && !held.equals(tillNumber)) {
            throw BusinessException.onField("code", "sale.unit.held", unit.getCode(), held);
        }
        // A piece cut for a paid sale leaves only by being handed over from that invoice (POS-02)
        if (unit.getStatus() == StockStatus.RESERVED) {
            Optional<String> soldOn = outputRepo.findByStockUnitId(unit.getId())
                    .flatMap(o -> jobRepo.findById(o.getCuttingJobId()))
                    .map(CuttingJob::getSalesInvoiceId)
                    .flatMap(this::numberOf);
            if (soldOn.isPresent()) {
                throw BusinessException.onField("code", "sale.unit.cutForSale", unit.getCode(), soldOn.get());
            }
        }
    }

    /** Prices a line for the customer: their list (or the default one), chargeable area, the glass's tax letter (TAX-01). */
    private void price(SalesInvoiceLine line, Customer customer) {
        Product product = line.getProduct();
        LinePricing.Glass g = pricing.glass(customer, product, line.getWidthMm(), line.getHeightMm())
                .orElseThrow(() -> BusinessException.onField("code", "sale.noPrice", product.getCode()));
        line.setChargeableAreaM2(g.chargeableArea());
        line.setPricePerM2(g.pricePerM2());
        line.setPriceList(g.list());
        line.setPricesIncludeVat(g.pricesIncludeVat());
        line.setTaxCode(g.taxCode());
        line.setVatRate(g.vatRate());
        line.setAmount(pricing.amount(g.pricePerM2(), g.chargeableArea(), line.getQuantity(), g.pricesIncludeVat(), g.vatRate()));
        line.setListPrice(null);
        line.setPriceReason(null);
    }

    /**
     * Prices processing for the customer (MD-06): the service's price per its unit (m², metre of edge, piece, hole) from
     * their list or the default one, times what the pieces need; VAT at the standard rate.
     */
    private void priceService(SalesInvoiceLine line, Customer customer) {
        ProcessingService service = line.getService();
        LinePricing.Service s = pricing.service(customer, service, line.getWidthMm(), line.getHeightMm(), line.getQuantity(), line.getHoles())
                .orElseThrow(() -> BusinessException.onField("serviceIds", "sale.custom.noServicePrice", service.getName()));
        line.setServiceQuantity(s.quantity());
        line.setServiceUnitPrice(s.unitPrice());
        line.setPriceList(s.list());
        line.setPricesIncludeVat(s.pricesIncludeVat());
        line.setChargeableAreaM2(null);
        line.setPricePerM2(null);
        line.setTaxCode(s.taxCode());
        line.setVatRate(s.vatRate());
        line.setAmount(pricing.amount(s.unitPrice(), s.quantity(), 1, s.pricesIncludeVat(), s.vatRate()));
        line.setListPrice(null);
        line.setPriceReason(null);
    }

    private Map<UUID, StockUnit> unitsOf(SalesInvoice sale) {
        List<UUID> ids = sale.getLines().stream().map(SalesInvoiceLine::getStockUnitId).filter(Objects::nonNull).toList();
        return unitRepo.findAllById(ids).stream().collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }


    /** Today in Kigali (the invoice date). */
    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
