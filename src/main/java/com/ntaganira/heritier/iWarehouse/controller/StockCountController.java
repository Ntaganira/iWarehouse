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
import com.ntaganira.heritier.iWarehouse.enums.StockCountType;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.CountRole;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.entity.StockCountParticipant;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.CompanyLogoService;
import com.ntaganira.heritier.iWarehouse.service.SettingService;
import com.ntaganira.heritier.iWarehouse.service.StockVarianceService;
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
    private final StockVarianceService varianceService;
    private final AttachmentService attachmentService;
    private final UserRepository userRepo;
    private final CompanyLogoService logoService;
    private final SettingService settingService;

    public StockCountController(StockCountService countService, StockService stockService, DataChangeService dataChangeService,
                                ActivityLogService activityLogService, Validator validator, Messages messages,
                                StockVarianceService varianceService, AttachmentService attachmentService, UserRepository userRepo,
                                CompanyLogoService logoService, SettingService settingService) {
        this.varianceService = varianceService;
        this.attachmentService = attachmentService;
        this.userRepo = userRepo;
        this.logoService = logoService;
        this.settingService = settingService;
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
            activityLogService.record(MODULE, "CREATE_STOCK_COUNT", "Started " + count.getCountType() + " count " + count.getNumber() + " of "
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
        model.addAttribute("types", StockCountType.values());
        model.addAttribute("blind", settingService.getBoolean(SettingKey.COUNT_BLIND));
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
            open = tab != null && List.of("issues", "lines", "people", "documents", "history").contains(tab) ? tab : "issues";
            List<StockCountLine> lines = countService.lines(id);
            model.addAttribute("units", countService.unitsById(lines.stream().map(StockCountLine::getStockUnitId)
                    .filter(Objects::nonNull).toList()));
            model.addAttribute("issues", Paging.of(lines.stream().filter(l -> l.getOutcome() != CountOutcome.MATCHED).toList(),
                    Paging.pageOf("issues", open, page)));
            model.addAttribute("lines", Paging.of(lines, Paging.pageOf("lines", open, page)));
        } else {
            open = tab != null && List.of("scanned", "todo", "people", "documents", "history").contains(tab) ? tab : "scanned";
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
            List<ScanRow> rows = (count.isOpen() ? count.scansOf(count.currentPass()) : List.copyOf(count.getScans())).stream()
                    .sorted(Comparator.comparing(StockCountScan::getScannedAt).reversed().thenComparing(StockCountScan::getCode))
                    .map(s -> new ScanRow(s, scanned.get(s.getCode()), outcomes.get(s.getCode())))
                    .toList();
            model.addAttribute("scans", Paging.of(rows, Paging.pageOf("scanned", open, page)));
            model.addAttribute("todo", Paging.of(todo, Paging.pageOf("todo", open, page)));
        }
        // M16: blind while open, the passes, the recount's list, who took part and signed, the variance, the signed sheets
        boolean hideExpected = count.isOpen() && count.isBlind();
        model.addAttribute("hideExpected", hideExpected);
        model.addAttribute("differences", count.isOpen() && count.getPhase() == com.ntaganira.heritier.iWarehouse.enums.CountPhase.RECOUNT
                ? countService.differences(count) : List.of());
        List<StockCountParticipant> participants = countService.participants(id);
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        model.addAttribute("participants", participants);
        model.addAttribute("signedCount", participants.stream().filter(StockCountParticipant::isSigned).count());
        model.addAttribute("canSignNow", count.getStatus() == StockCountStatus.CLOSED && participants.stream()
                .anyMatch(x -> me != null && me.equals(x.getUserId()) && !x.isSigned()));
        model.addAttribute("roles", CountRole.values());
        model.addAttribute("users", userRepo.findAll(org.springframework.data.domain.Sort.by("fullName")).stream().filter(User::isEnabled)
                .filter(u -> participants.stream().noneMatch(x -> u.getId().equals(x.getUserId()))).toList());
        model.addAttribute("variance", varianceService.ofCount(id).orElse(null));
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.STOCK_COUNT, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.historyWithChildren("StockCount", id.toString(),
                List.of("StockCountScan", "StockCountParticipant"), "count", Paging.pageOf("history", open, page), Paging.SIZE));
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
            // A scanned rack or slot label changes the place being counted: stay on it
            back = "redirect:/stock-counts/" + id + "?at=" + r.place().getId();
            String hereCode = r.place().getCode();
            if (r.scanned() == 0) {
                redirect.addFlashAttribute("flashSuccess", messages.get("count.scan.place", hereCode));
                return back;
            }
            activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Scanned " + (r.scanned() == 1 ? r.code() : r.scanned() + " labels")
                    + " at " + hereCode + " on " + numberOf(id) + (r.scanned() == 1 ? " (" + r.outcome() + ")" : ""), ActivityStatus.SUCCESS);
            if (r.scanned() > 1) {
                redirect.addFlashAttribute("flashSuccess", messages.get("count.scan.several", r.scanned(), hereCode));
            } else if (r.again()) {
                redirect.addFlashAttribute("flashSuccess", messages.get("count.scan.again", r.code(), hereCode));
            } else if (countService.findDetailed(id).isBlind()) {
                // Blind (CSH-012): whether the label was expected here is not said until the count closes
                redirect.addFlashAttribute("flashSuccess", messages.get("count.scan.blind", r.code(), hereCode));
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
            if (r.step() == StockCountService.Step.SECOND_COUNT) {
                activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Finished the first count of " + c.getNumber()
                        + ": a second, independent count follows", ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashWarning", messages.get("count.secondNeeded", c.getNumber()));
                return "redirect:/stock-counts/" + id;
            }
            if (r.step() == StockCountService.Step.RECOUNT) {
                activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Finished the second count of " + c.getNumber() + ": "
                        + r.differences() + " labels differ, to recount", ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashWarning", messages.get("count.recountNeeded", c.getNumber(), r.differences()));
                return "redirect:/stock-counts/" + id;
            }
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
            } else if (r.variance() != null) {
                redirect.addFlashAttribute("flashWarning", messages.get("count.varianceOpened", r.variance().getNumber(),
                        messages.get("variance.class." + r.variance().getClassification())));
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

    // ---------------------------------------------------------------- participants and signatures (IMP 15.1, PF-06 step 6)

    @PostMapping("/{id}/participants")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String addParticipant(@PathVariable UUID id, @RequestParam(required = false) CountRole role,
                                 @RequestParam(required = false) Long userId, @RequestParam(required = false) String name,
                                 RedirectAttributes redirect) {
        try {
            StockCountParticipant p = countService.addParticipant(id, role, name, userId);
            activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Added " + p.getName() + " (" + p.getRole() + ") to count " + numberOf(id),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.participant.added", p.getName()));
        } catch (BusinessException e) {
            participantFailed(id, "add a participant to", e, redirect);
        }
        return "redirect:/stock-counts/" + id + "?tab=people";
    }

    @PostMapping("/{id}/participants/{participantId}/remove")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String removeParticipant(@PathVariable UUID id, @PathVariable UUID participantId, RedirectAttributes redirect) {
        try {
            StockCountParticipant p = countService.removeParticipant(id, participantId);
            activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Took " + p.getName() + " off count " + numberOf(id), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.participant.removed", p.getName()));
        } catch (BusinessException e) {
            participantFailed(id, "remove a participant from", e, redirect);
        }
        return "redirect:/stock-counts/" + id + "?tab=people";
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_SIGN_STOCK_COUNT')")
    public String sign(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            StockCountParticipant p = countService.sign(id);
            activityLogService.record(MODULE, "APPROVE_STOCK_COUNT", p.getName() + " signed count " + numberOf(id) + " as " + p.getRole(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.signedMsg", numberOf(id)));
        } catch (BusinessException e) {
            participantFailed(id, "sign", e, redirect);
        }
        return "redirect:/stock-counts/" + id + "?tab=people";
    }

    @PostMapping("/{id}/participants/{participantId}/paper")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_COUNT_STOCK')")
    public String signedOnPaper(@PathVariable UUID id, @PathVariable UUID participantId, RedirectAttributes redirect) {
        try {
            StockCountParticipant p = countService.signedOnPaper(id, participantId);
            activityLogService.record(MODULE, "APPROVE_STOCK_COUNT", "Recorded the paper signature of " + p.getName() + " on count " + numberOf(id),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("count.paperSignedMsg", p.getName()));
        } catch (BusinessException e) {
            participantFailed(id, "record a paper signature on", e, redirect);
        }
        return "redirect:/stock-counts/" + id + "?tab=people";
    }

    private void participantFailed(UUID id, String what, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(MODULE, "UPDATE_STOCK_COUNT", "Failed to " + what + " count " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    /** The count sheet (IMP 15.2, PF-06): its places with blank rows to write labels on; the book quantity unless blind. */
    @GetMapping("/{id}/sheet")
    @PreAuthorize("hasAuthority('PAGE_STOCK_COUNTS') and hasAuthority('PERM_VIEW_STOCK_COUNT')")
    public String sheet(@PathVariable UUID id, Model model) {
        StockCount count = countService.findDetailed(id);
        Map<UUID, Location> byId = stockService.locationsById();
        List<Location> places = countService.scanPlaces(count, byId);
        Map<UUID, Long> book = new HashMap<>();
        if (!count.isBlind()) {
            countService.expectedUnits(count).forEach(u -> book.merge(u.getLocation().getId(), 1L, Long::sum));
        }
        model.addAttribute("count", count);
        model.addAttribute("places", places);
        model.addAttribute("book", book);
        model.addAttribute("participants", countService.participants(id));
        model.addAttribute("companyLogoUrl", logoService.url());
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        return "stock-counts/sheet";
    }

    private String numberOf(UUID id) {
        try {
            return countService.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }
}
