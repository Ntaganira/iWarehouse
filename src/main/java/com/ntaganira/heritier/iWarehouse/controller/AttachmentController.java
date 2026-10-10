package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.entity.Attachment;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : AttachmentController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Documents on records (SRS 3.1): keeping one (PERM_ATTACH_DOCUMENT), opening one in the browser, removing
 *               one with a reason (PERM_ATTACH_DOCUMENT). Each also needs the page authority of the record it is on
 *               (AttachmentOwner.page): who cannot open a shipment cannot open its customs papers. Back to the record's
 *               Documents tab.
 * </pre>
 */
@Controller
@RequestMapping("/attachments")
public class AttachmentController {

    static final String MODULE = "Documents";

    private final AttachmentService attachmentService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public AttachmentController(AttachmentService attachmentService, ActivityLogService activityLogService, Messages messages) {
        this.attachmentService = attachmentService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_ATTACH_DOCUMENT')")
    public String add(@RequestParam AttachmentOwner owner, @RequestParam UUID ownerId, @RequestParam(required = false) MultipartFile file,
                      @RequestParam(required = false) String note, RedirectAttributes redirect) {
        requirePage(owner);
        String name = file == null ? null : file.getOriginalFilename();
        try {
            byte[] bytes = file == null ? new byte[0] : file.getBytes();
            Attachment a = attachmentService.add(owner, ownerId, name, bytes, note);
            activityLogService.record(MODULE, "CREATE_DOCUMENT", "Kept " + a.getFileName() + " (" + a.getContentType() + ", " + a.getSizeBytes()
                    + " bytes) on " + owner + " " + ownerId, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("attachment.added", a.getFileName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_DOCUMENT", "Failed to keep " + name + " on " + owner + " " + ownerId + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        } catch (IOException e) {
            redirect.addFlashAttribute("flashError", messages.get("attachment.file.unreadable"));
        }
        return back(owner, ownerId);
    }

    /** The document itself, shown by the browser (a PDF or a photo). */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> open(@PathVariable UUID id) throws IOException {
        Attachment a = attachmentService.find(id);
        requirePage(a.getOwnerType());
        try (InputStream in = attachmentService.open(a)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(a.getFileName(), StandardCharsets.UTF_8).build().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                    .contentType(MediaType.parseMediaType(a.getContentType()))
                    .body(in.readAllBytes());
        }
    }

    @PostMapping("/{id}/remove")
    @PreAuthorize("hasAuthority('PERM_ATTACH_DOCUMENT')")
    public String remove(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        Attachment a = attachmentService.find(id);
        requirePage(a.getOwnerType());
        if (!StringUtils.hasText(reason)) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return back(a.getOwnerType(), a.getOwnerId());
        }
        try {
            AuditContext.withReason(reason.trim(), () -> attachmentService.remove(id, reason));
            activityLogService.record(MODULE, "CANCEL_DOCUMENT", "Removed " + a.getFileName() + " from " + a.getOwnerType() + " "
                    + a.getOwnerId() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("attachment.removedOk", a.getFileName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_DOCUMENT", "Failed to remove " + a.getFileName() + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return back(a.getOwnerType(), a.getOwnerId());
    }

    private static void requirePage(AttachmentOwner owner) {
        if (!AppUserPrincipal.currentHas(owner.page())) {
            throw new AccessDeniedException("No access to " + owner + " documents");
        }
    }

    private static String back(AttachmentOwner owner, UUID ownerId) {
        return "redirect:" + owner.path(ownerId) + "?tab=documents";
    }
}
