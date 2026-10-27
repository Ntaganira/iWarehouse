package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripDeposit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.TripCountKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : TripFieldController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The field cycle on a trip (M15): the load request approved (PERM_APPROVE_LOAD_REQUEST) and the previous
 *               trip waived by the COO (PERM_WAIVE_PREVIOUS_TRIP), collections banked during the trip recorded at the
 *               office (PERM_RECORD_TRIP_DEPOSIT), an unannounced count of the van (PERM_COUNT_VAN), the clearance
 *               (PERM_CLEAR_TRIP) and its countersignature (PERM_COUNTERSIGN_TRIP_CLEARANCE), and the trip's papers: the
 *               stock issue note, the daily return and the clearance certificate (PAGE_FLEET, the trips one may see).
 * </pre>
 */
@Controller
@RequestMapping("/trips/{id}")
public class TripFieldController {

    static final String MODULE = "Fleet";

    private final TripService tripService;
    private final TripDepositService depositService;
    private final TripCountService countService;
    private final TripCloseService closeService;
    private final TripPapersService papersService;
    private final FloatService floatService;
    private final SettingService settingService;
    private final CompanyLogoService companyLogoService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public TripFieldController(TripService tripService, TripDepositService depositService, TripCountService countService,
                               TripCloseService closeService, TripPapersService papersService, FloatService floatService,
                               SettingService settingService, CompanyLogoService companyLogoService, ActivityLogService activityLogService,
                               Messages messages) {
        this.tripService = tripService;
        this.depositService = depositService;
        this.countService = countService;
        this.closeService = closeService;
        this.papersService = papersService;
        this.floatService = floatService;
        this.settingService = settingService;
        this.companyLogoService = companyLogoService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- the load request (N.01, N.04)

    @PostMapping("/approve-load")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_APPROVE_LOAD_REQUEST')")
    public String approveLoad(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Trip t = tripService.approveLoad(id);
            activityLogService.record(MODULE, "APPROVE_LOAD_REQUEST", "Approved the load request of trip " + t.getNumber() + ": " + t.getRoute()
                    + ", " + t.getLines().size() + " unit(s) worth " + t.getLoadValue().toPlainString() + " RWF at cost, settled by " + t.getSettleBy(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.load.approvedMsg", t.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, id, "APPROVE_LOAD_REQUEST", "approve the load request of", e);
        }
        return "redirect:/trips/" + id;
    }

    @PostMapping("/waive-previous")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_WAIVE_PREVIOUS_TRIP')")
    public String waivePrevious(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            Trip t = reason == null || reason.isBlank() ? tripService.waivePrevious(id, reason)
                    : AuditContext.withReason(reason.trim(), () -> tripService.waivePrevious(id, reason));
            activityLogService.record(MODULE, "APPROVE_PREVIOUS_WAIVER", "Let trip " + t.getNumber() + " leave with the previous trip not cleared: "
                    + t.getPreviousOverrideReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashWarning", messages.get("trip.previous.waivedMsg", t.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, id, "APPROVE_PREVIOUS_WAIVER", "waive the previous trip of", e);
        }
        return "redirect:/trips/" + id;
    }

    // ---------------------------------------------------------------- collections banked during the trip (N.07)

    @PostMapping("/deposits")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RECORD_TRIP_DEPOSIT')")
    public String deposit(@PathVariable UUID id, @RequestParam(required = false) PaymentMethod method, @RequestParam(required = false) BigDecimal amount,
                          @RequestParam(required = false) String reference,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate depositedOn,
                          RedirectAttributes redirect) {
        try {
            TripDeposit d = depositService.record(id, new TripDepositService.Form(method, amount, reference, depositedOn), null,
                    TripCloseService.VIA_OFFICE);
            activityLogService.record(MODULE, "CREATE_TRIP_DEPOSIT", "Recorded " + d.getNumber() + ": " + d.getAmount().toPlainString() + " RWF banked by "
                    + d.getMethod() + " (" + d.getReference() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("tripDeposit.recorded", d.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, id, "CREATE_TRIP_DEPOSIT", "record a deposit on", e);
        }
        return "redirect:/trips/" + id + "?tab=deposits";
    }

    // ---------------------------------------------------------------- an unannounced count of the van (L.02)

    @GetMapping("/count")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_COUNT_VAN')")
    public String countForm(@PathVariable UUID id, Model model) {
        Trip trip = seen(id);
        model.addAttribute("trip", trip);
        model.addAttribute("counts", countService.ofTrip(id));
        return "trips/count";
    }

    @PostMapping("/count")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_COUNT_VAN')")
    public String count(@PathVariable UUID id, @RequestParam(required = false) String codes, @RequestParam(required = false) String note,
                        RedirectAttributes redirect) {
        try {
            TripCountService.Counted c = countService.count(id, TripCountKind.SPOT, VanCounts.codes(codes), note, null);
            Trip trip = tripService.findDetailed(id);
            activityLogService.record(MODULE, "CREATE_TRIP_COUNT", "Counted the van of trip " + trip.getNumber() + " unannounced: "
                    + c.count().getFound() + " of " + c.count().getExpected() + " found, " + c.count().getMissing() + " missing"
                    + (c.result().missing().isEmpty() ? "" : " (" + String.join(", ", c.result().missing()) + ")") + ", " + c.count().getExtra()
                    + " not expected", c.result().isClean() ? ActivityStatus.SUCCESS : ActivityStatus.FAILED);
            redirect.addFlashAttribute(c.result().isClean() ? "flashSuccess" : "flashWarning", c.result().isClean()
                    ? messages.get("tripCount.clean", c.count().getFound()) : messages.get("tripCount.differences", c.count().getMissing(), c.count().getExtra()));
            return "redirect:/trips/" + id + "?tab=counts";
        } catch (BusinessException e) {
            fail(redirect, id, "CREATE_TRIP_COUNT", "count the van of", e);
            redirect.addFlashAttribute("codesBack", codes);
            return "redirect:/trips/" + id + "/count";
        }
    }

    // ---------------------------------------------------------------- the clearance (N.15)

    @PostMapping("/clear")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_CLEAR_TRIP')")
    public String clear(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Trip t = closeService.clear(id);
            activityLogService.record(MODULE, "APPROVE_TRIP_CLEARANCE", "Cleared trip " + t.getNumber() + ": its stock, cash and float settled",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.clearance.clearedMsg", t.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, id, "APPROVE_TRIP_CLEARANCE", "clear", e);
        }
        return "redirect:/trips/" + id;
    }

    @PostMapping("/countersign")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_COUNTERSIGN_TRIP_CLEARANCE')")
    public String countersign(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Trip t = closeService.countersign(id);
            activityLogService.record(MODULE, "CLOSE_TRIP", "Countersigned the clearance of trip " + t.getNumber() + " (cleared by " + t.getClearedBy()
                    + "): closed", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.clearance.countersignedMsg", t.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, id, "CLOSE_TRIP", "countersign the clearance of", e);
        }
        return "redirect:/trips/" + id;
    }

    // ---------------------------------------------------------------- the trip's papers (N.20)

    @GetMapping("/issue-note")
    @PreAuthorize("hasAuthority('PAGE_FLEET')")
    public String issueNote(@PathVariable UUID id, Model model) {
        Trip trip = seen(id);
        model.addAttribute("note", papersService.issueNote(trip));
        company(model);
        activityLogService.record(MODULE, "PRINT_ISSUE_NOTE", "Opened the stock issue note of trip " + trip.getNumber() + " for printing", ActivityStatus.SUCCESS);
        return "trips/issue-note";
    }

    @GetMapping("/daily-return")
    @PreAuthorize("hasAuthority('PAGE_FLEET')")
    public String dailyReturn(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                              Model model, RedirectAttributes redirect) {
        Trip trip = seen(id);
        List<LocalDate> days = papersService.days(trip);
        if (days.isEmpty()) {
            redirect.addFlashAttribute("flashError", messages.get("trip.papers.notLeft", trip.getNumber()));
            return "redirect:/trips/" + id;
        }
        LocalDate chosen = day != null && days.contains(day) ? day : days.get(days.size() - 1);
        model.addAttribute("ret", papersService.dailyReturn(trip, chosen));
        company(model);
        activityLogService.record(MODULE, "PRINT_DAILY_RETURN", "Opened the daily return of trip " + trip.getNumber() + " for " + chosen, ActivityStatus.SUCCESS);
        return "trips/daily-return";
    }

    @GetMapping("/clearance")
    @PreAuthorize("hasAuthority('PAGE_FLEET')")
    public String clearance(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        Trip trip = seen(id);
        if (!trip.isCleared() && trip.getClosedAt() == null) {
            redirect.addFlashAttribute("flashError", messages.get("trip.clearance.notCleared", trip.getNumber()));
            return "redirect:/trips/" + id;
        }
        model.addAttribute("trip", trip);
        model.addAttribute("settlement", closeService.settlement(trip));
        model.addAttribute("deposits", depositService.ofTrip(id));
        model.addAttribute("banked", floatService.bankedOf(List.of(id)).getOrDefault(id, BigDecimal.ZERO));
        company(model);
        activityLogService.record(MODULE, "PRINT_CLEARANCE", "Opened the clearance certificate of trip " + trip.getNumber() + " for printing",
                ActivityStatus.SUCCESS);
        return "trips/clearance";
    }

    // ---------------------------------------------------------------- helpers

    private Trip seen(UUID id) {
        Trip trip = tripService.findDetailed(id);
        if (!TripService.canSee(trip)) {
            throw new AccessDeniedException("trip " + id);
        }
        return trip;
    }

    private void company(Model model) {
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        model.addAttribute("companyLogoUrl", companyLogoService.url());
        model.addAttribute("companyTin", settingService.get(SettingKey.COMPANY_TIN));
        model.addAttribute("companyAddress", settingService.get(SettingKey.COMPANY_ADDRESS));
        model.addAttribute("companyPhone", settingService.get(SettingKey.COMPANY_PHONE));
    }

    private void fail(RedirectAttributes redirect, UUID id, String action, String what, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(MODULE, action, "Failed to " + what + " trip " + id + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }
}
