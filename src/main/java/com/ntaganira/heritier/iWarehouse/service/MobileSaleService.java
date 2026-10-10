package com.ntaganira.heritier.iWarehouse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : MobileSaleService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Takes the sales a driver's phone made, online or offline (SYNC-02..06, MPOS-02..05, ACC-06). A sale carries
 *               the phone's UUID: sent again, it is found and answered as before, never taken twice (SYNC-03, AT-05). It is
 *               checked against the trip as it stands: the driver's trip on the road, each unit still on its vehicle, an
 *               invoice number given to this phone for this trip and unused, the trip's frozen prices within the driver's
 *               discount limit, the phone's amounts and payments. A sale that fails is kept as a conflict for the
 *               supervisor, never dropped (SYNC-05). One that passes is issued as the phone printed it (its number, its
 *               day): the units leave the vehicle (SALE movements), cash goes to the driver's float and mobile money to its
 *               account (MobileSale journal), and the receipt is queued for EBM (SYNC-06). Locks the trip, then the number,
 *               then the products.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class MobileSaleService {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

    private final SalesInvoiceRepository invoiceRepo;
    private final SalesPaymentRepository paymentRepo;
    private final SyncConflictRepository conflictRepo;
    private final TripInvoiceNumberRepository numberRepo;
    private final TripRepository tripRepo;
    private final DriverRepository driverRepo;
    private final ApiDeviceRepository deviceRepo;
    private final StockUnitRepository unitRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;
    private final PriceListRepository priceListRepo;
    private final MobileTripService tripService;
    private final StockService stockService;
    private final PostingService postingService;
    private final EbmService ebmService;
    private final LinePricing pricing;
    private final Notifier notifier;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MobileSaleService(SalesInvoiceRepository invoiceRepo, SalesPaymentRepository paymentRepo, SyncConflictRepository conflictRepo,
                             TripInvoiceNumberRepository numberRepo, TripRepository tripRepo, DriverRepository driverRepo,
                             ApiDeviceRepository deviceRepo, StockUnitRepository unitRepo, ProductRepository productRepo,
                             CustomerRepository customerRepo, PriceListRepository priceListRepo, MobileTripService tripService,
                             StockService stockService, PostingService postingService, EbmService ebmService, LinePricing pricing,
                             Notifier notifier, ObjectMapper mapper, Clock clock) {
        this.invoiceRepo = invoiceRepo;
        this.paymentRepo = paymentRepo;
        this.conflictRepo = conflictRepo;
        this.numberRepo = numberRepo;
        this.tripRepo = tripRepo;
        this.driverRepo = driverRepo;
        this.deviceRepo = deviceRepo;
        this.unitRepo = unitRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
        this.priceListRepo = priceListRepo;
        this.tripService = tripService;
        this.stockService = stockService;
        this.postingService = postingService;
        this.ebmService = ebmService;
        this.pricing = pricing;
        this.notifier = notifier;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** A sale as the phone sends it. */
    public record SaleRequest(UUID clientId, String number, UUID tripId, UUID customerId, String buyerName, String buyerTin,
                              OffsetDateTime createdAt, List<LineRequest> lines, List<PaymentRequest> payments, BigDecimal total,
                              BigDecimal cashTendered) {
    }

    /** A unit sold, at the price per m² the phone charged, its reason when it is not the list price, and its amount. */
    public record LineRequest(UUID unitId, BigDecimal pricePerM2, String priceReason, BigDecimal amount) {
    }

    public record PaymentRequest(PaymentMethod method, BigDecimal amount, String reference) {
    }

    public enum Outcome {
        /** Taken now. */
        ACCEPTED,
        /** Taken before: the same sale sent again (SYNC-03). */
        ALREADY_ACCEPTED,
        /** Kept for the supervisor now (SYNC-05). */
        CONFLICT,
        /** Kept for the supervisor before. */
        ALREADY_CONFLICT
    }

    public record Result(Outcome outcome, SalesInvoice invoice, SyncConflict conflict) {

        public boolean isAccepted() {
            return outcome == Outcome.ACCEPTED || outcome == Outcome.ALREADY_ACCEPTED;
        }
    }

    /** Why a sale cannot be taken as it was made. */
    static final class Refused extends RuntimeException {
        final SyncConflictReason reason;
        final String detail;

        Refused(SyncConflictReason reason, String detail) {
            super(reason + ": " + detail, null, false, false);
            this.reason = reason;
            this.detail = detail;
        }
    }

    /** What a sale's lines come to on the server. */
    private record Priced(StockUnit unit, MobileSales.Resolved resolved, BigDecimal price, String reason, BigDecimal amount) {
    }

    // ---------------------------------------------------------------- taking a sale

    @Transactional
    public Result submit(AppUserPrincipal user, UUID deviceId, SaleRequest req) {
        if (req == null || req.clientId() == null) {
            throw BusinessException.of("mobile.sale.noClientId");
        }
        Optional<SalesInvoice> taken = invoiceRepo.findByClientId(req.clientId());
        if (taken.isPresent()) {
            return new Result(Outcome.ALREADY_ACCEPTED, taken.get(), null);
        }
        Optional<SyncConflict> kept = conflictRepo.findByClientId(req.clientId());
        if (kept.isPresent()) {
            return new Result(Outcome.ALREADY_CONFLICT, null, kept.get());
        }
        ApiDevice device = deviceRepo.findById(deviceId).orElseThrow(() -> new NotFoundException("ApiDevice", deviceId));
        Driver driver = driverRepo.findByUser_Id(user.getId()).orElse(null);
        Trip trip = driver == null ? null : tripRepo.findFirstByDriver_IdAndStatus(driver.getId(), TripStatus.DEPARTED).orElse(null);
        try {
            if (trip == null) {
                throw new Refused(SyncConflictReason.NO_TRIP, user.getUsername() + " is on no trip on the road");
            }
            if (req.tripId() != null && !req.tripId().equals(trip.getId())) {
                throw new Refused(SyncConflictReason.NOT_YOUR_TRIP, "made on trip " + req.tripId() + ", the driver is on " + trip.getNumber());
            }
            tripRepo.lockById(trip.getId());
            Trip detailed = tripRepo.findDetailedById(trip.getId()).orElseThrow();
            return accept(user, device, driver, detailed, req);
        } catch (Refused r) {
            return new Result(Outcome.CONFLICT, null, keep(user, device, trip, req, r));
        }
    }

    private Result accept(AppUserPrincipal user, ApiDevice device, Driver driver, Trip trip, SaleRequest req) {
        List<LineRequest> lines = req.lines() == null ? List.of() : req.lines();
        if (lines.isEmpty() || lines.stream().anyMatch(l -> l.unitId() == null || l.pricePerM2() == null)) {
            throw new Refused(SyncConflictReason.INVALID, "a sale without units, or a unit without its price");
        }
        if (lines.stream().map(LineRequest::unitId).distinct().count() != lines.size()) {
            throw new Refused(SyncConflictReason.INVALID, "a unit sold twice in the sale");
        }
        String tin = StringUtils.hasText(req.buyerTin()) ? req.buyerTin().replaceAll("[\\s-]", "") : null;
        if (tin != null && !tin.matches("^[0-9]{9}$")) {
            throw new Refused(SyncConflictReason.INVALID, "buyer TIN " + req.buyerTin() + " is not 9 digits");
        }
        // Each unit still on this vehicle (SYNC-05: sold or moved since, the sale waits for the supervisor)
        Map<UUID, StockUnit> units = unitRepo.findByIdIn(lines.stream().map(LineRequest::unitId).toList()).stream()
                .collect(Collectors.toMap(StockUnit::getId, Function.identity()));
        UUID vehicleLocation = trip.getVehicle().getLocation().getId();
        List<String> gone = lines.stream().filter(l -> {
            StockUnit u = units.get(l.unitId());
            return u == null || u.getStatus() != StockStatus.ON_VEHICLE || u.getLocation() == null || !u.getLocation().getId().equals(vehicleLocation);
        }).map(l -> units.containsKey(l.unitId()) ? units.get(l.unitId()).getCode() : String.valueOf(l.unitId())).toList();
        if (!gone.isEmpty()) {
            throw new Refused(SyncConflictReason.UNIT_NOT_ON_VEHICLE, String.join(", ", gone) + " no longer on " + trip.getVehicle().getPlate());
        }
        // The number given to this phone for this trip, unused
        TripInvoiceNumber number = StringUtils.hasText(req.number()) ? numberRepo.lockByNumber(req.number().trim()).orElse(null) : null;
        if (number == null || !number.getTripId().equals(trip.getId()) || !number.getDeviceId().equals(device.getId())) {
            throw new Refused(SyncConflictReason.NUMBER_NOT_ISSUED, "number " + req.number() + " was not given to this phone for " + trip.getNumber());
        }
        if (number.isUsed()) {
            throw new Refused(SyncConflictReason.NUMBER_USED, "number " + number.getNumber() + " is on another sale");
        }
        // Prices from the trip's frozen lists, within the driver's limit (MPOS-04), the amounts as the phone charged them
        Customer customer = customer(req.customerId());
        MobileSales.Snapshot snapshot = tripService.snapshot(trip.getId());
        BigDecimal limit = tripService.discountLimit(user.getId());
        int decimals = pricing.baseDecimals();
        List<Priced> priced = new ArrayList<>();
        for (LineRequest l : lines) {
            StockUnit unit = units.get(l.unitId());
            Product product = unit.getProduct();
            MobileSales.Resolved resolved = snapshot.resolve(customer.getPriceList() == null ? null : customer.getPriceList().getId(), product.getId())
                    .orElseThrow(() -> new Refused(SyncConflictReason.NO_PRICE, product.getCode() + " has no price on the trip's lists"));
            BigDecimal price = l.pricePerM2().setScale(2, java.math.RoundingMode.HALF_UP);
            if (!MobileSales.withinLimit(resolved.pricePerM2(), price, limit)) {
                throw new Refused(SyncConflictReason.PRICE_BELOW_LIMIT, unit.getCode() + " at " + price.toPlainString() + " per m², list "
                        + resolved.pricePerM2().toPlainString() + ", limit " + limit.toPlainString() + "%");
            }
            String reason = price.compareTo(resolved.pricePerM2()) == 0 ? null
                    : StringUtils.hasText(l.priceReason()) ? l.priceReason().trim() : "Price changed on the road";
            BigDecimal amount = MobileSales.lineAmount(price, unit.getWidthMm(), unit.getHeightMm(), resolved.list(),
                    product.getTaxCategory().getRate(), decimals);
            priced.add(new Priced(unit, resolved, price, reason, amount));
        }
        int differs = MobileSales.firstDifference(lines.stream().map(LineRequest::amount).toList(), priced.stream().map(Priced::amount).toList());
        if (differs >= 0) {
            throw new Refused(SyncConflictReason.TOTAL_MISMATCH, priced.get(differs).unit().getCode() + " charged "
                    + lines.get(differs).amount() + ", the trip's prices make it " + priced.get(differs).amount().toPlainString());
        }
        Vat.Totals totals = Vat.totals(priced.stream().map(p -> new Vat.Line(p.unit().getProduct().getTaxCategory().getEbmCode(),
                p.unit().getProduct().getTaxCategory().getRate(), p.amount())).toList());
        if (req.total() == null || req.total().compareTo(totals.gross()) != 0) {
            throw new Refused(SyncConflictReason.TOTAL_MISMATCH, "total " + req.total() + ", the lines make " + totals.gross().toPlainString());
        }
        List<MobileSales.Payment> payments = (req.payments() == null ? List.<PaymentRequest>of() : req.payments()).stream()
                .map(p -> new MobileSales.Payment(p.method(), p.amount(), p.reference() == null ? null : p.reference().trim())).toList();
        MobileSales.paymentProblem(payments, totals.gross()).ifPresent(problem -> {
            throw new Refused(SyncConflictReason.PAYMENT_MISMATCH, problem);
        });
        BigDecimal cash = payments.stream().filter(p -> p.method() == PaymentMethod.CASH).map(MobileSales.Payment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tendered = cash.signum() > 0 && req.cashTendered() != null && req.cashTendered().compareTo(cash) >= 0 ? req.cashTendered() : null;

        // Taken: issue the invoice as the phone printed it
        Set<UUID> productIds = priced.stream().map(p -> p.unit().getProduct().getId()).collect(Collectors.toCollection(TreeSet::new));
        List<Product> products = productRepo.lockAllById(productIds);
        PostingService.StockValues before = postingService.stockValues(products);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime madeAt = req.createdAt() == null ? now : req.createdAt().atZoneSameInstant(ZONE).toLocalDateTime();
        SalesInvoice sale = new SalesInvoice();
        sale.setChannel(SaleChannel.MOBILE);
        sale.setTrip(trip);
        sale.setDevice(device);
        sale.setClientId(req.clientId());
        sale.setClientCreatedAt(madeAt);
        sale.setSyncedAt(now);
        sale.setCustomer(customer);
        sale.setBuyerName(StringUtils.hasText(req.buyerName()) ? truncate(req.buyerName().trim(), 100) : null);
        sale.setBuyerTin(tin != null ? tin : customer.getType() != CustomerType.WALK_IN ? customer.getTin() : null);
        int no = 1;
        for (Priced p : priced) {
            SalesInvoiceLine line = new SalesInvoiceLine();
            line.setInvoice(sale);
            line.setLineNo(no++);
            line.setKind(SaleLineKind.STOCK_UNIT);
            line.setStockUnitId(p.unit().getId());
            line.setUnitCode(p.unit().getCode());
            line.setProduct(p.unit().getProduct());
            line.setWidthMm(p.unit().getWidthMm());
            line.setHeightMm(p.unit().getHeightMm());
            line.setQuantity(1);
            line.setChargeableAreaM2(Pricing.chargeableArea(p.unit().getWidthMm(), p.unit().getHeightMm(), p.resolved().list().minChargeableM2()));
            line.setPricePerM2(p.price());
            line.setPriceList(priceListRepo.getReferenceById(p.resolved().list().listId()));
            line.setPricesIncludeVat(p.resolved().list().pricesIncludeVat());
            line.setTaxCode(p.unit().getProduct().getTaxCategory().getEbmCode());
            line.setVatRate(p.unit().getProduct().getTaxCategory().getRate());
            line.setAmount(p.amount());
            line.setListPrice(p.reason() == null ? null : p.resolved().pricePerM2());
            line.setPriceReason(p.reason() == null ? null : truncate(p.reason(), 255));
            sale.getLines().add(line);
        }
        // The day the phone made it, if the trip was on the road then; otherwise the day it reached the server
        LocalDate made = madeAt.toLocalDate();
        LocalDate departed = trip.getDepartedAt().toLocalDate();
        sale.setInvoiceDate(made.isBefore(departed) || made.isAfter(now.toLocalDate()) ? now.toLocalDate() : made);
        sale.setNumber(number.getNumber());
        sale.setNetAmount(totals.net());
        sale.setVatAmount(totals.vat());
        sale.setTotalAmount(totals.gross());
        sale.setBalanceDue(BigDecimal.ZERO.setScale(Journal.SCALE));
        sale.setCashTendered(tendered);
        sale.setChangeGiven(tendered == null ? null : tendered.subtract(cash));
        sale.setPostedAt(now);
        sale.setPostedBy(user.getUsername());
        sale.setStatus(SalesInvoiceStatus.POSTED);
        invoiceRepo.save(sale);
        for (Priced p : priced) {
            stockService.sellFromVehicle(p.unit(), sale.getId(), sale.getNumber());
        }
        List<SalesPayment> taken = new ArrayList<>();
        int pno = 1;
        for (MobileSales.Payment p : payments) {
            SalesPayment payment = new SalesPayment();
            payment.setInvoiceId(sale.getId());
            payment.setLineNo(pno++);
            payment.setMethod(p.method());
            payment.setAmount(p.amount());
            payment.setReference(p.method() == PaymentMethod.MOBILE_MONEY ? truncate(p.reference(), 60) : null);
            payment.setTripId(trip.getId());
            payment.setCashTendered(p.method() == PaymentMethod.CASH && tendered != null && payments.stream()
                    .filter(x -> x.method() == PaymentMethod.CASH).count() == 1 ? tendered : null);
            payment.setCreatedAt(now);
            payment.setUsername(user.getUsername());
            taken.add(paymentRepo.save(payment));
        }
        number.setInvoiceId(sale.getId());
        number.setUsedAt(now);
        postingService.mobileSale(sale, taken, before, driver.getId());
        ebmService.queueSale(sale);                                 // signed after the commit, or when EBM answers (SYNC-06)
        return new Result(Outcome.ACCEPTED, sale, null);
    }

    /** Keeps a sale the server cannot take as it was made, and tells the supervisors (SYNC-05). */
    private SyncConflict keep(AppUserPrincipal user, ApiDevice device, Trip trip, SaleRequest req, Refused r) {
        SyncConflict c = new SyncConflict();
        c.setClientId(req.clientId());
        c.setTrip(trip);
        c.setDevice(device);
        c.setUsername(user.getUsername());
        c.setNumber(req.number() == null ? null : truncate(req.number().trim(), 30));
        c.setReason(r.reason);
        c.setDetail(truncate(r.detail, 500));
        c.setTotalAmount(req.total());
        c.setPayload(payload(req));
        c.setClientCreatedAt(req.createdAt() == null ? null : req.createdAt().atZoneSameInstant(ZONE).toLocalDateTime());
        c.setReceivedAt(LocalDateTime.now(clock));
        conflictRepo.save(c);
        notifier.holders("REVIEW_SYNC_CONFLICT", null, NotificationKind.SYNC, "notify.sync.conflict.title", "notify.sync.conflict.message",
                "/sync-conflicts/" + c.getId(), c.getNumber() == null ? "—" : c.getNumber(), user.getFullName(), r.reason.name());
        return c;
    }

    // ---------------------------------------------------------------- reading

    /** A sale the phone sent, by its UUID: taken, kept as a conflict, or unknown. */
    public Result find(UUID clientId) {
        Optional<SalesInvoice> sale = invoiceRepo.findMobileByClientId(clientId);
        if (sale.isPresent()) {
            return new Result(Outcome.ALREADY_ACCEPTED, sale.get(), null);
        }
        return conflictRepo.findByClientId(clientId).map(c -> new Result(Outcome.ALREADY_CONFLICT, null, c)).orElse(null);
    }

    /** A trip's mobile sales, by number. */
    public List<SalesInvoice> salesOf(UUID tripId) {
        return invoiceRepo.findByTrip_IdOrderByNumberAsc(tripId);
    }

    private Customer customer(UUID id) {
        Optional<Customer> chosen = id == null ? Optional.empty() : customerRepo.findById(id);
        return chosen.orElseGet(() -> customerRepo.findByEnabledTrueOrderByNameAsc().stream().filter(Customer::isDefaultCustomer).findFirst()
                .orElseThrow(() -> new IllegalStateException("No default walk-in customer (V9)")));
    }

    private String payload(SaleRequest req) {
        try {
            return mapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            return String.valueOf(req);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
