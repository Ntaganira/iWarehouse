package com.ntaganira.heritier.iWarehouse.controller.api;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.service.EbmService;
import com.ntaganira.heritier.iWarehouse.service.Labels;
import com.ntaganira.heritier.iWarehouse.service.MobileSaleService;
import com.ntaganira.heritier.iWarehouse.service.MobileTripService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileApi.java
 * - Date      : 2026. 10. 10.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What the mobile POS API (/api/v1) sends and receives, as JSON records: never entities. Versioned by its
 *               path: a change a phone cannot read goes under /api/v2.
 * </pre>
 */
public final class MobileApi {

    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private MobileApi() {
    }

    // ---------------------------------------------------------------- signing in

    public record LoginRequest(String username, String password, String deviceId, String deviceName) {
    }

    public record UserView(String username, String fullName) {
    }

    public record LoginResponse(String token, UUID device, UserView user) {
    }

    // ---------------------------------------------------------------- the trip (SYNC-01)

    public record VehicleView(String plate, String model, int maxPieces, int maxLoadKg) {
    }

    public record TripView(UUID id, String number, LocalDate date, String area, VehicleView vehicle, String driver, LocalDateTime departedAt) {
    }

    public record UnitView(UUID id, String code, UUID productId, String productCode, String productName, int widthMm, int heightMm,
                           BigDecimal areaM2, BigDecimal weightKg, String taxCode, BigDecimal vatRate) {
    }

    public record PriceListView(UUID id, String code, String name, boolean defaultList, boolean pricesIncludeVat, BigDecimal minChargeableM2) {
    }

    public record PriceView(UUID listId, UUID productId, BigDecimal pricePerM2) {
    }

    /** creditLeft: what the customer may take on credit on this trip (decision D8, frozen at the download), null without credit. */
    public record CustomerView(UUID id, String code, String name, String type, String tin, String phone, UUID priceListId,
                               boolean defaultCustomer, BigDecimal creditLeft) {
    }

    /** logo: the company's logo as a data URI, printed above its name on receipts (as the web's receipts do), or null. */
    public record CompanyView(String name, String tin, String address, String phone, String logo) {
    }

    public record TripBundle(TripView trip, List<UnitView> units, List<PriceListView> priceLists, List<PriceView> prices,
                             List<CustomerView> customers, List<String> numbers, BigDecimal discountLimit, int decimals,
                             CompanyView company, String ebmMode, LocalDateTime downloadedAt, List<UUID> reportedBroken,
                             BigDecimal cashDeclared, boolean fieldCredit) {
    }

    static TripBundle bundle(MobileTripService.Bundle b, Messages messages) {
        Trip t = b.trip();
        Vehicle v = t.getVehicle();
        TripView trip = new TripView(t.getId(), t.getNumber(), t.getTripDate(), t.getArea(),
                new VehicleView(v.getPlate(), v.getModel(), v.getMaxPieces(), v.getMaxLoadKg()), t.getDriver().getUser().getFullName(),
                t.getDepartedAt());
        List<UnitView> units = b.units().stream().map(u -> new UnitView(u.getId(), u.getCode(), u.getProduct().getId(),
                u.getProduct().getCode(), productName(u.getProduct(), messages), u.getWidthMm(), u.getHeightMm(), u.getAreaM2(),
                u.getWeightKg(), u.getProduct().getTaxCategory().getEbmCode(), u.getProduct().getTaxCategory().getRate())).toList();
        List<PriceListView> lists = b.lists().stream().map(l -> new PriceListView(l.getPriceListId(), l.getCode(), l.getName(),
                l.isDefaultList(), l.isPricesIncludeVat(), l.getMinChargeableM2())).toList();
        List<PriceView> prices = b.prices().stream().map(p -> new PriceView(p.getPriceListId(), p.getProductId(), p.getPricePerM2())).toList();
        List<CustomerView> customers = b.customers().stream().map(c -> new CustomerView(c.getId(), c.getCode(), c.getName(),
                c.getType().name(), c.getTin(), c.getPhone(), c.getPriceList() == null ? null : c.getPriceList().getId(),
                c.isDefaultCustomer(), b.creditLeft().get(c.getId()))).toList();
        MobileTripService.Company co = b.company();
        return new TripBundle(trip, units, lists, prices, customers, b.numbers(), b.discountLimit(), b.decimals(),
                new CompanyView(co.name(), co.tin(), co.address(), co.phone(), co.logo()), b.ebmMode().name(), b.downloadedAt(), b.reportedBroken(),
                b.cashDeclared(), b.creditOn());
    }

    /** "Tinted Bronze 6 mm": the type in the phone's language, colour or finish as entered, thickness. */
    static String productName(Product p, Messages messages) {
        return messages.get("glass.type." + p.getGlassType()) + (p.getVariant() != null ? " " + p.getVariant() : "") + " "
                + p.getThicknessLabel() + " mm";
    }

    // ---------------------------------------------------------------- a sale (SYNC-03..06)

    /** The EBM signature of a sale, once signed (SYNC-06): what the receipt's SDC block prints, and its QR code. */
    public record EbmView(String status, boolean signed, boolean simulated, Long invcNo, String receiptLabel, String sdcId, String mrcNo,
                          String internalData, String signature, String signedAt, String qrSvg) {
    }

    public record ConflictView(String reason, String reasonText, String detail) {
    }

    /** What the server did with a sale: ACCEPTED (now or before), CONFLICT (kept for the supervisor), with what it knows. */
    public record SaleResult(UUID clientId, String outcome, String number, UUID invoiceId, BigDecimal total, EbmView ebm,
                             ConflictView conflict) {
    }

    static SaleResult result(UUID clientId, MobileSaleService.Result r, EbmService ebmService, Messages messages) {
        if (r.isAccepted()) {
            SalesInvoice sale = r.invoice();
            EbmView ebm = ebmService.ofInvoice(sale.getId()).map(e -> ebm(e, ebmService)).orElse(null);
            return new SaleResult(clientId, "ACCEPTED", sale.getNumber(), sale.getId(), sale.getTotalAmount(), ebm, null);
        }
        SyncConflict c = r.conflict();
        return new SaleResult(clientId, "CONFLICT", c.getNumber(), null, c.getTotalAmount(), null,
                new ConflictView(c.getReason().name(), messages.get("sync.reason." + c.getReason()), c.getDetail()));
    }

    static EbmView ebm(EbmReceipt e, EbmService ebmService) {
        if (!e.isSigned()) {
            return new EbmView(e.getStatus().name(), false, e.isSimulated(), e.getInvcNo(), null, null, null, null, null, null, null);
        }
        LocalDateTime at = e.getVsdcDate() != null ? e.getVsdcDate() : e.getSignedAt();
        return new EbmView(e.getStatus().name(), true, e.isSimulated(), e.getInvcNo(), e.getReceiptLabel(), e.getSdcId(), e.getMrcNo(),
                e.getIntrlDataGroups(), e.getRcptSignGroups(), at == null ? null : at.format(DAY_TIME), Labels.qrSvg(ebmService.qrData(e)));
    }

    // ---------------------------------------------------------------- the end of the day (MPOS-06, MPOS-07)

    /**
     * A breakage report as the phone sends it (MPOS-06): its own id, the trip and unit, why and where, when it happened, and
     * the photo as a data URL ("data:image/jpeg;base64,...").
     */
    public record BreakageRequest(UUID clientId, UUID tripId, UUID unitId, String reason, String place, java.time.OffsetDateTime happenedAt,
                                  String photo, String photoName) {
    }

    /** What the server did with a report: RECORDED (now) or ALREADY (before), and where it stands. */
    public record BreakageResult(UUID clientId, String outcome, String unitCode, String status, String decisionNote) {
    }

    static BreakageResult breakage(UUID clientId, String outcome, TripBreakage b) {
        return new BreakageResult(clientId, outcome, b.getUnitCode(), b.getStatus().name(), b.getDecisionNote());
    }

    /** The cash the driver declares at the end of the day (MPOS-07). */
    public record DeclarationRequest(UUID tripId, BigDecimal amount) {
    }

    public record DeclarationResult(UUID tripId, String tripNumber, BigDecimal cashDeclared, LocalDateTime declaredAt) {
    }

    // ---------------------------------------------------------------- on the road: deposits and counts (N.07, N.12)

    /** Collections the driver banked or sent by mobile money (BP 7.3 rule 4): its own id, the trip, how, how much, the reference, the day. */
    public record DepositRequest(UUID clientId, UUID tripId, String method, BigDecimal amount, String reference, LocalDate depositedOn) {
    }

    /** What the server did with a deposit: RECORDED (now or before), its number, and what is left on the float. */
    public record DepositResult(UUID clientId, String outcome, String number, BigDecimal amount, BigDecimal floatLeft) {
    }

    /** The driver's daily count of what is on board (SDP 6): its own id, the trip, the labels scanned. */
    public record CountRequest(UUID clientId, UUID tripId, List<String> codes, String note) {
    }

    /** What a count found: how many expected, found, the labels missing and those not expected. */
    public record CountResult(UUID clientId, int expected, int found, List<String> missing, List<String> extra) {
    }

    /** An API error: a message key and its text in the phone's language. */
    public record ErrorView(String error, String message) {
    }
}
