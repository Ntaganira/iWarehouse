package com.ntaganira.heritier.iWarehouse.controller.api;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripBreakage;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.ApiTokenFilter;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.BreakageService;
import com.ntaganira.heritier.iWarehouse.service.MobileTripService;
import com.ntaganira.heritier.iWarehouse.service.TripCloseService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileEndOfDayController.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The end of the day on the phone. POST /api/v1/breakages reports a unit broken on the road with its photo
 *               (MPOS-06; idempotent on the phone's id, so the offline queue may send it again); GET /api/v1/breakages/{id}
 *               says whether the supervisor accepted it; POST /api/v1/trip/declaration declares the cash the driver holds
 *               (MPOS-07), once.
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class MobileEndOfDayController {

    private final BreakageService breakageService;
    private final TripCloseService closeService;
    private final MobileTripService tripService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public MobileEndOfDayController(BreakageService breakageService, TripCloseService closeService, MobileTripService tripService,
                                    ActivityLogService activityLogService, Messages messages) {
        this.breakageService = breakageService;
        this.closeService = closeService;
        this.tripService = tripService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @PostMapping("/breakages")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.BreakageResult report(@RequestBody MobileApi.BreakageRequest req, HttpServletRequest request) {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        UUID deviceId = (UUID) request.getAttribute(ApiTokenFilter.DEVICE_ATTRIBUTE);
        try {
            BreakageService.Result r = breakageService.reportFromPhone(me, deviceId, new BreakageService.Report(req.clientId(), req.tripId(),
                    req.unitId(), req.reason(), req.place(), req.happenedAt()), req.photoName(), photo(req.photo()));
            TripBreakage b = r.breakage();
            if (r.outcome() == BreakageService.Outcome.RECORDED) {
                activityLogService.record(MobileAuthController.MODULE, "REPORT_BREAKAGE", "Reported " + b.getUnitCode() + " broken on the road ("
                        + b.getReason() + ", " + b.getPlace() + ")", ActivityStatus.SUCCESS);
            }
            return MobileApi.breakage(req.clientId(), r.outcome().name(), b);
        } catch (BusinessException e) {
            activityLogService.record(MobileAuthController.MODULE, "REPORT_BREAKAGE", "Failed to report a breakage: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            throw e;
        }
    }

    @GetMapping("/breakages/{clientId}")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.BreakageResult find(@PathVariable UUID clientId) {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        TripBreakage b = breakageService.findByClientId(clientId).filter(x -> me.getUsername().equals(x.getReportedBy()))
                .orElseThrow(() -> new NotFoundException("TripBreakage", clientId));
        return MobileApi.breakage(clientId, "ALREADY", b);
    }

    @PostMapping("/trip/declaration")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.DeclarationResult declare(@RequestBody MobileApi.DeclarationRequest req) {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        try {
            Trip trip = tripService.openTrip(me.getId(), req.tripId());
            Trip declared = closeService.declareCash(trip.getId(), req.amount(), TripCloseService.VIA_PHONE);
            activityLogService.record(MobileAuthController.MODULE, "DECLARE_CASH", "Declared " + declared.getCashDeclared().toPlainString()
                    + " RWF in cash at the end of trip " + declared.getNumber(), ActivityStatus.SUCCESS);
            return new MobileApi.DeclarationResult(declared.getId(), declared.getNumber(), declared.getCashDeclared(), declared.getCashDeclaredAt());
        } catch (BusinessException e) {
            activityLogService.record(MobileAuthController.MODULE, "DECLARE_CASH", "Failed to declare the cash: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            throw e;
        }
    }

    /** The photo's bytes from its data URL ("data:image/jpeg;base64,..."), or a bare base64 text. */
    private static byte[] photo(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        try {
            return Base64.getDecoder().decode(comma >= 0 ? dataUrl.substring(comma + 1).trim() : dataUrl.trim());
        } catch (IllegalArgumentException e) {
            throw BusinessException.of("breakage.photo.invalid");
        }
    }
}
