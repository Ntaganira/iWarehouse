package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : GlobalSearchService.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The header's search: a scanned or typed code opens its record (a unit, a rack or slot, any document
 *               number, a customer, supplier or glass code); anything else lists the first matches of each kind, each
 *               found by its own list's search, so "See all" opens that list filtered the same way. A kind shows only to
 *               who may open its list (page and view permission, as its controller asks); trips without PERM_VIEW_TRIP
 *               are the user's own, as on the trips list. Reads only.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class GlobalSearchService {

    /** Matches shown per kind; the list behind "See all" has the rest. */
    static final int PER_GROUP = 5;
    /** Shorter text is not searched (one letter matches nearly everything). */
    static final int MIN_LENGTH = 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** One record found: its code or number, what it is, a detail line, and its page. */
    public record Hit(String code, String title, String detail, String url) {
    }

    /** The matches of one kind: its title key, the list behind "See all" (null: no list to search), how many there are. */
    public record Group(String titleKey, String listUrl, long total, List<Hit> hits) {
    }

    /** What the search found; jumpTo is the one record whose code is exactly the text searched, else null. */
    public record Result(String query, boolean tooShort, List<Group> groups, String jumpTo) {
        public long getTotalHits() {
            return groups.stream().mapToLong(Group::total).sum();
        }
    }

    private record Source<T>(String titleKey, String listPath, String extraQuery, List<String> authorities,
                             Function<String, Page<T>> find, Function<T, Hit> hit) {
    }

    private final StockService stockService;
    private final LocationService locationService;
    private final LocationRepository locationRepo;
    private final ProductService productService;
    private final CustomerService customerService;
    private final SupplierService supplierService;
    private final SalesService salesService;
    private final QuotationService quotationService;
    private final CreditNoteService creditNoteService;
    private final CustomerAccountService customerAccountService;
    private final PurchaseOrderService purchaseOrderService;
    private final GoodsReceiptService goodsReceiptService;
    private final ShipmentService shipmentService;
    private final SupplierAccountService supplierAccountService;
    private final CuttingJobService cuttingJobService;
    private final StockTransferService stockTransferService;
    private final StockAdjustmentService stockAdjustmentService;
    private final StockCountService stockCountService;
    private final TripService tripService;
    private final VehicleService vehicleService;
    private final DriverService driverService;
    private final AuditCaseService auditCaseService;
    private final UserService userService;
    private final EmployeeService employeeService;
    private final PayRunService payRunService;
    private final VehicleCostService vehicleCostService;
    private final Messages messages;
    private final ReceivableWriteOffService writeOffService;
    private final CreditApplicationService creditApplicationService;
    private final PriceChangeService priceChangeService;
    private final NumberFormats num;

    public GlobalSearchService(StockService stockService, LocationService locationService, LocationRepository locationRepo,
                               ProductService productService, CustomerService customerService, SupplierService supplierService,
                               SalesService salesService, QuotationService quotationService, CreditNoteService creditNoteService,
                               CustomerAccountService customerAccountService, PurchaseOrderService purchaseOrderService,
                               GoodsReceiptService goodsReceiptService, ShipmentService shipmentService,
                               SupplierAccountService supplierAccountService, CuttingJobService cuttingJobService,
                               StockTransferService stockTransferService, StockAdjustmentService stockAdjustmentService,
                               StockCountService stockCountService, TripService tripService, VehicleService vehicleService,
                               DriverService driverService, AuditCaseService auditCaseService, UserService userService,
                               EmployeeService employeeService, PayRunService payRunService, VehicleCostService vehicleCostService,
                               Messages messages, NumberFormats num, ReceivableWriteOffService writeOffService,
                               CreditApplicationService creditApplicationService, PriceChangeService priceChangeService) {
        this.writeOffService = writeOffService;
        this.creditApplicationService = creditApplicationService;
        this.priceChangeService = priceChangeService;
        this.stockService = stockService;
        this.locationService = locationService;
        this.locationRepo = locationRepo;
        this.productService = productService;
        this.customerService = customerService;
        this.supplierService = supplierService;
        this.salesService = salesService;
        this.quotationService = quotationService;
        this.creditNoteService = creditNoteService;
        this.customerAccountService = customerAccountService;
        this.purchaseOrderService = purchaseOrderService;
        this.goodsReceiptService = goodsReceiptService;
        this.shipmentService = shipmentService;
        this.supplierAccountService = supplierAccountService;
        this.cuttingJobService = cuttingJobService;
        this.stockTransferService = stockTransferService;
        this.stockAdjustmentService = stockAdjustmentService;
        this.stockCountService = stockCountService;
        this.tripService = tripService;
        this.vehicleService = vehicleService;
        this.driverService = driverService;
        this.auditCaseService = auditCaseService;
        this.userService = userService;
        this.employeeService = employeeService;
        this.payRunService = payRunService;
        this.vehicleCostService = vehicleCostService;
        this.messages = messages;
        this.num = num;
    }

    public Result search(String raw) {
        String query = normalize(raw);
        if (query.length() < MIN_LENGTH) {
            return new Result(query, !query.isEmpty(), List.of(), null);
        }
        // A label is looked up exactly first (upper case, the unique index): the scanner's case
        if (isCode(query)) {
            String code = query.toUpperCase(Locale.ROOT);
            Optional<StockUnit> unit = can(STOCK) ? stockService.findByCode(code) : Optional.empty();
            if (unit.isPresent()) {
                return new Result(query, false, List.of(), "/stock/" + unit.get().getId());
            }
            Optional<Location> place = can(LOCATIONS) ? locationService.findByCode(code) : Optional.empty();
            if (place.isPresent()) {
                return new Result(query, false, List.of(), "/locations/" + place.get().getId());
            }
        }
        List<Group> groups = new ArrayList<>();
        for (Source<?> source : sources()) {
            if (can(source.authorities())) {
                Group group = run(source, query);
                if (group.total() > 0) {
                    groups.add(group);
                }
            }
        }
        return new Result(query, false, groups, jumpTo(query, groups));
    }

    // ---------------------------------------------------------------- pure parts (tested)

    /** The text as searched: trimmed, inner spaces collapsed, at most 100 characters. */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String q = raw.trim().replaceAll("\\s+", " ");
        return q.length() > 100 ? q.substring(0, 100) : q;
    }

    /** Looks like a label or a number: one word of letters, digits, dashes, dots or slashes. */
    static boolean isCode(String query) {
        return query.matches("[A-Za-z0-9][A-Za-z0-9./_-]*");
    }

    /** The page of the one record whose code is exactly the text (any case); null when none or several. */
    static String jumpTo(String query, List<Group> groups) {
        List<String> exact = groups.stream().flatMap(g -> g.hits().stream())
                .filter(h -> h.code() != null && h.code().equalsIgnoreCase(query))
                .map(Hit::url).distinct().toList();
        return exact.size() == 1 ? exact.get(0) : null;
    }

    // ---------------------------------------------------------------- the kinds searched, in the order shown

    private static final List<String> STOCK = List.of("PAGE_STOCK", "PERM_VIEW_STOCK");
    private static final List<String> LOCATIONS = List.of("PAGE_LOCATIONS", "PERM_VIEW_LOCATION");

    private List<Source<?>> sources() {
        Long me = AppUserPrincipal.currentHas("PERM_VIEW_TRIP") ? null : AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(-1L);
        return List.of(
            new Source<>("nav.stock", "/stock", "status=" + StockService.ALL_STATUSES, STOCK,
                q -> stockService.findPage(new StockService.UnitFilter(q, null, null, StockService.ALL_STATUSES, null, null, null), 0, PER_GROUP),
                u -> new Hit(u.getCode(), productName(u.getProduct()),
                        join(u.getWidthMm() + " × " + u.getHeightMm() + " mm", u.getLocation() == null ? null : u.getLocation().getCode(),
                                messages.get("stock.status." + u.getStatus())), "/stock/" + u.getId())),
            new Source<>("nav.locations", null, null, LOCATIONS,
                q -> locationRepo.search(q, PageRequest.of(0, PER_GROUP)),
                l -> new Hit(l.getCode(), l.getName(), messages.get("location.type." + l.getType()), "/locations/" + l.getId())),
            new Source<>("nav.products", "/products", null, List.of("PAGE_PRODUCTS", "PERM_VIEW_PRODUCT"),
                q -> productService.findPage(q, null, null, null, 0, PER_GROUP),
                p -> new Hit(p.getCode(), productName(p), null, "/products/" + p.getId())),
            new Source<>("nav.customers", "/customers", null, List.of("PAGE_CUSTOMERS", "PERM_VIEW_CUSTOMER"),
                q -> customerService.findPage(q, null, null, 0, PER_GROUP),
                c -> new Hit(c.getCode(), c.getName(), join(c.getPhone(), c.getTin() == null ? null : "TIN " + c.getTin()), "/customers/" + c.getId())),
            new Source<>("nav.creditApplications", "/credit-applications", null, List.of("PAGE_CREDIT_APPLICATIONS"),
                q -> creditApplicationService.findPage(null, q, 0, PER_GROUP),
                a -> new Hit(a.getNumber(), a.getCustomer() == null ? null : a.getCustomer().getName(),
                        join(date(a.getRequestedAt()), rwf(a.getCreditLimit()), messages.get("creditApp.status." + a.getStatus())),
                        "/credit-applications/" + a.getId())),
            new Source<>("nav.suppliers", "/suppliers", null, List.of("PAGE_SUPPLIERS", "PERM_VIEW_SUPPLIER"),
                q -> supplierService.findPage(q, null, null, null, 0, PER_GROUP),
                s -> new Hit(s.getCode(), s.getName(), join(s.getPhone(), s.getTin() == null ? null : "TIN " + s.getTin()), "/suppliers/" + s.getId())),
            new Source<>("priceChange.title", "/price-lists/changes", null, List.of("PAGE_PRICE_LISTS", "PERM_VIEW_PRICE_LIST"),
                q -> priceChangeService.findPage(null, q, 0, PER_GROUP),
                c -> new Hit(c.getNumber(), c.getPriceList() == null ? null : c.getPriceList().getName(),
                        join(date(c.getRequestedAt()), messages.get("priceChange.status." + c.getStatus())), "/price-lists/changes/" + c.getId())),
            new Source<>("nav.invoices", "/invoices", null, List.of("PAGE_INVOICES", "PERM_VIEW_INVOICE"),
                q -> salesService.findPage(q, false, 0, PER_GROUP),
                i -> new Hit(i.getNumber(), buyer(i.getBuyerName(), i.getCustomer()),
                        join(date(i.getInvoiceDate()), rwf(i.getTotalAmount())), "/invoices/" + i.getId())),
            new Source<>("nav.quotations", "/quotations", null, List.of("PAGE_QUOTATIONS", "PERM_VIEW_QUOTATION"),
                q -> quotationService.findPage(null, q, 0, PER_GROUP),
                x -> new Hit(x.getNumber(), buyer(x.getBuyerName(), x.getCustomer()), rwf(x.getTotalAmount()), "/quotations/" + x.getId())),
            new Source<>("nav.creditNotes", "/credit-notes", null, List.of("PAGE_CREDIT_NOTES", "PERM_VIEW_CREDIT_NOTE"),
                q -> creditNoteService.findPage(q, 0, PER_GROUP),
                n -> new Hit(n.getNumber(), n.getCustomer() == null ? null : n.getCustomer().getName(),
                        join(date(n.getPostedAt()), rwf(n.getTotalAmount())), "/credit-notes/" + n.getId())),
            new Source<>("creditNote.request.title", "/credit-notes/requests", null, List.of("PAGE_CREDIT_NOTES", "PERM_VIEW_CREDIT_NOTE"),
                q -> creditNoteService.findRequests(null, q, 0, PER_GROUP),
                r -> new Hit(r.getNumber(), r.getCustomer() == null ? null : r.getCustomer().getName(),
                        join(date(r.getRequestedAt()), rwf(r.getTotalAmount()), messages.get("creditNote.request.status." + r.getStatus())),
                        "/credit-notes/requests/" + r.getId())),
            new Source<>("nav.customerPayments", "/customer-payments", null, List.of("PAGE_CUSTOMER_PAYMENTS", "PERM_VIEW_CUSTOMER_ACCOUNT"),
                q -> customerAccountService.findPage(q, 0, PER_GROUP),
                p -> new Hit(p.getNumber(), p.getCustomer() == null ? null : p.getCustomer().getName(),
                        join(date(p.getPostedAt()), rwf(p.getAmount())), "/customer-payments/" + p.getId())),
            new Source<>("nav.writeOffs", "/accounting/write-offs", null, List.of("PAGE_RECEIVABLE_WRITE_OFFS", "PERM_VIEW_WRITE_OFF"),
                q -> writeOffService.findPage(null, q, 0, PER_GROUP),
                w -> new Hit(w.getNumber(), w.getCustomer() == null ? null : w.getCustomer().getName(),
                        join(date(w.getRequestedAt()), rwf(w.getAmount()), messages.get("writeOff.status." + w.getStatus())),
                        "/accounting/write-offs/" + w.getId())),
            new Source<>("nav.procurement", "/purchase-orders", null, List.of("PAGE_PROCUREMENT", "PERM_VIEW_PURCHASE_ORDER"),
                q -> purchaseOrderService.findPage(q, null, null, 0, PER_GROUP),
                o -> new Hit(o.getNumber(), o.getSupplier() == null ? null : o.getSupplier().getName(),
                        join(date(o.getOrderDate()), o.getSupplierRef()), "/purchase-orders/" + o.getId())),
            new Source<>("nav.goodsReceipts", "/goods-receipts", null, List.of("PAGE_RECEIVING", "PERM_VIEW_GOODS_RECEIPT"),
                q -> goodsReceiptService.findPage(q, null, 0, PER_GROUP),
                r -> new Hit(r.getNumber(),
                        r.getPurchaseOrder() == null || r.getPurchaseOrder().getSupplier() == null ? null : r.getPurchaseOrder().getSupplier().getName(),
                        join(date(r.getReceivedDate()), r.getPurchaseOrder() == null ? null : r.getPurchaseOrder().getNumber()), "/goods-receipts/" + r.getId())),
            new Source<>("nav.shipments", "/shipments", null, List.of("PAGE_SHIPMENTS", "PERM_VIEW_SHIPMENT"),
                q -> shipmentService.findPage(q, null, 0, PER_GROUP),
                s -> new Hit(s.getNumber(), s.getReference(), null, "/shipments/" + s.getId())),
            new Source<>("nav.supplierInvoices", "/supplier-invoices", null, List.of("PAGE_SUPPLIER_INVOICES", "PERM_VIEW_SUPPLIER_ACCOUNT"),
                q -> supplierAccountService.findInvoices(q, 0, PER_GROUP),
                i -> new Hit(i.getNumber(), i.getSupplier() == null ? null : i.getSupplier().getName(),
                        join(i.getSupplierRef(), date(i.getInvoiceDate()), money(i.getAmount(), i.getCurrencyCode())), "/supplier-invoices/" + i.getId())),
            new Source<>("nav.supplierPayments", "/supplier-payments", null, List.of("PAGE_SUPPLIER_PAYMENTS", "PERM_VIEW_SUPPLIER_ACCOUNT"),
                q -> supplierAccountService.findPayments(q, 0, PER_GROUP),
                p -> new Hit(p.getNumber(), p.getSupplier() == null ? null : p.getSupplier().getName(),
                        join(date(p.getPostedAt()), money(p.getAmount(), p.getCurrencyCode())), "/supplier-payments/" + p.getId())),
            new Source<>("supplierPayment.request.title", "/supplier-payments/requests", null, List.of("PAGE_SUPPLIER_PAYMENTS", "PERM_VIEW_SUPPLIER_ACCOUNT"),
                q -> supplierAccountService.findRequests(null, q, 0, PER_GROUP),
                r -> new Hit(r.getNumber(), r.getSupplier() == null ? null : r.getSupplier().getName(),
                        join(date(r.getPreparedAt()), money(r.getAmount(), r.getCurrencyCode()), messages.get("supplierPayment.request.status." + r.getStatus())),
                        "/supplier-payments/requests/" + r.getId())),
            new Source<>("nav.production", "/cutting-jobs", null, List.of("PAGE_PRODUCTION", "PERM_VIEW_CUTTING_JOB"),
                q -> cuttingJobService.findPage(q, null, null, 0, PER_GROUP),
                j -> new Hit(j.getNumber(), j.getProduct() == null ? null : productName(j.getProduct()),
                        j.getCustomer() == null ? null : j.getCustomer().getName(), "/cutting-jobs/" + j.getId())),
            new Source<>("nav.stockTransfers", "/stock-transfers", null, List.of("PAGE_STOCK_TRANSFERS", "PERM_VIEW_STOCK_TRANSFER"),
                q -> stockTransferService.findPage(q, 0, PER_GROUP),
                t -> new Hit(t.getNumber(), t.getToLocation() == null ? null : "→ " + t.getToLocation().getCode(), date(t.getPostedAt()),
                        "/stock-transfers/" + t.getId())),
            new Source<>("nav.stockAdjustments", "/stock-adjustments", null, List.of("PAGE_STOCK_ADJUSTMENTS", "PERM_VIEW_STOCK_ADJUSTMENT"),
                q -> stockAdjustmentService.findPage(q, null, 0, PER_GROUP),
                a -> new Hit(a.getNumber(), a.getReason(), date(a.getPostedAt()), "/stock-adjustments/" + a.getId())),
            new Source<>("nav.stockCounts", "/stock-counts", null, List.of("PAGE_STOCK_COUNTS", "PERM_VIEW_STOCK_COUNT"),
                q -> stockCountService.findPage(q, null, 0, PER_GROUP),
                c -> new Hit(c.getNumber(), c.getLocation() == null ? null : c.getLocation().getCode(),
                        c.getProduct() == null ? null : productName(c.getProduct()), "/stock-counts/" + c.getId())),
            new Source<>("nav.fleet", "/trips", null, List.of("PAGE_FLEET"),
                q -> tripService.findPage(new TripService.Filter(q, null, null, me), 0, PER_GROUP),
                t -> new Hit(t.getNumber(),
                        join(t.getVehicle() == null ? null : t.getVehicle().getPlate(),
                                t.getDriver() == null || t.getDriver().getUser() == null ? null : t.getDriver().getUser().getFullName()),
                        join(date(t.getTripDate()), t.getArea()), "/trips/" + t.getId())),
            new Source<>("nav.vehicles", "/vehicles", null, List.of("PAGE_VEHICLES", "PERM_VIEW_VEHICLE"),
                q -> vehicleService.findPage(q, null, 0, PER_GROUP),
                v -> new Hit(v.getPlate(), v.getModel(), null, "/vehicles/" + v.getId())),
            new Source<>("nav.vehicleCosts", "/vehicle-costs", null, List.of("PAGE_VEHICLE_COSTS", "PERM_VIEW_VEHICLE_COST"),
                q -> vehicleCostService.findPage(q, null, null, null, 0, PER_GROUP),
                c -> new Hit(c.getNumber(), join(c.getVehicle() == null ? null : c.getVehicle().getPlate(), c.getDescription()),
                        join(date(c.getCostDate()), rwf(c.getAmount())), "/vehicle-costs/" + c.getId())),
            new Source<>("nav.drivers", "/drivers", null, List.of("PAGE_DRIVERS", "PERM_VIEW_DRIVER"),
                q -> driverService.findPage(q, null, 0, PER_GROUP),
                d -> new Hit(d.getUsername(), d.getUser() == null ? null : d.getUser().getFullName(), null, "/drivers/" + d.getId())),
            new Source<>("nav.auditCases", "/audit-cases", null, List.of("PAGE_AUDIT_CASES", "PERM_VIEW_AUDIT_CASES"),
                q -> auditCaseService.findPage(q, null, 0, PER_GROUP),
                c -> new Hit(c.getNumber(), c.getTrip() == null ? null : c.getTrip().getNumber(), date(c.getOpenedAt()), "/audit-cases/" + c.getId())),
            new Source<>("nav.employees", "/payroll/employees", null, List.of("PAGE_EMPLOYEES", "PERM_VIEW_EMPLOYEE"),
                q -> employeeService.findPage(q, null, 0, PER_GROUP),
                e -> new Hit(e.getCode(), e.getFullName(), e.getJobTitle(), "/payroll/employees/" + e.getId())),
            new Source<>("nav.payRuns", "/payroll/runs", null, List.of("PAGE_PAY_RUNS", "PERM_VIEW_PAY_RUN"),
                q -> payRunService.findPage(q, null, 0, PER_GROUP),
                r -> new Hit(r.getNumber(), date(r.getPeriod()), null, "/payroll/runs/" + r.getId())),
            new Source<>("nav.users", "/users", null, List.of("PAGE_USERS", "PERM_VIEW_USER"),
                q -> userService.findPage(q, null, null, 0, PER_GROUP),
                u -> new Hit(u.getUsername(), u.getFullName(), u.getEmail(), "/users/" + u.getId()))
        );
    }

    private <T> Group run(Source<T> source, String query) {
        Page<T> page = source.find().apply(query);
        List<Hit> hits = page.getContent().stream().map(source.hit()).toList();
        String list = null;
        if (source.listPath() != null) {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath(source.listPath()).queryParam("search", query);
            if (source.extraQuery() != null) {
                uri.query(source.extraQuery());
            }
            list = uri.encode().build().toUriString();
        }
        return new Group(source.titleKey(), list, page.getTotalElements(), hits);
    }

    private static boolean can(List<String> authorities) {
        return authorities.stream().allMatch(AppUserPrincipal::currentHas);
    }

    // ---------------------------------------------------------------- the detail line

    private String productName(Product p) {
        if (p == null) {
            return null;
        }
        return messages.get("glass.type." + p.getGlassType()) + (p.getVariant() != null ? " " + p.getVariant() : "") + " " + p.getThicknessLabel() + " mm";
    }

    private static String buyer(String buyerName, Customer customer) {
        if (buyerName != null && !buyerName.isBlank()) {
            return buyerName;
        }
        return customer == null ? null : customer.getName();
    }

    private String rwf(BigDecimal amount) {
        return amount == null ? null : num.money(amount) + " RWF";
    }

    private String money(BigDecimal amount, String currency) {
        return amount == null ? null : num.amount(amount, 2) + " " + Objects.requireNonNullElse(currency, "");
    }

    private static String date(LocalDate date) {
        return date == null ? null : date.format(DATE);
    }

    private static String date(LocalDateTime time) {
        return time == null ? null : time.format(DATE);
    }

    private static String join(String... parts) {
        String text = Stream.of(parts).filter(s -> s != null && !s.isBlank()).map(String::trim).collect(Collectors.joining(" · "));
        return text.isEmpty() ? null : text;
    }
}
