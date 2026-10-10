package com.ntaganira.heritier.iWarehouse.controller.api;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.entity.ApiDevice;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.ApiTokenFilter;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileAuthController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Signing a phone in and out of the mobile POS (NFR-10): the username and password give the phone a token of
 *               its own (PERM_USE_MOBILE_POS needed); signing out revokes it. Logged, failures too.
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class MobileAuthController {

    static final String MODULE = "Mobile POS";

    private final DeviceService deviceService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public MobileAuthController(DeviceService deviceService, ActivityLogService activityLogService, Messages messages) {
        this.deviceService = deviceService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @PostMapping("/login")
    public MobileApi.LoginResponse login(@RequestBody MobileApi.LoginRequest req, HttpServletRequest request) {
        String username = req.username() == null ? "" : req.username().trim();
        try {
            DeviceService.Issued issued = deviceService.signIn(username, req.password(), req.deviceId(), req.deviceName(),
                    request.getHeader("User-Agent"));
            activityLogService.record(issued.user().getUsername(), MODULE, "LOGIN_DEVICE", "Signed in on the phone \""
                    + issued.device().getName() + "\" (" + issued.device().getDeviceKey() + ")", ActivityStatus.SUCCESS);
            return new MobileApi.LoginResponse(issued.token(), issued.device().getId(),
                    new MobileApi.UserView(issued.user().getUsername(), issued.user().getFullName()));
        } catch (BusinessException e) {
            activityLogService.record(username, MODULE, "LOGIN_DEVICE", "Failed to sign in on a phone: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            throw e;
        }
    }

    @PostMapping("/logout")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.UserView logout(HttpServletRequest request) {
        UUID deviceId = (UUID) request.getAttribute(ApiTokenFilter.DEVICE_ATTRIBUTE);
        ApiDevice device = deviceService.signOut(deviceId);
        activityLogService.record(MODULE, "LOGOUT_DEVICE", "Signed out on the phone \"" + device.getName() + "\"", ActivityStatus.SUCCESS);
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        return new MobileApi.UserView(me.getUsername(), me.getFullName());
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.UserView me() {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        return new MobileApi.UserView(me.getUsername(), me.getFullName());
    }
}
