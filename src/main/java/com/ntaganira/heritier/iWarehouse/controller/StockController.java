package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.GoodsReceiptService;
import com.ntaganira.heritier.iWarehouse.service.Labels;
import com.ntaganira.heritier.iWarehouse.service.LocationService;
import com.ntaganira.heritier.iWarehouse.service.StockService;
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
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StockController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Inventory screens (INV-01..04, INV-06): stock units with filters and the "smallest piece
 *               that fits" search, a unit's detail with its movements and History, and labels (INV-03)
 *               as a printable page or ZPL. A scanned label code in the search opens the unit.
 *               PAGE_STOCK + PERM_VIEW_STOCK; costs need PERM_VIEW_STOCK_COST, labels PERM_PRINT_LABEL.
 * </pre>
 */
@Controller
@RequestMapping("/stock")
public class StockController {

    static final String MODULE = "Inventory";
    private static final int PAGE_SIZE = 25;

    private final StockService stockService;
    private final GoodsReceiptService receiptService;
    private final LocationService locationService;
    private final ProductRepository productRepo;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;

    public StockController(StockService stockService, GoodsReceiptService receiptService, LocationService locationService,
                           ProductRepository productRepo, DataChangeService dataChangeService,
                           ActivityLogService activityLogService) {
        this.stockService = stockService;
        this.receiptService = receiptService;
        this.locationService = locationService;
        this.productRepo = productRepo;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
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
        // A scanned or typed label code opens the unit.
        if (StringUtils.hasText(search)) {
            Optional<StockUnit> scanned = stockService.findByCode(search);
            if (scanned.isPresent()) {
                return "redirect:/stock/" + scanned.get().getId();
            }
        }
        Integer w = positive(minWidth);
        Integer h = positive(minHeight);
        StockService.UnitFilter filter = new StockService.UnitFilter(search, product, location, status, kind, w, h);
        Page<StockUnit> units = stockService.findPage(filter, Math.max(page, 0), PAGE_SIZE);
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
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "movements") String tab, Model model) {
        StockUnit unit = stockService.findDetailed(id);
        Map<UUID, Location> locations = stockService.locationsById();
        model.addAttribute("unit", unit);
        model.addAttribute("qr", Labels.qrSvg(unit.getCode()));
        model.addAttribute("movements", stockService.movements(id));
        // How the cost was built up (receipt, landed costs): only for those who may see costs.
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        model.addAttribute("costEntries", seeCost ? stockService.costEntries(id) : List.of());
        model.addAttribute("locations", locations);
        model.addAttribute("path", unit.getLocation() == null ? List.of() : locationService.ancestors(unit.getLocation()));
        model.addAttribute("history", dataChangeService.history("StockUnit", id.toString(), 0, 20));
        model.addAttribute("tab", List.of("movements", "cost", "history").contains(tab) && (seeCost || !tab.equals("cost")) ? tab : "movements");
        return "stock/view";
    }

    /** Printable labels (50 x 30 mm) of a posted receipt, one crate, or one unit. */
    @GetMapping("/labels")
    @PreAuthorize("hasAuthority('PAGE_STOCK') and hasAuthority('PERM_PRINT_LABEL')")
    public String labels(@RequestParam(required = false) UUID receipt, @RequestParam(required = false) UUID crate,
                         @RequestParam(required = false) UUID unit, Model model) {
        LabelSet set = labelSet(receipt, crate, unit);
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
                                            @RequestParam(required = false) UUID unit) {
        LabelSet set = labelSet(receipt, crate, unit);
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

    /** The units to label: of a posted receipt, of one crate, or one unit. */
    private LabelSet labelSet(UUID receiptId, UUID crateId, UUID unitId) {
        if (unitId != null) {
            StockUnit unit = stockService.findDetailed(unitId);
            return new LabelSet(List.of(unit), unit.getCode(), "/stock/" + unitId, "unit=" + unitId);
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
