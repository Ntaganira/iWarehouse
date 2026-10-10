package com.ntaganira.heritier.iWarehouse.controller.api;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.ApiTokenFilter;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.EbmService;
import com.ntaganira.heritier.iWarehouse.service.MobileSaleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller.api
 * - File      : MobileSaleController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Syncing the phone's sales (SYNC-02..06): a sale sent with its UUID is taken, found again when sent twice, or
 *               kept as a conflict for the supervisor; the answer is 200 in each case, so the phone moves to its next
 *               pending sale. Each sync is logged with the phone, the driver, the phone's time and the server's (AUD-07).
 *               GET by UUID gives a sale's state and, once signed, its EBM signature for the receipt (SYNC-06).
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/sales")
public class MobileSaleController {

    private final MobileSaleService saleService;
    private final EbmService ebmService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public MobileSaleController(MobileSaleService saleService, EbmService ebmService, ActivityLogService activityLogService, Messages messages) {
        this.saleService = saleService;
        this.ebmService = ebmService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.SaleResult submit(@RequestBody MobileSaleService.SaleRequest req, HttpServletRequest request) {
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        UUID deviceId = (UUID) request.getAttribute(ApiTokenFilter.DEVICE_ATTRIBUTE);
        MobileSaleService.Result r = saleService.submit(me, deviceId, req);
        String made = req.createdAt() == null ? "" : ", made on the phone at " + req.createdAt();
        String phone = AuditContext.current().getDeviceId();
        switch (r.outcome()) {
            case ACCEPTED -> activityLogService.record(MobileAuthController.MODULE, "SYNC_MOBILE_SALE", "Took sale " + r.invoice().getNumber()
                    + " from the phone " + phone + " (" + r.invoice().getLines().size() + " unit(s), " + r.invoice().getTotalAmount().toPlainString()
                    + " RWF, trip " + r.invoice().getTrip().getNumber() + made + ")", ActivityStatus.SUCCESS);
            case CONFLICT -> activityLogService.record(MobileAuthController.MODULE, "SYNC_MOBILE_SALE", "Kept sale "
                    + r.conflict().getNumber() + " from the phone " + phone + " for the supervisor: " + r.conflict().getReason() + ", "
                    + r.conflict().getDetail() + made, ActivityStatus.FAILED);
            default -> activityLogService.record(MobileAuthController.MODULE, "SYNC_MOBILE_SALE", "Sale " + req.clientId()
                    + " sent again by the phone " + phone + ": " + r.outcome(), ActivityStatus.SUCCESS);
        }
        return MobileApi.result(req.clientId(), r, ebmService, messages);
    }

    @GetMapping("/{clientId}")
    @PreAuthorize("hasAuthority('PERM_USE_MOBILE_POS')")
    public MobileApi.SaleResult find(@PathVariable UUID clientId) {
        MobileSaleService.Result r = saleService.find(clientId);
        if (r == null) {
            throw new NotFoundException("MobileSale", clientId);
        }
        // A driver reads their own sales only
        AppUserPrincipal me = AppUserPrincipal.current().orElseThrow();
        String owner = r.isAccepted() ? r.invoice().getPostedBy() : r.conflict().getUsername();
        if (!me.getUsername().equals(owner)) {
            throw new NotFoundException("MobileSale", clientId);
        }
        return MobileApi.result(clientId, r, ebmService, messages);
    }
}
