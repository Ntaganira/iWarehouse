package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockMovement;
import com.ntaganira.heritier.iWarehouse.entity.StockReconciliation;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StockReviewController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The stock card of a glass for the business or one site (IMP 7.1, STC-003), the daily review of the stock
 *               movements (IMP 12) and the monthly stock reconciliation (BP 7.4, Table 9, Annex I step 5), each exported to
 *               Excel or PDF. Card and day: PAGE_STOCK_SUMMARY + PERM_VIEW_STOCK, signing a day off PERM_REVIEW_DAILY_STOCK;
 *               the month: PAGE_STOCK_RECONCILIATION + PERM_VIEW_STOCK_RECONCILIATION, signing it off
 *               PERM_REVIEW_STOCK_RECONCILIATION.
 * </pre>
 */
@Controller
public class StockReviewController {

    static final String MODULE = "Stock";
    private static final String STOCK = "hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_VIEW_STOCK')";
    private static final String RECON = "hasAuthority('PAGE_STOCK_RECONCILIATION') and hasAuthority('PERM_VIEW_STOCK_RECONCILIATION')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final StockCardService cardService;
    private final StockReviewService reviewService;
    private final StockSummaryService summaryService;
    private final SiteTransferService siteTransferService;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final InventoryReportService inventoryReports;
    private final InventorySheets inventorySheets;

    public StockReviewController(StockCardService cardService, StockReviewService reviewService, StockSummaryService summaryService,
                                 SiteTransferService siteTransferService, ReportFiles reportFiles, ActivityLogService activityLogService,
                                 Messages messages, InventoryReportService inventoryReports, InventorySheets inventorySheets) {
        this.inventoryReports = inventoryReports;
        this.inventorySheets = inventorySheets;
        this.cardService = cardService;
        this.reviewService = reviewService;
        this.summaryService = summaryService;
        this.siteTransferService = siteTransferService;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- the stock card (IMP 7.1)

    @GetMapping("/stock/card")
    @PreAuthorize(STOCK)
    public String card(@RequestParam(required = false) UUID product, @RequestParam(required = false) UUID site,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate[] period = period(from, to);
        model.addAttribute("products", summaryService.products());
        model.addAttribute("sites", siteTransferService.sites());
        model.addAttribute("product", product);
        model.addAttribute("site", site);
        model.addAttribute("from", period[0]);
        model.addAttribute("to", period[1]);
        if (product != null) {
            StockCardService.Card card = cardService.card(product, site, period[0], period[1]);
            model.addAttribute("card", card);
            model.addAttribute("rows", Paging.of(card.rows(), Paging.page(page)));
        }
        model.addAttribute("query", QueryString.of("product", str(product), "site", str(site), "from", period[0].toString(), "to", period[1].toString()));
        return "stock/card";
    }

    @GetMapping("/stock/card/export")
    @PreAuthorize(STOCK)
    public ResponseEntity<byte[]> cardExport(@RequestParam UUID product, @RequestParam(required = false) UUID site,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        LocalDate[] period = period(from, to);
        StockCardService.Card card = cardService.card(product, site, period[0], period[1]);
        String scope = card.site() == null ? messages.get("stockCard.allSites") : card.site().getName();
        Excel.Builder sheet = Excel.sheet(messages.get("stockCard.title"), messages.get("stockCard.exportTitle", card.product().getCode()),
                messages.get("stockCard.exportSubtitle", scope, period[0].format(DAY), period[1].format(DAY)),
                messages.get("activity.dateTime"), messages.get("stock.movementType"), messages.get("stock.code"), messages.get("stock.reference"),
                messages.get("activity.user"), messages.get("stockCard.in"), messages.get("stockCard.out"), messages.get("stockCard.balance"),
                messages.get("stockCard.balanceM2"));
        sheet.bold(messages.get("stockCard.opening"), null, null, null, null, null, null, card.openingPieces(), card.openingM2());
        for (StockCardService.Row r : card.rows()) {
            sheet.row(r.at().format(TIME), messages.get("stock.movement." + r.type()), r.unitCode(), r.refNumber(), r.user(),
                    r.isIn() ? r.pieces() : null, r.isIn() ? null : -r.pieces(), r.balancePieces(), r.balanceM2());
        }
        sheet.bold(messages.get("stockCard.closing"), null, null, null, null, card.inPieces(), card.outPieces(), card.closingPieces(), card.closingM2());
        activityLogService.record(MODULE, "EXPORT_STOCK_CARD", "Exported the stock card of " + card.product().getCode() + " (" + scope + ", "
                + period[0] + " to " + period[1] + ") to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "stock-card-" + card.product().getCode() + "-" + period[1], sheet.build());
    }

    // ---------------------------------------------------------------- the day (IMP 12)

    @GetMapping("/stock/daily-review")
    @PreAuthorize(STOCK)
    public String day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                      @RequestParam(required = false) MovementType type, @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "0") int rpage, Model model) {
        LocalDate d = day == null || day.isAfter(reviewService.today()) ? reviewService.today() : day;
        Page<StockMovement> movements = reviewService.dayMovements(d, type, Paging.page(page), Paging.SIZE);
        model.addAttribute("day", d);
        model.addAttribute("today", reviewService.today());
        model.addAttribute("type", type);
        model.addAttribute("types", MovementType.values());
        model.addAttribute("byType", reviewService.dayByType(d));
        model.addAttribute("movements", movements);
        model.addAttribute("units", reviewService.unitsOf(movements.getContent()));
        model.addAttribute("locations", siteTransferService.locations());
        model.addAttribute("review", reviewService.dayReview(d).orElse(null));
        model.addAttribute("reviews", reviewService.dayReviews(Paging.page(rpage), Paging.SIZE));
        // The daily inventory report (IMP 14.1, 18; DIR-011): values, sites, deliveries, transfers, exceptions
        model.addAttribute("report", inventoryReports.day(d));
        model.addAttribute("query", QueryString.of("day", d.toString(), "type", type == null ? null : type.name()));
        return "stock/daily-review";
    }

    /** The Internal Controller countersigns a day the Warehouse Manager signed off (IMP 14.1, M.01). */
    @PostMapping("/stock/daily-review/countersign")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_COUNTERSIGN_DAILY_STOCK')")
    public String countersignDay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                 @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            var review = inventoryReports.countersign(day, note);
            activityLogService.record(MODULE, "APPROVE_DAILY_STOCK", "Countersigned the daily inventory report of " + day + " signed off by "
                    + review.getReviewedBy() + (review.getCountersignNote() == null ? "" : ": " + review.getCountersignNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("inventoryReport.countersigned", day.format(DAY)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_DAILY_STOCK", "Failed to countersign the daily inventory report of " + day + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock/daily-review?day=" + day;
    }

    /** The daily inventory report in Excel or PDF (IMP 18, DIR-011): the figures, sites, every movement, deliveries, transfers, exceptions. */
    @GetMapping("/stock/daily-review/export")
    @PreAuthorize(STOCK)
    public ResponseEntity<byte[]> dayExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                            @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        LocalDate d = day == null || day.isAfter(reviewService.today()) ? reviewService.today() : day;
        InventoryReportService.Day r = inventoryReports.day(d);
        String sub = d.format(DAY);
        Excel.Builder summary = Excel.sheet(messages.get("inventoryReport.daily"), messages.get("inventoryReport.daily"), sub,
                messages.get("stockRecon.check"), messages.get("stockRecon.figure"));
        if (seeCost) {
            summary.row(messages.get("inventoryReport.openingValue"), r.openingValue());
            summary.row(messages.get("inventoryReport.closingValue"), r.closingValue());
            summary.bold(messages.get("inventoryReport.change"), r.getChange());
        }
        summary.row(messages.get("inventoryReport.unitsIn"), r.sites().total().inUnits());
        summary.row(messages.get("inventoryReport.unitsOut"), r.sites().total().outUnits());
        summary.row(messages.get("inventoryReport.unitsHeld"), r.sites().total().units());
        summary.row(messages.get("inventoryReport.handOvers"), r.handOvers());
        reviewService.dayReview(d).ifPresentOrElse(rv -> {
            summary.row(messages.get("stockReview.signedBy"), rv.getReviewedBy() + " - " + rv.getReviewedAt().format(TIME));
            summary.row(messages.get("inventoryReport.countersignedBy"), rv.isCountersigned()
                    ? rv.getCountersignedBy() + " - " + rv.getCountersignedAt().format(TIME) : messages.get("inventoryReport.notCountersigned"));
        }, () -> summary.row(messages.get("stockReview.signedBy"), messages.get("stockReview.notYet", sub)));
        Excel.Builder sites = inventorySheets.sites(r.sites(), messages.get("inventoryReport.bySite"), sub, seeCost);
        Excel.Builder types = Excel.sheet(messages.get("stockReview.byType"), messages.get("stockReview.byType"), sub,
                messages.get("stock.movementType"), messages.get("stockRecon.count"), messages.get("summary.area"), messages.get("stockRecon.cost"));
        r.byType().forEach(t -> types.row(messages.get("stock.movement." + t.type()), t.count(), t.areaM2(), seeCost ? t.cost() : null));
        Map<UUID, Location> places = siteTransferService.locations();
        Excel.Builder moves = Excel.sheet(messages.get("stockReview.movements"), messages.get("stockReview.movements"), sub,
                messages.get("activity.dateTime"), messages.get("stock.movementType"), messages.get("stock.code"), messages.get("stock.fromTo"),
                messages.get("stock.reference"), messages.get("activity.user"));
        Page<StockMovement> all = reviewService.dayMovements(d, null, 0, 10_000);
        Map<UUID, StockUnit> units = reviewService.unitsOf(all.getContent());
        for (StockMovement m : all.getContent()) {
            StockUnit u = units.get(m.getStockUnitId());
            moves.row(m.getMovedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")), messages.get("stock.movement." + m.getType()),
                    u == null ? "" : u.getCode(), code(places, m.getFromLocationId()) + " > " + code(places, m.getToLocationId()),
                    m.getRefNumber(), m.getUsername());
        }
        Excel.Builder docs = Excel.sheet(messages.get("inventoryReport.documents"), messages.get("inventoryReport.documents"), sub,
                messages.get("inventoryReport.section"), messages.get("stock.reference"), messages.get("inventoryReport.detail"),
                messages.get("stockRecon.count"), messages.get("stockRecon.cost"));
        r.deliveries().forEach(i -> docs.row(messages.get("inventoryReport.deliveries"), i.number(), i.detail(), null, null));
        r.sold().forEach(x -> docs.row(messages.get("stock.movement." + x.type()), x.refNumber(), null, x.units(), seeCost ? x.cost() : null));
        r.transfers().forEach(x -> docs.row(messages.get("stock.movement." + x.type()), x.refNumber(), null, x.units(), seeCost ? x.cost() : null));
        r.adjustments().forEach(x -> docs.row(messages.get("stock.movement." + x.type()), x.refNumber(), null, x.units(), seeCost ? x.cost() : null));
        r.exceptions().forEach(i -> docs.row(messages.get(i.kindKey()), i.number(), i.detail(), null, seeCost ? i.value() : null));
        activityLogService.record(MODULE, "EXPORT_DAILY_STOCK", "Exported the daily inventory report of " + d + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "daily-inventory-" + d, summary.build(), sites.build(), types.build(), moves.build(), docs.build());
    }

    private static String code(Map<UUID, Location> places, UUID id) {
        Location l = id == null ? null : places.get(id);
        return l == null ? "-" : l.getCode();
    }

    @PostMapping("/stock/daily-review")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_REVIEW_DAILY_STOCK')")
    public String reviewDay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day, @RequestParam(required = false) String note,
                            RedirectAttributes redirect) {
        try {
            var review = reviewService.reviewDay(day, note);
            activityLogService.record(MODULE, "REVIEW_DAILY_STOCK", "Reviewed the stock movements of " + day + " (" + review.getMovements()
                    + " movement(s))" + (review.getNote() == null ? "" : ": " + review.getNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("stockReview.day.reviewed", day.format(DAY)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVIEW_DAILY_STOCK", "Failed to review the stock movements of " + day + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock/daily-review?day=" + day;
    }

    // ---------------------------------------------------------------- the month (BP 7.4, Annex I step 5)

    @GetMapping("/reports/stock-reconciliation")
    @PreAuthorize(RECON)
    public String month(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page, Model model) {
        YearMonth m = month(month);
        model.addAttribute("month", m);
        model.addAttribute("monthValue", m.toString());
        model.addAttribute("maxMonth", reviewService.thisMonth().toString());
        model.addAttribute("ended", m.isBefore(reviewService.thisMonth()));
        model.addAttribute("recon", reviewService.month(m));
        model.addAttribute("review", reviewService.monthReview(m).orElse(null));
        model.addAttribute("reviews", reviewService.monthReviews(Paging.page(page), Paging.SIZE));
        model.addAttribute("query", QueryString.of("month", m.toString()));
        return "reports/stock-reconciliation";
    }

    @PostMapping("/reports/stock-reconciliation")
    @PreAuthorize("hasAuthority('PAGE_STOCK_RECONCILIATION') and hasAuthority('PERM_REVIEW_STOCK_RECONCILIATION')")
    public String reviewMonth(@RequestParam String month, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        YearMonth m = month(month);
        try {
            StockReconciliation r = reviewService.reviewMonth(m, note);
            activityLogService.record(MODULE, "REVIEW_STOCK_RECONCILIATION", "Signed the stock reconciliation of " + m + " off: variance "
                    + r.getVarianceValue().toPlainString() + " RWF (" + r.getVariancePercent().toPlainString() + "% of stock)"
                    + (r.isAboveTolerance() ? ", above tolerance" : "") + (r.getNote() == null ? "" : ": " + r.getNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("stockReview.month.reviewed", label(m)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVIEW_STOCK_RECONCILIATION", "Failed to sign the stock reconciliation of " + m + " off: " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/reports/stock-reconciliation?month=" + m;
    }

    @GetMapping("/reports/stock-reconciliation/export")
    @PreAuthorize(RECON)
    public ResponseEntity<byte[]> monthExport(@RequestParam(required = false) String month,
                                              @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth m = month(month);
        StockReviewService.Month r = reviewService.month(m);
        Excel.Builder types = Excel.sheet(messages.get("stockRecon.byType"), messages.get("stockRecon.title"),
                messages.get("stockRecon.exportSubtitle", label(m)), messages.get("stock.movementType"), messages.get("stockRecon.count"),
                messages.get("summary.area"), messages.get("stockRecon.cost"));
        for (StockReviewService.ByType t : r.byType()) {
            types.row(messages.get("stock.movement." + t.type()), t.count(), t.areaM2(), t.cost());
        }
        Excel.Builder checks = Excel.sheet(messages.get("stockRecon.checks"), messages.get("stockRecon.checks"), label(m),
                messages.get("stockRecon.check"), messages.get("stockRecon.figure"));
        checks.row(messages.get("stockRecon.loaded"), r.vehicles().loaded());
        checks.row(messages.get("stockRecon.sold"), r.vehicles().sold());
        checks.row(messages.get("stockRecon.returned"), r.vehicles().returned());
        checks.row(messages.get("stockRecon.broken"), r.vehicles().broken());
        checks.row(messages.get("stockRecon.onRoad"), r.vehicles().onTheRoad());
        checks.bold(messages.get("stockRecon.missing"), r.vehicles().getDifference());
        checks.row(messages.get("stockRecon.dispatched"), r.sites().dispatched());
        checks.row(messages.get("stockRecon.arrived"), r.sites().arrived());
        checks.row(messages.get("stockRecon.inTransit"), r.sites().inTransit());
        checks.bold(messages.get("stockRecon.notArrived"), r.sites().getDifference());
        checks.row(messages.get("stockRecon.ticketsRaised"), r.ticketsRaised());
        checks.bold(messages.get("stockRecon.ticketsOpen"), r.ticketsOpen());
        checks.row(messages.get("stockRecon.lostUnits"), r.lostUnits());
        checks.row(messages.get("stockRecon.lostValue"), r.lostValue());
        checks.row(messages.get("stockRecon.stockValue"), r.stockValue());
        checks.bold(messages.get("stockRecon.percent"), r.percent());
        checks.row(messages.get("stockRecon.tolerance"), r.tolerance());
        activityLogService.record(MODULE, "EXPORT_STOCK_RECONCILIATION", "Exported the stock reconciliation of " + m + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "stock-reconciliation-" + m, types.build(), checks.build());
    }

    // ---------------------------------------------------------------- helpers

    /** From the first of the month to today unless asked; never past today, never from after to. */
    private LocalDate[] period(LocalDate from, LocalDate to) {
        LocalDate today = cardService.today();
        LocalDate t = to == null || to.isAfter(today) ? today : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        if (f.isAfter(t)) {
            f = t;
        }
        return new LocalDate[]{f, t};
    }

    /** The month asked (yyyy-MM), else the last ended one; never after this month. */
    private YearMonth month(String month) {
        YearMonth now = reviewService.thisMonth();
        YearMonth m;
        try {
            m = month == null || month.isBlank() ? now.minusMonths(1) : YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            m = now.minusMonths(1);
        }
        return m.isAfter(now) ? now : m;
    }

    private String label(YearMonth m) {
        return m.atDay(1).format(DateTimeFormatter.ofPattern("MM/yyyy"));
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
