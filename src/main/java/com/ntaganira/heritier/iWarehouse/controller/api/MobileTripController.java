package com.ntaganira.heritier.iWarehouse.controller.api;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.ApiTokenFilter;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.MobileTripService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileTripController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The trip download (SYNC-01): the driver's trip on the road with the units on the vehicle, the customers, the
 *               trip's frozen prices and the phone's invoice numbers. Downloaded again whenever the phone is online, it
 *               tops the numbers up; the prices stay as first taken.
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/trip")
public class MobileTripController {

    private final MobileTripService tripService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public MobileTripController(MobileTripService tripService, ActivityLogService activityLogService, Messages messages) {
        this.tripService = tripService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.TripBundle download(HttpServletRequest request) {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        UUID deviceId = (UUID) request.getAttribute(ApiTokenFilter.DEVICE_ATTRIBUTE);
        try {
            MobileTripService.Bundle bundle = tripService.download(me, deviceId);
            activityLogService.record(MobileAuthController.MODULE, "DOWNLOAD_TRIP", "Downloaded trip " + bundle.trip().getNumber()
                    + ": " + bundle.units().size() + " unit(s) on " + bundle.trip().getVehicle().getPlate() + ", "
                    + bundle.numbers().size() + " invoice number(s) on the phone", ActivityStatus.SUCCESS);
            return MobileApi.bundle(bundle, messages);
        } catch (BusinessException e) {
            activityLogService.record(MobileAuthController.MODULE, "DOWNLOAD_TRIP", "Failed to download the trip: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            throw e;
        }
    }
}
