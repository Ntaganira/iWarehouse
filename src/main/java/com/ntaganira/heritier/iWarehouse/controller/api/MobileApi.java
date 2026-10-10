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
 * - User      : Hntaganira
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

    public record CustomerView(UUID id, String code, String name, String type, String tin, String phone, UUID priceListId,
                               boolean defaultCustomer) {
    }

    public record CompanyView(String name, String tin, String address, String phone) {
    }

    public record TripBundle(TripView trip, List<UnitView> units, List<PriceListView> priceLists, List<PriceView> prices,
                             List<CustomerView> customers, List<String> numbers, BigDecimal discountLimit, int decimals,
                             CompanyView company, String ebmMode, LocalDateTime downloadedAt) {
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
                c.isDefaultCustomer())).toList();
        MobileTripService.Company co = b.company();
        return new TripBundle(trip, units, lists, prices, customers, b.numbers(), b.discountLimit(), b.decimals(),
                new CompanyView(co.name(), co.tin(), co.address(), co.phone()), b.ebmMode().name(), b.downloadedAt());
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

    /** An API error: a message key and its text in the phone's language. */
    public record ErrorView(String error, String message) {
    }
}
