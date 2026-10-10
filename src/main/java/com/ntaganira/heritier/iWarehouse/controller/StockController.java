package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.CuttingJob;
import com.ntaganira.heritier.iWarehouse.entity.CuttingJobOutput;
import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.CuttingJobStatus;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.CuttingJobService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.GoodsReceiptService;
import com.ntaganira.heritier.iWarehouse.service.Labels;
import com.ntaganira.heritier.iWarehouse.service.LocationService;
import com.ntaganira.heritier.iWarehouse.service.StockReservationService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.StockAgeing;
import com.ntaganira.heritier.iWarehouse.service.StockReportService;
import com.ntaganira.heritier.iWarehouse.service.StockService;
import com.ntaganira.heritier.iWarehouse.service.StockSummary;
import com.ntaganira.heritier.iWarehouse.service.StockSummaryService;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StockController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Inventory screens (INV-01..04, INV-06): stock units with filters and the "smallest piece
 *               that fits" search, a unit's detail with its movements, the cut it came from or went
 *               to, and History, and labels (INV-03) of a receipt, crate, cutting job or unit, as a
 *               printable page or ZPL. A scanned label code in the search opens the unit.
 *               PAGE_STOCK + PERM_VIEW_STOCK; costs need PERM_VIEW_STOCK_COST, labels PERM_PRINT_LABEL.
 * </pre>
 */
@Controller
@RequestMapping("/stock")
public class StockController {

    static final String MODULE = "Inventory";

    private final StockService stockService;
    private final GoodsReceiptService receiptService;
    private final CuttingJobService jobService;
    private final LocationService locationService;
    private final ProductRepository productRepo;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final StockReservationService reservationService;
    private final StockSummaryService summaryService;
    private final StockReportService stockReports;
    private final ReportFiles reportFiles;
    private final Messages messages;

    public StockController(StockService stockService, GoodsReceiptService receiptService, CuttingJobService jobService,
                           LocationService locationService, ProductRepository productRepo, DataChangeService dataChangeService,
                           ActivityLogService activityLogService, StockReservationService reservationService,
                           StockSummaryService summaryService, StockReportService stockReports, ReportFiles reportFiles,
                           Messages messages) {
        this.stockReports = stockReports;
        this.reportFiles = reportFiles;
        this.stockService = stockService;
        this.receiptService = receiptService;
        this.jobService = jobService;
        this.locationService = locationService;
        this.productRepo = productRepo;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.reservationService = reservationService;
        this.summaryService = summaryService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_STOCK') and hasAuthority('PERM_VIEW_STOCK')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) UUID product,
                       @RequestParam(required = false) UUID location,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) UnitKind kind,
                       @RequestParam(required = false) Integer minWidth,
                       @RequestParam(required = false) Integer minHeight,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // A scanned or typed label code opens the unit; a rack or slot label lists what is on it (MD-02).
        if (StringUtils.hasText(search)) {
            Optional<StockUnit> scanned = stockService.findByCode(search);
            if (scanned.isPresent()) {
                return "redirect:/stock/" + scanned.get().getId();
            }
            Optional<Location> place = locationService.findByCode(search);
            if (place.isPresent()) {
                return "redirect:/stock?location=" + place.get().getId();
            }
        }
        Integer w = positive(minWidth);
        Integer h = positive(minHeight);
        StockService.UnitFilter filter = new StockService.UnitFilter(search, product, location, status, kind, w, h);
        Page<StockUnit> units = stockService.findPage(filter, Paging.page(page), Paging.SIZE);
        model.addAttribute("units", units);
        model.addAttribute("fitSearch", filter.fitSearch());
        model.addAttribute("products", productRepo.findAll(Sort.by("glassType", "variant", "thicknessMm")));
        model.addAttribute("locationRows", locationService.tree(false));
        model.addAttribute("statuses", StockStatus.values());
        model.addAttribute("kinds", UnitKind.values());
        model.addAttribute("search", search);
        model.addAttribute("product", product);
        model.addAttribute("location", location);
        model.addAttribute("status", status);
        model.addAttribute("kind", kind);
        model.addAttribute("minWidth", w);
        model.addAttribute("minHeight", h);
        model.addAttribute("paginationQuery", QueryString.of("search", search,
                "product", product == null ? null : product.toString(),
                "location", location == null ? null : location.toString(),
                "status", status, "kind", kind == null ? null : kind.name(),
                "minWidth", w == null ? null : w.toString(), "minHeight", h == null ? null : h.toString()));
        return "stock/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_STOCK') and hasAuthority('PERM_VIEW_STOCK')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "movements") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        StockUnit unit = stockService.findDetailed(id);
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        String open = List.of("movements", "cost", "history").contains(tab) && (seeCost || !tab.equals("cost")) ? tab : "movements";
        Map<UUID, Location> locations = stockService.locationsById();
        model.addAttribute("unit", unit);
        model.addAttribute("qr", Labels.qrSvg(unit.getCode()));
        // The open tab shows the page asked for; the others start at their first page.
        model.addAttribute("movements", Paging.of(stockService.movements(id), Paging.pageOf("movements", open, page)));
        // How the cost was built up (receipt, landed costs): only for those who may see costs.
        model.addAttribute("costEntries", Paging.of(seeCost ? stockService.costEntries(id) : List.of(), Paging.pageOf("cost", open, page)));
        model.addAttribute("locations", locations);
        model.addAttribute("path", unit.getLocation() == null ? List.of() : locationService.ancestors(unit.getLocation()));
        // The cut it was taken for or cut by, and the cut it came out of (PRD-03).
        model.addAttribute("sourceOf", jobService.jobOfSource(id).orElse(null));
        model.addAttribute("cutBy", jobService.jobOfOutput(id).orElse(null));
        // A pending adjustment holds the unit (INV-05); customers for the reserve dialog.
        model.addAttribute("heldBy", stockService.holds(List.of(id)).get(id));
        model.addAttribute("customers", unit.getStatus() == StockStatus.AVAILABLE && AppUserPrincipal.currentHas("PERM_RESERVE_STOCK")
                ? reservationService.customers() : List.of());
        model.addAttribute("history", dataChangeService.history("StockUnit", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "stock/view";
    }

    // ---------------------------------------------------------------- reservations (INV-05)

    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasAuthority('PAGE_STOCK') and hasAuthority('PERM_RESERVE_STOCK')")
    public String reserve(@PathVariable UUID id, @RequestParam(required = false) UUID customerId,
                          @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            StockUnit unit = reservationService.reserve(id, customerId, note);
            activityLogService.record(MODULE, "RESERVE_STOCK", "Reserved " + unit.getCode() + " for "
                    + unit.getReservedCustomer().getName() + (unit.getReservedNote() == null ? "" : ": " + unit.getReservedNote()),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("reservation.reserved", unit.getCode(),
                    unit.getReservedCustomer().getName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "RESERVE_STOCK", "Failed to reserve a unit: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock/" + id;
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('PAGE_STOCK') and hasAuthority('PERM_RESERVE_STOCK')")
    public String release(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > 255) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/stock/" + id;
        }
        try {
            StockUnit unit = com.ntaganira.heritier.iWarehouse.audit.AuditContext.withReason(reason.trim(),
                    () -> reservationService.release(id, reason));
            activityLogService.record(MODULE, "RELEASE_STOCK", "Released " + unit.getCode() + ": " + reason.trim(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("reservation.released", unit.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "RELEASE_STOCK", "Failed to release a unit: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock/" + id;
    }

    // ---------------------------------------------------------------- summary (INV-09, INV-10)

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_VIEW_STOCK')")
    public String summary(@RequestParam(defaultValue = "PRODUCT") StockSummary.GroupBy group,
                          @RequestParam(required = false) UUID product, @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "0") int rpage, Model model) {
        StockSummaryService.View view = summaryService.summary(group, product);
        model.addAttribute("view", view);
        // Two tables, each with its own page: the groups (page) and glass to reorder (rpage); totals stay overall
        model.addAttribute("rowPage", Paging.of(view.rows(), page));
        model.addAttribute("reorderPage", Paging.of(view.reorder(), rpage));
        model.addAttribute("groups", StockSummary.GroupBy.values());
        model.addAttribute("products", summaryService.products());
        model.addAttribute("group", group);
        model.addAttribute("product", product);
        model.addAttribute("query", QueryString.of("group", group.name(), "product", product == null ? null : product.toString()));
        return "stock/summary";
    }

    /** The summary and the glass to reorder in Excel or PDF (RPT-07); values only for those who may see costs. */
    @GetMapping("/summary/export")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_VIEW_STOCK')")
    public ResponseEntity<byte[]> summaryExport(@RequestParam(defaultValue = "PRODUCT") StockSummary.GroupBy group,
                                                @RequestParam(required = false) UUID product,
                                                @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        StockSummaryService.View view = summaryService.summary(group, product);
        List<String> headers = new ArrayList<>();
        if (group == StockSummary.GroupBy.PRODUCT_LOCATION) {
            headers.add(messages.get("summary.group.PRODUCT"));
            headers.add(messages.get("summary.group.LOCATION"));
        } else {
            headers.add(messages.get("summary.group." + group));
        }
        headers.add(messages.get("summary.pieces"));
        headers.add(messages.get("summary.area"));
        if (seeCost) {
            headers.add(messages.get("summary.value"));
        }
        Excel.Builder sheet = Excel.sheet(messages.get("summary.title"), messages.get("summary.title"),
                messages.get("summary.exportSubtitle", messages.get("summary.by." + group), day(stockReports.today())),
                headers.toArray(String[]::new));
        for (StockSummary.Row r : view.rows()) {
            List<Object> cells = new ArrayList<>();
            switch (group) {
                case PRODUCT -> cells.add(r.product() == null ? r.key() : r.product().getCode());
                case LOCATION -> cells.add(r.location() == null ? r.key() : r.location().getCode());
                case PRODUCT_LOCATION -> {
                    cells.add(r.product() == null ? "" : r.product().getCode());
                    cells.add(r.location() == null ? "" : r.location().getCode());
                }
                case STATUS -> cells.add(messages.get("stock.status." + r.status()));
            }
            cells.add(r.pieces());
            cells.add(r.areaM2());
            if (seeCost) {
                cells.add(r.value());
            }
            sheet.row(cells.toArray());
        }
        List<Object> total = new ArrayList<>();
        total.add(messages.get("cutting.total"));
        if (group == StockSummary.GroupBy.PRODUCT_LOCATION) {
            total.add(null);
        }
        total.add(view.total().pieces());
        total.add(view.total().areaM2());
        if (seeCost) {
            total.add(view.total().value());
        }
        sheet.bold(total.toArray());
        Excel.Builder reorder = Excel.sheet(messages.get("summary.reorderTitle"), messages.get("summary.reorderTitle"), null,
                messages.get("product.name"), messages.get("summary.available"), messages.get("summary.reorderLevel"), messages.get("summary.short"));
        view.reorder().forEach(r -> reorder.row(r.product().getCode(), r.availableM2(), r.levelM2(), r.getShortM2()));
        activityLogService.record(MODULE, "EXPORT_STOCK_SUMMARY", "Exported the stock summary by " + group + " to " + format.label(),
                ActivityStatus.SUCCESS);
        String name = "stock-summary-" + group.name().toLowerCase().replace('_', '-');
        return view.reorder().isEmpty() ? reportFiles.download(format, name, sheet.build())
                : reportFiles.download(format, name, sheet.build(), reorder.build());
    }

    // ---------------------------------------------------------------- off-cut ageing, slow-moving stock (RPT-02)

    @GetMapping("/offcut-ageing")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_VIEW_STOCK')")
    public String offcutAgeing(@RequestParam(required = false) UUID product, @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "0") int upage, Model model) {
        StockReportService.OffcutReport report = stockReports.offcuts(product);
        model.addAttribute("report", report);
        model.addAttribute("rowPage", Paging.of(report.rows(), Paging.page(page)));
        model.addAttribute("unitPage", Paging.of(report.units(), Paging.page(upage)));
        model.addAttribute("bands", StockAgeing.Band.values());
        model.addAttribute("products", summaryService.products());
        model.addAttribute("product", product);
        model.addAttribute("today", stockReports.today());
        model.addAttribute("query", QueryString.of("product", product == null ? null : product.toString()));
        return "stock/offcut-ageing";
    }

    @GetMapping("/offcut-ageing/export")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_VIEW_STOCK')")
    public ResponseEntity<byte[]> offcutAgeingExport(@RequestParam(required = false) UUID product,
                                                     @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        StockReportService.OffcutReport report = stockReports.offcuts(product);
        LocalDate today = stockReports.today();
        List<String> headers = new ArrayList<>(List.of(messages.get("product.name")));
        for (StockAgeing.Band b : StockAgeing.Band.values()) {
            headers.add(messages.get("ageing.band." + b) + " (m\u00b2)");
        }
        headers.add(messages.get("summary.pieces"));
        headers.add(messages.get("summary.area"));
        if (seeCost) {
            headers.add(messages.get("summary.value"));
        }
        Excel.Builder bands = Excel.sheet(messages.get("offcutAgeing.title"), messages.get("offcutAgeing.title"),
                messages.get("fin.asOf", day(today)), headers.toArray(String[]::new));
        for (StockAgeing.AgeRow r : report.rows()) {
            bands.row(ageCells(r, r.product().getCode(), seeCost));
        }
        bands.bold(ageCells(report.total(), messages.get("cutting.total"), seeCost));
        List<String> unitHeaders = new ArrayList<>(List.of(messages.get("stock.code"), messages.get("product.name"), messages.get("cutting.size"),
                messages.get("summary.area"), messages.get("stock.location"), messages.get("offcutAgeing.since"), messages.get("offcutAgeing.days")));
        if (seeCost) {
            unitHeaders.add(messages.get("summary.value"));
        }
        Excel.Builder units = Excel.sheet(messages.get("offcutAgeing.units"), messages.get("offcutAgeing.units"), null, unitHeaders.toArray(String[]::new));
        report.units().forEach(u -> units.row(unitCells(u, today, seeCost)));
        activityLogService.record(MODULE, "EXPORT_OFFCUT_AGEING", "Exported the off-cut ageing to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "offcut-ageing-" + today, bands.build(), units.build());
    }

    @GetMapping("/slow-moving")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_VIEW_STOCK')")
    public String slowMoving(@RequestParam(required = false) Integer days, @RequestParam(required = false) UUID product,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int upage, Model model) {
        int d = slowDays(days);
        StockReportService.SlowReport report = stockReports.slowMoving(d, product);
        model.addAttribute("report", report);
        model.addAttribute("rowPage", Paging.of(report.rows(), Paging.page(page)));
        model.addAttribute("unitPage", Paging.of(report.units(), Paging.page(upage)));
        model.addAttribute("products", summaryService.products());
        model.addAttribute("product", product);
        model.addAttribute("days", d);
        model.addAttribute("defaultDays", stockReports.slowMovingDays());
        model.addAttribute("today", stockReports.today());
        model.addAttribute("query", QueryString.of("days", String.valueOf(d), "product", product == null ? null : product.toString()));
        return "stock/slow-moving";
    }

    @GetMapping("/slow-moving/export")
    @PreAuthorize("hasAuthority('PAGE_STOCK_SUMMARY') and hasAuthority('PERM_VIEW_STOCK')")
    public ResponseEntity<byte[]> slowMovingExport(@RequestParam(required = false) Integer days, @RequestParam(required = false) UUID product,
                                                   @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        int d = slowDays(days);
        StockReportService.SlowReport report = stockReports.slowMoving(d, product);
        LocalDate today = stockReports.today();
        List<String> headers = new ArrayList<>(List.of(messages.get("product.name"), messages.get("summary.pieces"), messages.get("summary.area"),
                messages.get("slowMoving.oldPieces"), messages.get("slowMoving.oldArea")));
        if (seeCost) {
            headers.add(messages.get("slowMoving.oldValue"));
        }
        headers.add(messages.get("slowMoving.sold", d));
        headers.add(messages.get("slowMoving.lastSold"));
        Excel.Builder glass = Excel.sheet(messages.get("slowMoving.title"), messages.get("slowMoving.title"),
                messages.get("slowMoving.exportSubtitle", d, day(today)), headers.toArray(String[]::new));
        for (StockAgeing.SlowRow r : report.rows()) {
            glass.row(slowCells(r, r.product().getCode(), seeCost, r.lastSold()));
        }
        glass.bold(slowCells(report.total(), messages.get("cutting.total"), seeCost, null));
        List<String> unitHeaders = new ArrayList<>(List.of(messages.get("stock.code"), messages.get("product.name"), messages.get("cutting.size"),
                messages.get("summary.area"), messages.get("stock.location"), messages.get("offcutAgeing.since"), messages.get("offcutAgeing.days")));
        if (seeCost) {
            unitHeaders.add(messages.get("summary.value"));
        }
        Excel.Builder units = Excel.sheet(messages.get("slowMoving.units"), messages.get("slowMoving.units"), null, unitHeaders.toArray(String[]::new));
        report.units().forEach(u -> units.row(unitCells(u, today, seeCost)));
        activityLogService.record(MODULE, "EXPORT_SLOW_MOVING", "Exported the slow-moving stock (over " + d + " days) to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "slow-moving-" + today, glass.build(), units.build());
    }

    /** The days asked, or the Settings' days; kept between 1 and 730. */
    private int slowDays(Integer days) {
        int d = days == null ? stockReports.slowMovingDays() : days;
        return Math.max(1, Math.min(730, d));
    }

    private static Object[] ageCells(StockAgeing.AgeRow r, String label, boolean seeCost) {
        List<Object> cells = new ArrayList<>(List.of(label));
        for (StockAgeing.Band b : StockAgeing.Band.values()) {
            cells.add(r.get(b).isEmpty() ? null : r.get(b).areaM2());
        }
        cells.add(r.pieces());
        cells.add(r.areaM2());
        if (seeCost) {
            cells.add(r.value());
        }
        return cells.toArray();
    }

    private static Object[] slowCells(StockAgeing.SlowRow r, String label, boolean seeCost, LocalDate lastSold) {
        List<Object> cells = new ArrayList<>(List.of(label, r.pieces(), r.areaM2(), r.oldPieces(), r.oldAreaM2()));
        if (seeCost) {
            cells.add(r.oldValue());
        }
        cells.add(r.soldM2());
        cells.add(lastSold);
        return cells.toArray();
    }

    private Object[] unitCells(StockAgeing.Unit u, LocalDate today, boolean seeCost) {
        List<Object> cells = new ArrayList<>(List.of(u.code(), u.product().getCode(), u.widthMm() + " x " + u.heightMm(), u.areaM2()));
        cells.add(u.location() == null ? "" : u.location().getCode());
        cells.add(u.since());
        cells.add(u.days(today));
        if (seeCost) {
            cells.add(u.getValue());
        }
        return cells.toArray();
    }

    private static String day(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    /** Printable labels (50 x 30 mm) of a posted receipt, one crate, a completed cutting job, or one unit. */
    @GetMapping("/labels")
    @PreAuthorize("hasAuthority('PAGE_STOCK') and hasAuthority('PERM_PRINT_LABEL')")
    public String labels(@RequestParam(required = false) UUID receipt, @RequestParam(required = false) UUID crate,
                         @RequestParam(required = false) UUID job, @RequestParam(required = false) UUID unit, Model model) {
        LabelSet set = labelSet(receipt, crate, job, unit);
        Map<UUID, String> qr = new HashMap<>();
        for (StockUnit u : set.units()) {
            qr.put(u.getId(), Labels.qrSvg(u.getCode()));
        }
        model.addAttribute("units", set.units());
        model.addAttribute("qr", qr);
        model.addAttribute("title", set.title());
        model.addAttribute("backUrl", set.backUrl());
        model.addAttribute("zplUrl", "/stock/labels.zpl?" + set.query());
        activityLogService.record(MODULE, "PRINT_LABELS", "Opened " + set.units().size() + " label(s) of "
                + set.title() + " for printing", ActivityStatus.SUCCESS);
        return "stock/labels";
    }

    /** The same labels as ZPL, for a label printer driven directly. */
    @GetMapping(value = "/labels.zpl")
    @PreAuthorize("hasAuthority('PAGE_STOCK') and hasAuthority('PERM_PRINT_LABEL')")
    public ResponseEntity<byte[]> labelsZpl(@RequestParam(required = false) UUID receipt,
                                            @RequestParam(required = false) UUID crate,
                                            @RequestParam(required = false) UUID job,
                                            @RequestParam(required = false) UUID unit) {
        LabelSet set = labelSet(receipt, crate, job, unit);
        List<Labels.Label> labels = set.units().stream()
                .map(u -> new Labels.Label(u.getCode(), u.getProduct().getCode(), u.getProduct().getThicknessLabel(),
                        u.getWidthMm(), u.getHeightMm(), u.getCrateBatch() == null ? null : u.getCrateBatch().getBatchNo()))
                .toList();
        activityLogService.record(MODULE, "PRINT_LABELS", "Downloaded " + labels.size() + " label(s) of "
                + set.title() + " as ZPL", ActivityStatus.SUCCESS);
        String file = set.title().replaceAll("[^A-Za-z0-9-]", "_") + ".zpl";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(Labels.zpl(labels).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- helpers

    private record LabelSet(List<StockUnit> units, String title, String backUrl, String query) {
    }

    /** The units to label: of a posted receipt, of one crate, cut by a completed job (PRD-04), or one unit. */
    private LabelSet labelSet(UUID receiptId, UUID crateId, UUID jobId, UUID unitId) {
        if (unitId != null) {
            StockUnit unit = stockService.findDetailed(unitId);
            return new LabelSet(List.of(unit), unit.getCode(), "/stock/" + unitId, "unit=" + unitId);
        }
        if (jobId != null) {
            CuttingJob job = jobService.findById(jobId);
            if (job.getStatus() != CuttingJobStatus.COMPLETED) {
                throw new NotFoundException("CuttingJob", jobId);
            }
            CuttingJobService.Outcome outcome = jobService.outcome(job);
            List<StockUnit> units = outcome.outputs().stream()
                    .map(CuttingJobOutput::getStockUnitId).filter(Objects::nonNull)
                    .map(id -> outcome.units().get(id)).filter(Objects::nonNull)
                    .toList();
            return new LabelSet(units, job.getNumber(), "/cutting-jobs/" + jobId + "?tab=cut", "job=" + jobId);
        }
        if (receiptId != null) {
            GoodsReceipt receipt = receiptService.findById(receiptId);
            if (receipt.getStatus() != GoodsReceiptStatus.POSTED) {
                throw new NotFoundException("GoodsReceipt", receiptId);
            }
            List<StockUnit> units = stockService.unitsOfReceipt(receiptId);
            if (crateId != null) {
                units = units.stream().filter(u -> u.getCrateBatch() != null && crateId.equals(u.getCrateBatch().getId())).toList();
                String batch = units.isEmpty() ? "" : " / " + units.get(0).getCrateBatch().getBatchNo();
                return new LabelSet(units, receipt.getNumber() + batch, "/goods-receipts/" + receiptId + "?tab=units",
                        "receipt=" + receiptId + "&crate=" + crateId);
            }
            return new LabelSet(units, receipt.getNumber(), "/goods-receipts/" + receiptId + "?tab=units", "receipt=" + receiptId);
        }
        throw new NotFoundException("StockUnit", null);
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
