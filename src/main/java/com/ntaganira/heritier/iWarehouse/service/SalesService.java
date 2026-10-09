package com.ntaganira.heritier.iWarehouse.service;

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
 *               VAT output, and the glass at MAC.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SalesService {

    private final SalesInvoiceRepository repo;
    private final SalesPaymentRepository paymentRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;
    private final CurrencyRepository currencyRepo;
    private final TillService tillService;
    private final StockService stockService;
    private final PriceListService priceListService;
    private final PostingService postingService;
    private final JournalService journalService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public SalesService(SalesInvoiceRepository repo, SalesPaymentRepository paymentRepo, StockUnitRepository unitRepo,
                        ProductRepository productRepo, CustomerRepository customerRepo, CurrencyRepository currencyRepo,
                        TillService tillService, StockService stockService, PriceListService priceListService,
                        PostingService postingService, JournalService journalService, DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.paymentRepo = paymentRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
        this.currencyRepo = currencyRepo;
        this.tillService = tillService;
        this.stockService = stockService;
        this.priceListService = priceListService;
        this.postingService = postingService;
        this.journalService = journalService;
        this.numbers = numbers;
        this.clock = clock;
    }

    /** A customer's credit at the counter (POS-05): allowed for account customers with a limit, what they owe. */
    public record Credit(boolean allowed, BigDecimal limit, BigDecimal owed) {

        public BigDecimal getAvailable() {
            return allowed ? limit.subtract(owed).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        }
    }

    /** An invoice paid: the invoice, its change and its journal. */
    public record Paid(SalesInvoice invoice, BigDecimal change, JournalEntry journal) {
    }

    // ---------------------------------------------------------------- reading

    /** The sale the till is ringing up, if any. */
    public Optional<SalesInvoice> cart(TillSession session) {
        return repo.findFirstByTillSession_IdAndStatus(session.getId(), SalesInvoiceStatus.DRAFT);
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

    /** Issued invoices, newest first. */
    public Page<SalesInvoice> findPage(String search, int page, int size) {
        Specification<SalesInvoice> spec = (root, query, cb) -> {
            Predicate p = cb.equal(root.get("status"), SalesInvoiceStatus.POSTED);
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
        Map<UUID, Optional<PriceListService.UnitPrice>> byProduct = new HashMap<>();
        Map<UUID, BigDecimal> prices = new HashMap<>();
        int decimals = baseDecimals();
        for (StockUnit u : units) {
            Product product = products.get(u.getProduct().getId());
            Optional<PriceListService.UnitPrice> price = byProduct.computeIfAbsent(product.getId(),
                    id -> priceListService.priceFor(customer, product));
            price.ifPresent(p -> {
                BigDecimal area = Pricing.chargeableArea(u.getWidthMm(), u.getHeightMm(), priceListService.minChargeableArea(p.list()));
                prices.put(u.getId(), Vat.lineAmount(p.pricePerM2(), area, 1, p.list().isPricesIncludeVat(),
                        product.getTaxCategory().getRate(), decimals));
            });
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
        SalesInvoiceLine line = sale.getLines().stream().filter(l -> l.getId().equals(lineId)).findFirst()
                .orElseThrow(() -> new NotFoundException("SalesInvoiceLine", lineId));
        sale.getLines().remove(line);
        int no = 1;
        for (SalesInvoiceLine l : sale.getLines()) {
            l.setLineNo(no++);
        }
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
            sale.getLines().forEach(l -> price(l, customer));
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
        TillSession session = tillService.lockCurrent();
        SalesInvoice sale = cart(session).orElseThrow(() -> BusinessException.of("sale.none"));
        if (sale.getLines().isEmpty()) {
            throw BusinessException.of("sale.empty");
        }
        Set<UUID> productIds = sale.getLines().stream().map(l -> l.getProduct().getId()).collect(Collectors.toCollection(TreeSet::new));
        List<Product> products = productRepo.lockAllById(productIds);
        PostingService.StockValues before = postingService.stockValues(products);
        Map<UUID, StockUnit> units = unitsOf(sale);
        for (SalesInvoiceLine line : sale.getLines()) {
            StockUnit unit = units.get(line.getStockUnitId());
            if (unit == null) {
                throw BusinessException.of("sale.unit.unknown", line.getUnitCode());
            }
            requireSellable(unit, sale.getCustomer(), session.getNumber());
        }

        Vat.Totals totals = totals(sale);
        SalePayments.Split split = SalePayments.split(totals.gross(), entered);
        BigDecimal credit = split.amountOf(PaymentMethod.CREDIT);
        if (credit.signum() > 0) {
            Credit c = credit(sale.getCustomer());
            if (!c.allowed()) {
                throw BusinessException.onField("credit", "sale.pay.credit.notAllowed", sale.getCustomer().getName());
            }
            if (credit.compareTo(c.getAvailable()) > 0) {
                throw BusinessException.onField("credit", "sale.pay.credit.over", c.getAvailable(), c.limit());
            }
        }

        String number = numbers.next(DocumentType.INVOICE);
        for (SalesInvoiceLine line : sale.getLines()) {
            stockService.sell(units.get(line.getStockUnitId()), sale.getId(), number);
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
            payment.setCreatedAt(now);
            payment.setUsername(username);
            payments.add(paymentRepo.save(payment));
        }
        // Status and the fields its check needs, together, after the queries
        sale.setNumber(number);
        sale.setInvoiceDate(now.toLocalDate());
        sale.setNetAmount(totals.net());
        sale.setVatAmount(totals.vat());
        sale.setTotalAmount(totals.gross());
        sale.setCashTendered(split.cashTendered());
        sale.setChangeGiven(split.cashTendered() == null ? null : split.change());
        sale.setPostedAt(now);
        sale.setPostedBy(username);
        sale.setStatus(SalesInvoiceStatus.POSTED);
        JournalEntry journal = postingService.sale(sale, payments, before);
        return new Paid(sale, split.change(), journal);
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
    }

    /** Prices a line for the customer: their list (or the default one), chargeable area, the glass's tax letter (TAX-01). */
    private void price(SalesInvoiceLine line, Customer customer) {
        Product product = line.getProduct();
        PriceListService.UnitPrice price = priceListService.priceFor(customer, product)
                .orElseThrow(() -> BusinessException.onField("code", "sale.noPrice", product.getCode()));
        TaxCategory tax = product.getTaxCategory();
        BigDecimal area = Pricing.chargeableArea(line.getWidthMm(), line.getHeightMm(), priceListService.minChargeableArea(price.list()));
        line.setChargeableAreaM2(area);
        line.setPricePerM2(price.pricePerM2());
        line.setPriceList(price.list());
        line.setPricesIncludeVat(price.list().isPricesIncludeVat());
        line.setTaxCode(tax.getEbmCode());
        line.setVatRate(tax.getRate());
        line.setAmount(Vat.lineAmount(price.pricePerM2(), area, line.getQuantity(), price.list().isPricesIncludeVat(), tax.getRate(),
                baseDecimals()));
    }

    private Map<UUID, StockUnit> unitsOf(SalesInvoice sale) {
        List<UUID> ids = sale.getLines().stream().map(SalesInvoiceLine::getStockUnitId).filter(Objects::nonNull).toList();
        return unitRepo.findAllById(ids).stream().collect(Collectors.toMap(StockUnit::getId, Function.identity()));
    }

    private int baseDecimals() {
        return currencyRepo.findByBaseCurrencyTrue().map(Currency::getDecimals).orElse(0);
    }

    /** Today in Kigali (the invoice date). */
    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
