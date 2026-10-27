package com.ntaganira.heritier.iWarehouse.controller.api;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripDeposit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.TripCountKind;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.FloatService;
import com.ntaganira.heritier.iWarehouse.service.MobileTripService;
import com.ntaganira.heritier.iWarehouse.service.TripCloseService;
import com.ntaganira.heritier.iWarehouse.service.TripCountService;
import com.ntaganira.heritier.iWarehouse.service.TripDepositService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileRoadController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The driver's phone on the road (M15): collections banked or sent by mobile money during the trip
 *               (BP 7.3 rule 4: POST /api/v1/deposits) and the daily count of what is on board (SDP 6: POST /api/v1/counts).
 *               Both carry the phone's own id: sent twice, taken once. PERM_USE_MOBILE_POS, the trip the driver's own.
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class MobileRoadController {

    private final TripDepositService depositService;
    private final TripCountService countService;
    private final FloatService floatService;
    private final MobileTripService tripService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public MobileRoadController(TripDepositService depositService, TripCountService countService, FloatService floatService,
                                MobileTripService tripService, ActivityLogService activityLogService, Messages messages) {
        this.depositService = depositService;
        this.countService = countService;
        this.floatService = floatService;
        this.tripService = tripService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @PostMapping("/deposits")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.DepositResult deposit(@RequestBody MobileApi.DepositRequest req) {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        try {
            Trip trip = tripService.openTrip(me.getId(), req.tripId());
            boolean before = depositService.byClientId(req.clientId()).isPresent();
            PaymentMethod method = "MOBILE_MONEY".equals(req.method()) ? PaymentMethod.MOBILE_MONEY : PaymentMethod.BANK_TRANSFER;
            TripDeposit d = depositService.record(trip.getId(), new TripDepositService.Form(method, req.amount(), req.reference(), req.depositedOn()),
                    req.clientId(), TripCloseService.VIA_PHONE);
            if (!before) {
                activityLogService.record(MobileAuthController.MODULE, "CREATE_TRIP_DEPOSIT", "Banked " + d.getAmount().toPlainString() + " RWF of trip "
                        + trip.getNumber() + " by " + d.getMethod() + " (" + d.getReference() + "), " + d.getNumber(), ActivityStatus.SUCCESS);
            }
            return new MobileApi.DepositResult(req.clientId(), "RECORDED", d.getNumber(), d.getAmount(), floatService.expectedOf(trip.getId()));
        } catch (BusinessException e) {
            activityLogService.record(MobileAuthController.MODULE, "CREATE_TRIP_DEPOSIT", "Failed to record a deposit: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            throw e;
        }
    }

    @PostMapping("/counts")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.CountResult count(@RequestBody MobileApi.CountRequest req) {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        try {
            Trip trip = tripService.openTrip(me.getId(), req.tripId());
            boolean before = countService.byClientId(req.clientId()).isPresent();
            TripCountService.Counted c = countService.count(trip.getId(), TripCountKind.DAILY, req.codes(), req.note(), req.clientId());
            if (!before) {
                activityLogService.record(MobileAuthController.MODULE, "CREATE_TRIP_COUNT", "Counted trip " + trip.getNumber() + " on the road: "
                        + c.count().getFound() + " of " + c.count().getExpected() + " found, " + c.count().getMissing() + " missing, "
                        + c.count().getExtra() + " not expected", c.result().isClean() ? ActivityStatus.SUCCESS : ActivityStatus.FAILED);
            }
            return new MobileApi.CountResult(req.clientId(), c.count().getExpected(), c.count().getFound(), c.result().missing(), c.result().extra());
        } catch (BusinessException e) {
            activityLogService.record(MobileAuthController.MODULE, "CREATE_TRIP_COUNT", "Failed to record a count: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            throw e;
        }
    }
}
