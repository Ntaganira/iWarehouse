package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.StockCountDto;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.StockCount;
import com.ntaganira.heritier.iWarehouse.entity.StockCountLine;
import com.ntaganira.heritier.iWarehouse.entity.StockCountScan;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.CountOutcome;
import com.ntaganira.heritier.iWarehouse.enums.StockCountStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.StockCountService;
import com.ntaganira.heritier.iWarehouse.service.StockCounting;
import com.ntaganira.heritier.iWarehouse.service.StockService;
import jakarta.validation.Validator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StockCountController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Stock counts by scanning (INV-08): list, start a count of a place (and optionally one
 *               glass), scan labels where they are found (one per scan or several pasted), remove a scan,
 *               close (the result is recorded; missing and lost units go on an adjustment) or cancel with a
 *               reason. PAGE_STOCK_COUNTS + PERM_VIEW_STOCK_COUNT; changes need PERM_COUNT_STOCK.
 * </pre>
 */
@Controller
@RequestMapping("/stock-counts")
public class StockCountController {

    static final String MODULE = "Stock Counts";
    private static final int REASON_MAX = 255;

    private final StockCountService countService;
    private final StockService stockService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;

    public StockCountController(StockCountService countService, StockService stockService, DataChangeService dataChangeService,
                                ActivityLogService activityLogService, Validator validator, Messages messages) {
        this.countService = countService;
        this.stockService = stockService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
    }

    /** A scan on an open or cancelled count: the unit it names and, while open, what it shows so far. */
    public record ScanRow(StockCountScan scan, StockUnit unit, CountOutcome outcome) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_VIEW_STOCK_COUNT')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("counts", countService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", StockCountStatus.values());
        model.addAttribute("open", countService.openCount());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "stock-counts/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String createForm(@RequestParam(required = false) UUID location, Model model) {
        StockCountDto dto = new StockCountDto();
        dto.setLocationId(location);
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String create(@ModelAttribute("countDto") StockCountDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        validator.validate(dto, result);
        if (result.hasErrors()) {
            return form(model, dto);
        }
        try {
            StockCount count = countService.start(dto);
            activityLogService.record(MODULE, "CREATE_STOCK_COUNT", "Started count " + count.getNumber() + " of "
                    + count.getLocation().getCode() + (count.getProduct() == null ? "" : " (" + count.getProduct().getCode() + ")")
                    + ", " + count.getPlaces().size() + " place(s) held", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.startedMsg", count.getNumber(), count.getLocation().getCode()));
            return "redirect:/stock-counts/" + count.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_STOCK_COUNT", "Failed to start a count: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto);
        }
    }

    private String form(Model model, StockCountDto dto) {
        model.addAttribute("countDto", dto);
        model.addAttribute("places", countService.countablePlaces(stockService.locationsById()));
        model.addAttribute("products", countService.products());
        return "stock-counts/form";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_VIEW_STOCK_COUNT')")
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab,
                       @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) UUID at, Model model) {
        StockCount count = countService.findDetailed(id);
        Map<UUID, Location> byId = stockService.locationsById();
        model.addAttribute("count", count);
        model.addAttribute("locations", byId);
        String open;
        if (count.getStatus() == StockCountStatus.CLOSED) {
            open = tab != null && List.of("issues", "lines", "history").contains(tab) ? tab : "issues";
            List<StockCountLine> lines = countService.lines(id);
            model.addAttribute("units", countService.unitsById(lines.stream().map(StockCountLine::getStockUnitId)
                    .filter(Objects::nonNull).toList()));
            model.addAttribute("issues", Paging.of(lines.stream().filter(l -> l.getOutcome() != CountOutcome.MATCHED).toList(),
                    Paging.pageOf("issues", open, page)));
            model.addAttribute("lines", Paging.of(lines, Paging.pageOf("lines", open, page)));
        } else {
            open = tab != null && List.of("scanned", "todo", "history").contains(tab) ? tab : "scanned";
            Map<String, StockUnit> scanned = countService.scannedUnits(count);
            List<StockUnit> expected = count.isOpen() ? countService.expectedUnits(count) : List.of();
            Map<String, CountOutcome> outcomes = new HashMap<>();
            List<StockUnit> todo = List.of();
            if (count.isOpen()) {
                List<StockCounting.Line> lines = countService.compare(count, expected, scanned);
                lines.stream().filter(l -> l.foundAt() != null).forEach(l -> outcomes.put(l.code(), l.outcome()));
                List<String> missing = lines.stream().filter(l -> l.outcome() == CountOutcome.MISSING).map(StockCounting.Line::code).toList();
                todo = expected.stream().filter(u -> missing.contains(u.getCode())).toList();
                long issues = outcomes.values().stream().filter(o -> o != CountOutcome.MATCHED).count();
                model.addAttribute("expectedCount", expected.size());
                model.addAttribute("issuesSoFar", issues);
                List<Location> scanPlaces = countService.scanPlaces(count, byId);
                model.addAttribute("scanPlaces", scanPlaces);
                // The place being counted stays chosen between scans; a single rack or slot needs no choice
                UUID chosen = at != null && scanPlaces.stream().anyMatch(l -> l.getId().equals(at)) ? at
                        : scanPlaces.size() == 1 ? scanPlaces.get(0).getId() : null;
                model.addAttribute("at", chosen);
            }
            List<ScanRow> rows = count.getScans().stream()
                    .sorted(Comparator.comparing(StockCountScan::getScannedAt).reversed().thenComparing(StockCountScan::getCode))
                    .map(s -> new ScanRow(s, scanned.get(s.getCode()), outcomes.get(s.getCode())))
                    .toList();
            model.addAttribute("scans", Paging.of(rows, Paging.pageOf("scanned", open, page)));
            model.addAttribute("todo", Paging.of(todo, Paging.pageOf("todo", open, page)));
        }
        model.addAttribute("history", dataChangeService.historyWithChildren("StockCount", id.toString(),
                List.of("StockCountScan"), "count", Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "stock-counts/view";
    }

    @PostMapping("/{id}/scan")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String scan(@PathVariable UUID id, @RequestParam(required = false) UUID locationId,
                       @RequestParam(required = false) String codes, RedirectAttributes redirect) {
        String back = "redirect:/stock-counts/" + id + (locationId == null ? "" : "?at=" + locationId);
        try {
            StockCountService.ScanResult r = countService.scan(id, locationId, codes);
            Location here = stockService.locationsById().get(locationId);
            String hereCode = here == null ? "" : here.getCode();
            activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Scanned " + (r.scanned() == 1 ? r.code() : r.scanned() + " labels")
                    + " at " + hereCode + " on " + numberOf(id) + (r.scanned() == 1 ? " (" + r.outcome() + ")" : ""), ActivityStatus.SUCCESS);
            if (r.scanned() > 1) {
                redirect.addFlashAttribute("flashSuccess", messages.get("count.scan.several", r.scanned(), hereCode));
            } else if (r.again()) {
                redirect.addFlashAttribute("flashSuccess", messages.get("count.scan.again", r.code(), hereCode));
            } else {
                boolean fine = r.outcome() == CountOutcome.MATCHED || r.outcome() == CountOutcome.MISPLACED;
                redirect.addFlashAttribute(fine ? "flashSuccess" : "flashWarning", scanMessage(r, hereCode));
            }
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Failed to scan on " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return back;
    }

    /** "U-WH-000041 is recorded on WH-A-R02: counted here, on WH-A-R01; it moves when the count closes". */
    private String scanMessage(StockCountService.ScanResult r, String here) {
        StockUnit u = r.unit();
        return switch (r.outcome()) {
            case MATCHED -> messages.get("count.scan.MATCHED", r.code(), here);
            case MISPLACED -> messages.get("count.scan.MISPLACED", r.code(), u.getLocation() == null ? "—" : u.getLocation().getCode(), here);
            case FOUND_LOST -> messages.get("count.scan.FOUND_LOST", r.code());
            case ELSEWHERE, NOT_IN_STOCK -> messages.get("count.scan.GONE", r.code(), messages.get("stock.status." + u.getStatus()));
            default -> messages.get("count.scan.UNKNOWN", r.code());
        };
    }

    @PostMapping("/{id}/scans/{scanId}/remove")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String removeScan(@PathVariable UUID id, @PathVariable UUID scanId, @RequestParam(required = false) UUID at,
                             RedirectAttributes redirect) {
        try {
            StockCountScan scan = countService.removeScan(id, scanId);
            activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Removed the scan of " + scan.getCode() + " from " + numberOf(id),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.scan.removed", scan.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Failed to remove a scan from " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock-counts/" + id + (at == null ? "" : "?at=" + at);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String close(@PathVariable UUID id, RedirectAttributes redirect) {
        String number = numberOf(id);
        try {
            String reason = messages.get("count.adjustmentReason", number);
            StockCountService.CloseResult r = AuditContext.withReason(reason, () -> countService.close(id, reason));
            StockCount c = r.count();
            activityLogService.record(MODULE, "CLOSE_STOCK_COUNT", "Closed count " + c.getNumber() + ": " + c.getExpectedUnits()
                    + " expected, " + c.getCountedUnits() + " scanned, " + c.getMatchedUnits() + " matched, " + c.getMissingUnits()
                    + " missing, " + c.getMisplacedUnits() + " misplaced, " + c.getExtraUnits() + " extra"
                    + (c.getAdjustmentNumber() == null ? "" : "; adjustment " + c.getAdjustmentNumber())
                    + (r.racksOverLimit().isEmpty() ? "" : "; racks over their limit: " + String.join(", ", r.racksOverLimit())),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.closed", c.getNumber(), c.getMissingUnits(),
                    c.getMisplacedUnits(), c.getExtraUnits()));
            if (!r.racksOverLimit().isEmpty()) {
                redirect.addFlashAttribute("flashWarning", messages.get("count.racksOverLimit", String.join(", ", r.racksOverLimit())));
            }
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CLOSE_STOCK_COUNT", "Failed to close count " + number + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock-counts/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/stock-counts/" + id;
        }
        try {
            StockCount count = AuditContext.withReason(reason.trim(), () -> countService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_STOCK_COUNT", "Cancelled count " + count.getNumber() + ": " + reason.trim(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.cancelledMsg", count.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_STOCK_COUNT", "Failed to cancel count " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock-counts/" + id;
    }

    private String numberOf(UUID id) {
        try {
            return countService.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }
}
