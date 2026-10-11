package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripBreakage;
import com.ntaganira.heritier.iWarehouse.entity.TripLine;
import com.ntaganira.heritier.iWarehouse.entity.TripReturnExtra;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : TripReturnController.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The end of the day on a trip's page (FLT-08..FLT-10, MPOS-07, SRS 5.1). The supervisor scans the units off
 *               the vehicle (RETURN_TRIP), records breakage found on it, decides on the breakage reported on the road
 *               (VALIDATE_BREAKAGE, never their own report), reconciles the trip, records the cash the driver declares; the
 *               trip closes once its stock and cash are settled (CLOSE_TRIP). Every action is logged, refusals too.
 * </pre>
 */
@Controller
@RequestMapping("/trips/{id}")
public class TripReturnController {

    private static final String MODULE = TripController.MODULE;

    private final TripService tripService;
    private final TripReturnService returnService;
    private final BreakageService breakageService;
    private final TripCloseService closeService;
    private final ActivityLogService activityLogService;
    private final NumberFormats num;
    private final Messages messages;

    public TripReturnController(TripService tripService, TripReturnService returnService, BreakageService breakageService,
                                TripCloseService closeService, ActivityLogService activityLogService, NumberFormats num, Messages messages) {
        this.tripService = tripService;
        this.returnService = returnService;
        this.breakageService = breakageService;
        this.closeService = closeService;
        this.activityLogService = activityLogService;
        this.num = num;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- the return scan (FLT-09)

    @PostMapping("/return/scan")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RETURN_TRIP')")
    public String scan(@PathVariable UUID id, @RequestParam(required = false) String codes, RedirectAttributes redirect) {
        try {
            TripReturnService.ScanResult r = returnService.scanBack(id, codes);
            Trip trip = r.trip();
            List<String> back = codesOf(r, TripReturnService.ScanOutcome.BACK);
            List<String> soldBack = codesOf(r, TripReturnService.ScanOutcome.SOLD_BACK);
            List<String> extra = codesOf(r, TripReturnService.ScanOutcome.EXTRA);
            List<String> places = codesOf(r, TripReturnService.ScanOutcome.PLACE);
            if (!back.isEmpty() || !soldBack.isEmpty()) {
                List<String> all = new ArrayList<>(back);
                all.addAll(soldBack);
                activityLogService.record(MODULE, "RETURN_TRIP", "Scanned " + describe(all) + " off " + trip.getVehicle().getPlate()
                        + " for trip " + trip.getNumber(), ActivityStatus.SUCCESS);
            }
            if (!extra.isEmpty() || !soldBack.isEmpty()) {
                // FLT-10: an unexpected unit is kept for the reconciliation's audit case
                List<String> unexpected = new ArrayList<>(soldBack);
                unexpected.addAll(extra);
                activityLogService.record(MODULE, "RETURN_TRIP", "Unexpected on trip " + trip.getNumber() + ": " + String.join(", ", unexpected),
                        ActivityStatus.FAILED);
            }
            List<String> warnings = new ArrayList<>();
            if (!soldBack.isEmpty()) {
                warnings.add(messages.get("trip.return.scan.soldBack", String.join(", ", soldBack)));
            }
            if (!extra.isEmpty()) {
                warnings.add(messages.get("trip.return.scan.extra", String.join(", ", extra), trip.getNumber()));
            }
            if (!places.isEmpty()) {
                warnings.add(messages.get("trip.return.scan.place", String.join(", ", places)));
            }
            if (!warnings.isEmpty()) {
                redirect.addFlashAttribute("flashWarning", String.join(" ", warnings));
            }
            long done = trip.getLines().stream().filter(TripLine::isScannedBack).count();
            if (!back.isEmpty()) {
                redirect.addFlashAttribute("flashSuccess", back.size() == 1
                        ? messages.get("trip.return.scan.back", back.get(0), done, trip.getLines().size())
                        : messages.get("trip.return.scan.backSeveral", back.size(), done, trip.getLines().size()));
            } else if (warnings.isEmpty()) {
                redirect.addFlashAttribute("flashSuccess", messages.get("trip.return.scan.again", r.scans().get(0).code(), done, trip.getLines().size()));
            }
        } catch (BusinessException e) {
            fail(id, "RETURN_TRIP", "Failed to scan units back on trip ", e, redirect);
        }
        return "redirect:/trips/" + id + "?tab=return#return-scan";
    }

    @PostMapping("/return/lines/{lineId}/clear")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RETURN_TRIP')")
    public String clearScan(@PathVariable UUID id, @PathVariable UUID lineId, RedirectAttributes redirect) {
        try {
            TripLine line = returnService.clearScan(id, lineId);
            activityLogService.record(MODULE, "RETURN_TRIP", "Took back the return scan of " + line.getUnitCode() + " on trip "
                    + line.getTrip().getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.return.scan.cleared", line.getUnitCode()));
        } catch (BusinessException e) {
            fail(id, "RETURN_TRIP", "Failed to take back a return scan on trip ", e, redirect);
        }
        return "redirect:/trips/" + id + "?tab=return";
    }

    @PostMapping("/return/extras/{extraId}/remove")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RETURN_TRIP')")
    public String removeExtra(@PathVariable UUID id, @PathVariable UUID extraId, RedirectAttributes redirect) {
        try {
            TripReturnExtra extra = returnService.removeExtra(id, extraId);
            activityLogService.record(MODULE, "RETURN_TRIP", "Took " + extra.getCode() + " off the unexpected labels of trip "
                    + extra.getTrip().getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.return.extra.removed", extra.getCode()));
        } catch (BusinessException e) {
            fail(id, "RETURN_TRIP", "Failed to take an unexpected label off trip ", e, redirect);
        }
        return "redirect:/trips/" + id + "?tab=return";
    }

    /** Reconciles the trip (FLT-09, FLT-10): what became of each unit; differences open an audit case. */
    @PostMapping("/return/reconcile")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RETURN_TRIP')")
    public String reconcile(@PathVariable UUID id, @RequestParam(required = false) String place, RedirectAttributes redirect) {
        try {
            TripReturnService.Reconciled r = returnService.reconcile(id, place);
            Trip trip = r.trip();
            TripReconciliation.Result result = r.result();
            activityLogService.record(MODULE, "RECONCILE_TRIP", "Reconciled trip " + trip.getNumber() + ": " + trip.getReturnedPieces()
                    + " back, " + trip.getSoldPieces() + " sold, " + trip.getBrokenPieces() + " broken, " + trip.getMissingPieces() + " missing"
                    + (r.auditCase() == null ? "" : "; audit case " + r.auditCase().getNumber() + " opened"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.return.reconciled", trip.getNumber(), trip.getReturnedPieces(),
                    trip.getSoldPieces(), trip.getBrokenPieces(), trip.getMissingPieces()));
            if (r.auditCase() != null) {
                redirect.addFlashAttribute("flashWarning", messages.get("trip.return.caseOpened", r.auditCase().getNumber(),
                        result.missing().size(), result.unexpected().size()));
            }
        } catch (BusinessException e) {
            fail(id, "RECONCILE_TRIP", "Failed to reconcile trip ", e, redirect);
        }
        return "redirect:/trips/" + id + "?tab=return";
    }

    // ---------------------------------------------------------------- breakage (FLT-08)

    /** Breakage found on the vehicle at the warehouse, with its photo. */
    @PostMapping("/breakages")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RETURN_TRIP')")
    public String report(@PathVariable UUID id, @RequestParam(required = false) UUID unitId, @RequestParam(required = false) String reason,
                         @RequestParam(required = false) String place, @RequestParam(value = "file", required = false) MultipartFile file,
                         RedirectAttributes redirect) {
        try {
            byte[] photo = file == null || file.isEmpty() ? null : file.getBytes();
            TripBreakage b = breakageService.reportAtWarehouse(id, unitId, reason, place, file == null ? null : file.getOriginalFilename(), photo);
            activityLogService.record(MODULE, "REPORT_BREAKAGE", "Recorded " + b.getUnitCode() + " broken on trip " + b.getTrip().getNumber()
                    + " (" + b.getReason() + ", " + b.getPlace() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("breakage.recorded", b.getUnitCode()));
        } catch (BusinessException e) {
            fail(id, "REPORT_BREAKAGE", "Failed to record a breakage on trip ", e, redirect);
        } catch (IOException e) {
            redirect.addFlashAttribute("flashError", messages.get("attachment.file.required"));
        }
        return "redirect:/trips/" + id + "?tab=return#breakages";
    }

    @PostMapping("/breakages/{breakageId}/accept")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_VALIDATE_BREAKAGE')")
    public String accept(@PathVariable UUID id, @PathVariable UUID breakageId, RedirectAttributes redirect) {
        return decide(id, breakageId, true, null, redirect);
    }

    @PostMapping("/breakages/{breakageId}/reject")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_VALIDATE_BREAKAGE')")
    public String reject(@PathVariable UUID id, @PathVariable UUID breakageId, @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        return decide(id, breakageId, false, reason, redirect);
    }

    private String decide(UUID id, UUID breakageId, boolean accept, String reason, RedirectAttributes redirect) {
        try {
            TripBreakage b = accept ? breakageService.decide(id, breakageId, true, null)
                    : AuditContext.withReason(reason == null ? null : reason.trim(), () -> breakageService.decide(id, breakageId, false, reason));
            activityLogService.record(MODULE, accept ? "APPROVE_BREAKAGE" : "REJECT_BREAKAGE", (accept ? "Accepted" : "Rejected")
                    + " the breakage of " + b.getUnitCode() + " on trip " + b.getTrip().getNumber()
                    + (accept ? "" : ": " + b.getDecisionNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(accept ? "breakage.accepted" : "breakage.rejected", b.getUnitCode()));
        } catch (BusinessException e) {
            fail(id, accept ? "APPROVE_BREAKAGE" : "REJECT_BREAKAGE", "Failed to decide on a breakage of trip ", e, redirect);
        }
        return "redirect:/trips/" + id + "?tab=return#breakages";
    }

    // ---------------------------------------------------------------- cash and closing (MPOS-07, SRS 5.1)

    /** The cash the driver declares, recorded at the warehouse. */
    @PostMapping("/cash")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RETURN_TRIP')")
    public String declare(@PathVariable UUID id, @RequestParam(required = false) BigDecimal amount, RedirectAttributes redirect) {
        try {
            Trip trip = closeService.declareCash(id, amount, TripCloseService.VIA_OFFICE);
            activityLogService.record(MODULE, "DECLARE_CASH", "Recorded " + trip.getCashDeclared().toPlainString() + " RWF declared by "
                    + trip.getDriver().getUsername() + " at the end of trip " + trip.getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.cash.recorded", num.money(trip.getCashDeclared()),
                    trip.getDriver().getUser().getFullName()));
        } catch (BusinessException e) {
            fail(id, "DECLARE_CASH", "Failed to record the cash declared on trip ", e, redirect);
        }
        return "redirect:/trips/" + id + "?tab=return";
    }

    @PostMapping("/close")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_CLOSE_TRIP')")
    public String close(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Trip trip = closeService.close(id);
            activityLogService.record(MODULE, "CLOSE_TRIP", "Closed trip " + trip.getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.closed", trip.getNumber()));
        } catch (BusinessException e) {
            fail(id, "CLOSE_TRIP", "Refused to close trip ", e, redirect);
        }
        return "redirect:/trips/" + id + "?tab=return";
    }

    private void fail(UUID id, String action, String what, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(MODULE, action, what + numberOf(id) + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private static List<String> codesOf(TripReturnService.ScanResult r, TripReturnService.ScanOutcome outcome) {
        return r.of(outcome).stream().map(TripReturnService.Scanned::code).collect(Collectors.toList());
    }

    private static String describe(List<String> codes) {
        if (codes.size() == 1) {
            return codes.get(0);
        }
        String shown = codes.size() <= 10 ? String.join(", ", codes) : String.join(", ", codes.subList(0, 10)) + ", ...";
        return codes.size() + " units (" + shown + ")";
    }

    private String numberOf(UUID id) {
        try {
            return tripService.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }
}
