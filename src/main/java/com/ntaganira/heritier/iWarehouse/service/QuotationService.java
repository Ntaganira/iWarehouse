package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.QuotationDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : QuotationService.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Quotations (POS-03) and sales orders (F-01, Annex G, decision D4: a quotation of kind SALES_ORDER
 *               raised by the Order Desk with its channel, market, territory, agent and site, checked against stock,
 *               the price list and the customer's credit, sent to the cashier, who takes payment against it; it lapses
 *               after the Settings' days). A draft is priced each time it is saved, like a sale (LinePricing): whole
 *               sheets and sizes to cut from the customer's list over their chargeable area, the processing of a
 *               size under it, a discount per row within the author's limit (Discounts), VAT per tax letter on the
 *               totals. Sending fixes it (QUO number from the start); a sent quotation is rung up at a till
 *               (SalesService.ringUp) and converted when that sale is paid; a draft or sent one is cancelled with
 *               a reason, unless a till is ringing it up.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class QuotationService {

    /** Dates in messages (NFR-15). */
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final QuotationRepository repo;
    private final CustomerRepository customerRepo;
    private final ProductRepository productRepo;
    private final ProcessingServiceRepository serviceRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final LinePricing pricing;
    private final SalesService salesService;
    private final DocumentNumberService numbers;
    private final SettingService settingService;
    private final TerritoryRepository territoryRepo;
    private final StockUnitRepository unitRepo;
    private final StockService stockService;
    private final Notifier notifier;
    private final Clock clock;

    public QuotationService(QuotationRepository repo, CustomerRepository customerRepo, ProductRepository productRepo,
                            ProcessingServiceRepository serviceRepo, SalesInvoiceRepository invoiceRepo, LinePricing pricing,
                            SalesService salesService, DocumentNumberService numbers, SettingService settingService,
                            TerritoryRepository territoryRepo, StockUnitRepository unitRepo, StockService stockService,
                            Notifier notifier, Clock clock) {
        this.territoryRepo = territoryRepo;
        this.unitRepo = unitRepo;
        this.stockService = stockService;
        this.notifier = notifier;
        this.repo = repo;
        this.customerRepo = customerRepo;
        this.productRepo = productRepo;
        this.serviceRepo = serviceRepo;
        this.invoiceRepo = invoiceRepo;
        this.pricing = pricing;
        this.salesService = salesService;
        this.numbers = numbers;
        this.settingService = settingService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /**
     * Quotations, newest first, by status (EXPIRED = sent and past its date; SENT = sent and still valid) and by
     * number, customer or name printed.
     */
    public Page<Quotation> findPage(String status, String search, int page, int size) {
        return findPage(QuotationKind.QUOTATION, status, search, page, size);
    }

    /** Quotations or sales orders, as above. */
    public Page<Quotation> findPage(QuotationKind kind, String status, String search, int page, int size) {
        LocalDate today = today();
        Specification<Quotation> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("kind"), kind));
            if ("EXPIRED".equals(status)) {
                p.add(cb.equal(root.get("status"), QuotationStatus.SENT));
                p.add(cb.lessThan(root.get("validUntil"), today));
            } else if (StringUtils.hasText(status)) {
                try {
                    QuotationStatus s = QuotationStatus.valueOf(status);
                    p.add(cb.equal(root.get("status"), s));
                    if (s == QuotationStatus.SENT) {
                        p.add(cb.greaterThanOrEqualTo(root.get("validUntil"), today));
                    }
                } catch (IllegalArgumentException ignored) {
                    // an unknown status filters nothing
                }
            }
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("number")), term),
                        cb.like(cb.lower(root.get("customer").get("name")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("buyerName"), "")), term)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.desc("quoteDate"), Sort.Order.desc("number"))));
    }

    public Quotation findDetailed(UUID id) {
        return repo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Quotation", id));
    }

    public Quotation findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Quotation", id));
    }

    /** VAT per tax letter and the totals of a quotation's lines (TAX-01), as on an invoice. */
    public Vat.Totals totals(Quotation q) {
        return Vat.totals(q.getLines().stream().map(l -> new Vat.Line(l.getTaxCode(), l.getVatRate(), l.getAmount())).toList());
    }

    /** The till ringing the quotation up, if one is. */
    public Optional<String> onTill(Quotation q) {
        return invoiceRepo.findFirstByQuotationIdAndStatus(q.getId(), SalesInvoiceStatus.DRAFT).map(i -> i.getTillSession().getNumber());
    }

    /** The invoice it became, for a link. */
    public Optional<String> invoiceNumber(Quotation q) {
        return q.getInvoiceId() == null ? Optional.empty() : invoiceRepo.findById(q.getInvoiceId()).map(SalesInvoice::getNumber);
    }

    public List<Customer> customers() {
        return customerRepo.findByEnabledTrueOrderByNameAsc();
    }

    public List<Product> products() {
        return productRepo.findByEnabledTrueOrderByCodeAsc();
    }

    public List<ProcessingService> services() {
        return serviceRepo.findAll(Sort.by("code")).stream()
                .filter(ProcessingService::isProcessing).filter(ProcessingService::isEnabled).toList();
    }

    /** A new quotation: the walk-in customer, valid for the Settings number of days, one empty row. */
    public QuotationDto newForm() {
        return newForm(QuotationKind.QUOTATION);
    }

    /** A new quotation, or a new order (at the counter, domestic, payable for the Settings' days). */
    public QuotationDto newForm(QuotationKind kind) {
        QuotationDto dto = new QuotationDto();
        customerRepo.findByDefaultCustomerTrue().ifPresent(c -> dto.setCustomerId(c.getId()));
        dto.setValidUntil(defaultValidUntil(kind));
        if (kind == QuotationKind.SALES_ORDER) {
            dto.setChannel(OrderChannel.COUNTER);
            dto.setMarket(Market.DOMESTIC);
            List<Location> sites = sites();
            if (sites.size() == 1) {
                dto.setSiteId(sites.get(0).getId());
            }
        }
        dto.getLines().add(new QuotationDto.Line());
        return dto;
    }

    /** The form of a quotation: its own rows to edit it, or new rows (no ids) to copy it into a new one. */
    public QuotationDto formOf(Quotation q, boolean copy) {
        QuotationDto dto = new QuotationDto();
        dto.setId(copy ? null : q.getId());
        dto.setCustomerId(q.getCustomer().getId());
        dto.setBuyerName(q.getBuyerName());
        dto.setBuyerTin(q.getBuyerTin());
        dto.setValidUntil(copy ? defaultValidUntil(q.getKind()) : q.getValidUntil());
        dto.setNotes(q.getNotes());
        dto.setChannel(q.getChannel());
        dto.setMarket(q.getMarket());
        dto.setTerritoryId(q.getTerritoryId());
        dto.setAgentId(q.getAgentId());
        dto.setSiteId(q.getSiteId());
        for (QuotationLine line : q.getLines()) {
            if (line.isServiceLine()) {
                continue;
            }
            QuotationDto.Line row = new QuotationDto.Line();
            row.setId(copy ? null : line.getId());
            row.setProductId(line.getProduct().getId());
            row.setKind(line.getKind());
            row.setWidthMm(line.getWidthMm());
            row.setHeightMm(line.getHeightMm());
            row.setQuantity(line.getQuantity());
            row.setHoles(line.getHoles());
            row.setMark(line.getMark());
            row.setDiscountPercent(line.getDiscountPercent().signum() == 0 ? null : line.getDiscountPercent().stripTrailingZeros());
            q.getLines().stream().filter(l -> l.getParentLine() == line).forEach(l -> row.getServiceIds().add(l.getService().getId()));
            dto.getLines().add(row);
        }
        if (dto.getLines().isEmpty()) {
            dto.getLines().add(new QuotationDto.Line());
        }
        return dto;
    }

    public LocalDate defaultValidUntil() {
        return defaultValidUntil(QuotationKind.QUOTATION);
    }

    public LocalDate defaultValidUntil(QuotationKind kind) {
        return today().plusDays(settingService.getInt(kind == QuotationKind.SALES_ORDER
                ? SettingKey.SALES_ORDER_VALID_DAYS : SettingKey.QUOTATION_VALIDITY_DAYS));
    }

    /** Active territories (an order's and a customer's). */
    public List<Territory> territories() {
        return territoryRepo.findByEnabledTrueOrderByCodeAsc();
    }

    /** Every territory by id, for names on pages. */
    public Map<UUID, Territory> territoriesById() {
        return territoryRepo.findAll().stream().collect(Collectors.toMap(Territory::getId, Function.identity()));
    }

    /** Customers who are established agents (BP 6). */
    public List<Customer> agents() {
        return customerRepo.findByEnabledTrueOrderByNameAsc().stream().filter(Customer::isAgent).toList();
    }

    /** Active sites the goods of an order can leave from. */
    public List<Location> sites() {
        return stockService.locationsById().values().stream().filter(l -> l.getType() == LocationType.SITE && l.isEnabled())
                .sorted(Comparator.comparing(Location::getCode)).toList();
    }

    // ---------------------------------------------------------------- writing

    /** A new draft, numbered and priced. */
    @Transactional
    public Quotation create(QuotationDto dto) {
        return create(QuotationKind.QUOTATION, dto, null);
    }

    /** A new quotation or order (taken by the signed-in user), numbered and priced; an order may name its quotation. */
    @Transactional
    public Quotation create(QuotationKind kind, QuotationDto dto, UUID sourceId) {
        Quotation q = new Quotation();
        q.setKind(kind);
        q.setQuoteDate(today());
        q.setSourceId(sourceId);
        if (kind == QuotationKind.SALES_ORDER) {
            Optional<AppUserPrincipal> user = AppUserPrincipal.current();
            q.setSalesperson(user.map(AppUserPrincipal::getUsername).orElse("system"));
            q.setSalespersonId(user.map(AppUserPrincipal::getId).orElse(null));
        }
        apply(q, dto);
        q.setNumber(numbers.next(kind == QuotationKind.SALES_ORDER ? DocumentType.SALES_ORDER : DocumentType.QUOTATION));
        return repo.save(q);
    }

    /**
     * The Order Desk turns a sent, valid quotation into an order (F-01): a draft order with its customer, rows and
     * processing, at the counter; the quotation converts when the order is paid.
     */
    @Transactional
    public Quotation orderFrom(UUID quotationId) {
        Quotation source = findDetailed(quotationId);
        if (source.isOrder() || !source.isSent()) {
            throw BusinessException.of("order.from.notSent", source.getNumber());
        }
        if (source.isExpiredOn(today())) {
            throw BusinessException.of("sale.quote.expired", source.getNumber(), source.getValidUntil().format(DAY));
        }
        if (repo.existsBySourceIdAndStatusIn(source.getId(), List.of(QuotationStatus.DRAFT, QuotationStatus.SENT))) {
            throw BusinessException.of("order.from.exists", source.getNumber());
        }
        QuotationDto dto = formOf(source, true);
        QuotationDto defaults = newForm(QuotationKind.SALES_ORDER);
        dto.setValidUntil(defaults.getValidUntil());
        dto.setChannel(defaults.getChannel());
        dto.setMarket(source.getCustomer().getMarket());
        dto.setTerritoryId(source.getCustomer().getTerritoryId());
        dto.setSiteId(defaults.getSiteId());
        return create(QuotationKind.SALES_ORDER, dto, source.getId());
    }

    // ---------------------------------------------------------------- the Order Desk's check (F-01 C, Annex G step 2)

    /** One row against stock: free whole sheets of its size, or m² of its glass available to cut. */
    public record LineStock(QuotationLine line, long sheetsFree, BigDecimal areaAvailable, BigDecimal areaNeeded, boolean enough) {
    }

    /** Stock for each row, the price list and its date, and the customer's credit: limit, owed and room left. */
    public record DeskCheck(List<LineStock> lines, PriceList priceList, LocalDateTime pricesOf, SalesService.Credit credit,
                            boolean overCredit) {

        public boolean isStockShort() {
            return lines.stream().anyMatch(l -> !l.enough());
        }
    }

    public DeskCheck deskCheck(Quotation q) {
        List<LineStock> rows = new ArrayList<>();
        Map<String, Long> sheetsTaken = new HashMap<>();
        Map<UUID, BigDecimal> areaFree = new HashMap<>();
        Map<UUID, Location> places = q.getSiteId() == null ? Map.of() : stockService.locationsById();
        for (QuotationLine line : q.getLines()) {
            if (line.isSheet()) {
                // The sheets of the site the order leaves from, when it names one
                List<StockUnit> found = unitRepo.findOfSize(line.getProduct().getId(), line.getWidthMm(), line.getHeightMm(), StockStatus.AVAILABLE)
                        .stream().filter(u -> q.getSiteId() == null || (u.getLocation() != null
                                && q.getSiteId().equals(SiteTransferService.siteOf(u.getLocation(), places)))).toList();
                Map<UUID, String> held = found.isEmpty() ? Map.of() : stockService.holds(found.stream().map(StockUnit::getId).toList());
                String key = line.getProduct().getId() + ":" + Math.min(line.getWidthMm(), line.getHeightMm()) + "x"
                        + Math.max(line.getWidthMm(), line.getHeightMm());
                long free = found.stream().filter(u -> !held.containsKey(u.getId())).count() - sheetsTaken.getOrDefault(key, 0L);
                sheetsTaken.merge(key, (long) line.getQuantity(), Long::sum);
                rows.add(new LineStock(line, Math.max(free, 0), null, null, free >= line.getQuantity()));
            } else if (line.isCustomPiece()) {
                BigDecimal available = areaFree.computeIfAbsent(line.getProduct().getId(),
                        id -> unitRepo.sumArea(id, List.of(StockStatus.AVAILABLE)));
                BigDecimal needed = Pricing.areaM2(line.getWidthMm(), line.getHeightMm()).multiply(BigDecimal.valueOf(line.getQuantity()));
                rows.add(new LineStock(line, 0, available, needed, available.compareTo(needed) >= 0));
                areaFree.put(line.getProduct().getId(), available.subtract(needed));
            }
        }
        PriceList list = q.getLines().stream().map(QuotationLine::getPriceList).filter(Objects::nonNull).findFirst().orElse(null);
        LocalDateTime pricesOf = list == null ? null : list.getUpdatedAt() != null ? list.getUpdatedAt() : list.getCreatedAt();
        SalesService.Credit credit = salesService.credit(q.getCustomer());
        boolean over = credit.allowed() && q.getTotalAmount().compareTo(credit.getAvailable()) > 0;
        return new DeskCheck(rows, list, pricesOf, credit, over);
    }

    /** A draft changed and priced again. */
    @Transactional
    public Quotation update(UUID id, QuotationDto dto) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("Quotation", id));
        Quotation q = findDetailed(id);
        if (!q.isDraft()) {
            throw BusinessException.of("quote.notDraft", q.getNumber());
        }
        apply(q, dto);
        return q;
    }

    /** Fixes a draft for the customer: it has lines and its date has not passed. */
    @Transactional
    public Quotation send(UUID id) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("Quotation", id));
        Quotation q = findDetailed(id);
        if (!q.isDraft()) {
            throw BusinessException.of("quote.notDraft", q.getNumber());
        }
        if (q.getLines().isEmpty()) {
            throw BusinessException.of("quote.lines.required");
        }
        if (q.getValidUntil().isBefore(today())) {
            throw BusinessException.of("quote.send.expired", q.getNumber());
        }
        q.setSentAt(LocalDateTime.now(clock));
        q.setSentBy(AppUserPrincipal.currentUsername());
        q.setStatus(QuotationStatus.SENT);
        if (q.isOrder()) {
            // The cashier takes payment against it (Annex G step 3)
            notifier.holders("SELL", AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null), NotificationKind.ORDER,
                    "notify.order.toPay", "notify.order.toPayText", "/sales-orders/" + q.getId(), q.getNumber(), q.getBillTo());
        }
        return q;
    }

    /** Cancels a draft or sent quotation with a reason, unless a till is ringing it up. */
    @Transactional
    public Quotation cancel(UUID id, String reason) {
        repo.lockById(id).orElseThrow(() -> new NotFoundException("Quotation", id));
        Quotation q = findDetailed(id);
        if (!q.isDraft() && !q.isSent()) {
            throw BusinessException.of("quote.notOpen", q.getNumber());
        }
        Optional<String> till = onTill(q);
        if (till.isPresent()) {
            throw BusinessException.of("quote.cancel.onTill", q.getNumber(), till.get());
        }
        q.setCancelReason(reason.trim());
        q.setCancelledAt(LocalDateTime.now(clock));
        q.setCancelledBy(AppUserPrincipal.currentUsername());
        q.setStatus(QuotationStatus.CANCELLED);
        return q;
    }

    /**
     * Sets the header and rows and prices every line for the customer: a row's glass over its chargeable area, its
     * processing (sizes only) per unit, both at the row's discount, which may not pass the author's limit.
     */
    private void apply(Quotation q, QuotationDto dto) {
        Customer customer = dto.getCustomerId() == null ? null
                : customerRepo.findById(dto.getCustomerId()).filter(Customer::isEnabled).orElse(null);
        if (customer == null) {
            throw BusinessException.onField("customerId", "sale.customer.invalid");
        }
        String tin = PartyRules.clean(dto.getBuyerTin());
        if (tin != null && !tin.matches("\\d{9}")) {
            throw BusinessException.onField("buyerTin", "sale.buyerTin.invalid");
        }
        if (dto.getValidUntil().isBefore(q.getQuoteDate())) {
            throw BusinessException.onField("validUntil", "quote.validUntil.beforeDate", q.getQuoteDate().format(DAY));
        }
        if (dto.getLines().isEmpty()) {
            throw BusinessException.of("quote.lines.required");
        }
        if (q.isOrder()) {
            applyOrder(q, dto);
        }
        q.setCustomer(customer);
        q.setBuyerName(PartyRules.clean(dto.getBuyerName()));
        q.setBuyerTin(tin != null ? tin : customer.getType() != CustomerType.WALK_IN ? customer.getTin() : null);
        q.setValidUntil(dto.getValidUntil());
        q.setNotes(PartyRules.clean(dto.getNotes()));

        BigDecimal limit = salesService.discountLimit();
        Map<UUID, Product> products = productRepo.findAllById(dto.getLines().stream().map(QuotationDto.Line::getProductId)
                .filter(Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, ProcessingService> services = serviceRepo.findAllById(dto.getLines().stream()
                .flatMap(r -> r.getServiceIds() == null ? java.util.stream.Stream.empty() : r.getServiceIds().stream())
                .filter(Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(ProcessingService::getId, Function.identity()));
        Map<UUID, QuotationLine> existing = q.getLines().stream().filter(l -> !l.isServiceLine() && l.getId() != null)
                .collect(Collectors.toMap(QuotationLine::getId, Function.identity()));

        List<QuotationLine> kept = new ArrayList<>();
        for (int i = 0; i < dto.getLines().size(); i++) {
            QuotationDto.Line row = dto.getLines().get(i);
            String f = "lines[" + i + "].";
            Product product = products.get(row.getProductId());
            if (product == null || !product.isEnabled()) {
                throw BusinessException.onField(f + "productId", "sale.custom.product");
            }
            if (row.getKind() == QuoteLineKind.SERVICE) {
                throw BusinessException.onField(f + "kind", "quote.line.kind.required");
            }
            boolean size = row.getKind() == QuoteLineKind.CUSTOM_PIECE;
            if (size && !product.getGlassType().isCuttable()) {
                throw BusinessException.onField(f + "productId", "sale.custom.notCuttable", product.getCode());
            }
            List<UUID> ids = row.getServiceIds() == null ? List.of() : row.getServiceIds().stream().filter(Objects::nonNull).distinct().toList();
            List<ProcessingService> processing = ids.stream().map(services::get)
                    .filter(s -> s != null && s.isEnabled() && s.isProcessing())
                    .sorted(Comparator.comparing(ProcessingService::getCode)).toList();
            if (processing.size() != ids.size()) {
                throw BusinessException.onField(f + "serviceIds", "sale.custom.service");
            }
            if (!size && !processing.isEmpty()) {
                throw BusinessException.onField(f + "serviceIds", "quote.line.sheetProcessing");
            }
            boolean perHole = processing.stream().anyMatch(s -> s.getChargeUnit() == ChargeUnit.HOLE);
            if (perHole && row.getHoles() == null) {
                throw BusinessException.onField(f + "holes", "sale.custom.holes");
            }
            BigDecimal discount = row.getDiscountPercent() == null ? BigDecimal.ZERO.setScale(2)
                    : row.getDiscountPercent().setScale(2, RoundingMode.HALF_UP);
            if (Discounts.needsApproval(discount, limit)) {
                throw BusinessException.onField(f + "discountPercent", "quote.line.discount.overLimit", limit);
            }
            String mark = PartyRules.clean(row.getMark());

            QuotationLine line = row.getId() == null ? null : existing.get(row.getId());
            if (line == null) {
                line = new QuotationLine();
                line.setQuotation(q);
            }
            int w = row.getWidthMm();
            int h = row.getHeightMm();
            int qty = row.getQuantity();
            line.setKind(row.getKind());
            line.setProduct(product);
            line.setWidthMm(w);
            line.setHeightMm(h);
            line.setQuantity(qty);
            line.setHoles(perHole ? row.getHoles() : null);
            line.setMark(size ? mark : null);
            line.setProcessing(processing.isEmpty() ? null : processing.stream().map(ProcessingService::getCode).collect(Collectors.joining(",")));
            LinePricing.Glass glass = pricing.glass(customer, product, w, h)
                    .orElseThrow(() -> BusinessException.onField(f + "productId", "sale.noPrice", product.getCode()));
            line.setChargeableAreaM2(glass.chargeableArea());
            line.setServiceQuantity(null);
            price(line, glass.list(), glass.pricesIncludeVat(), glass.taxCode(), glass.vatRate(), glass.pricePerM2(), discount);
            line.setAmount(pricing.amount(line.getPrice(), glass.chargeableArea(), qty, glass.pricesIncludeVat(), glass.vatRate()));
            kept.add(line);

            // Its processing: the lines under it, kept by service
            QuotationLine parent = line;
            Map<UUID, QuotationLine> children = q.getLines().stream().filter(l -> l.isServiceLine() && l.getParentLine() == parent)
                    .collect(Collectors.toMap(l -> l.getService().getId(), Function.identity()));
            for (ProcessingService service : processing) {
                QuotationLine child = children.get(service.getId());
                if (child == null) {
                    child = new QuotationLine();
                    child.setQuotation(q);
                    child.setKind(QuoteLineKind.SERVICE);
                    child.setParentLine(parent);
                    child.setService(service);
                }
                child.setProduct(product);
                child.setWidthMm(w);
                child.setHeightMm(h);
                child.setQuantity(qty);
                child.setHoles(service.getChargeUnit() == ChargeUnit.HOLE ? row.getHoles() : null);
                LinePricing.Service sp = pricing.service(customer, service, w, h, qty, child.getHoles())
                        .orElseThrow(() -> BusinessException.onField(f + "serviceIds", "sale.custom.noServicePrice", service.getName()));
                child.setChargeableAreaM2(null);
                child.setServiceQuantity(sp.quantity());
                price(child, sp.list(), sp.pricesIncludeVat(), sp.taxCode(), sp.vatRate(), sp.unitPrice(), discount);
                child.setAmount(pricing.amount(child.getPrice(), sp.quantity(), 1, sp.pricesIncludeVat(), sp.vatRate()));
                kept.add(child);
            }
        }
        q.getLines().removeIf(l -> !kept.contains(l));
        // uk_quotation_lines_no is checked at commit, so lines can swap numbers here
        for (int i = 0; i < kept.size(); i++) {
            QuotationLine line = kept.get(i);
            line.setLineNo(i + 1);
            if (!q.getLines().contains(line)) {
                q.getLines().add(line);
            }
        }
        q.getLines().sort(Comparator.comparingInt(QuotationLine::getLineNo));
        Vat.Totals totals = Vat.totals(kept.stream().map(l -> new Vat.Line(l.getTaxCode(), l.getVatRate(), l.getAmount())).toList());
        q.setNetAmount(totals.net());
        q.setVatAmount(totals.vat());
        q.setTotalAmount(totals.gross());
    }

    /** An order's channel, market, territory, agent and site: each required or checked. */
    private void applyOrder(Quotation q, QuotationDto dto) {
        if (dto.getChannel() == null) {
            throw BusinessException.onField("channel", "order.channel.required");
        }
        if (dto.getMarket() == null) {
            throw BusinessException.onField("market", "order.market.required");
        }
        if (dto.getTerritoryId() != null && territoryRepo.findById(dto.getTerritoryId()).filter(Territory::isEnabled).isEmpty()) {
            throw BusinessException.onField("territoryId", "order.territory.invalid");
        }
        if (dto.getAgentId() != null && customerRepo.findById(dto.getAgentId()).filter(c -> c.isEnabled() && c.isAgent()).isEmpty()) {
            throw BusinessException.onField("agentId", "order.agent.invalid");
        }
        if (dto.getChannel() == OrderChannel.AGENT && dto.getAgentId() == null) {
            throw BusinessException.onField("agentId", "order.agent.required");
        }
        if (dto.getSiteId() != null && sites().stream().noneMatch(s -> s.getId().equals(dto.getSiteId()))) {
            throw BusinessException.onField("siteId", "order.site.invalid");
        }
        q.setChannel(dto.getChannel());
        q.setMarket(dto.getMarket());
        q.setTerritoryId(dto.getTerritoryId());
        q.setAgentId(dto.getAgentId());
        q.setSiteId(dto.getSiteId());
    }

    private static void price(QuotationLine line, PriceList list, boolean includeVat, String taxCode, BigDecimal vatRate,
                              BigDecimal listPrice, BigDecimal discount) {
        line.setPriceList(list);
        line.setPricesIncludeVat(includeVat);
        line.setTaxCode(taxCode);
        line.setVatRate(vatRate);
        line.setListPrice(listPrice);
        line.setDiscountPercent(discount);
        line.setPrice(discount.signum() == 0 ? listPrice : Discounts.priceAt(listPrice, discount));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
