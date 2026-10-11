package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.dto.PasswordChangeDto;
import com.ntaganira.heritier.iWarehouse.dto.ProfileDto;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.service.PayRunService;
import com.ntaganira.heritier.iWarehouse.service.PdfService;
import com.ntaganira.heritier.iWarehouse.service.ProfileService;
import com.ntaganira.heritier.iWarehouse.entity.PayRun;
import com.ntaganira.heritier.iWarehouse.entity.PayRunLine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ProfileController.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : My profile (/profile, the header's menu): every signed-in user keeps their own details, photo and
 *               password, like signing out it needs no page or permission. Always the caller's own account. The session's
 *               user is refreshed after a change, so the header shows the new name and photo at once. An employee linked to
 *               their user finds their payslips here (posted and paid runs only, never another employee's).
 * </pre>
 */
@Controller
@RequestMapping("/profile")
public class ProfileController {

    static final String MODULE = "User Management";

    private final ProfileService profileService;
    private final PayRunService payRunService;
    private final PdfService pdfService;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public ProfileController(ProfileService profileService, PayRunService payRunService, PdfService pdfService, ReportFiles reportFiles,
                             ActivityLogService activityLogService, Messages messages) {
        this.profileService = profileService;
        this.payRunService = payRunService;
        this.pdfService = pdfService;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public String view(@RequestParam(defaultValue = "details") String tab, Model model) {
        User me = profileService.me(currentId());
        if (!model.containsAttribute("profileDto")) {
            model.addAttribute("profileDto", ProfileDto.of(me));
        }
        if (!model.containsAttribute("passwordDto")) {
            model.addAttribute("passwordDto", new PasswordChangeDto());
        }
        return page(model, me, "password".equals(tab) ? "password" : "details");
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public String update(@Valid @ModelAttribute("profileDto") ProfileDto dto, BindingResult result, Model model,
                         HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        User me = profileService.me(currentId());
        if (result.hasErrors()) {
            return invalid(model, me, "details", result, new PasswordChangeDto(), "passwordDto");
        }
        try {
            User user = profileService.updateDetails(me.getId(), dto);
            refreshSession(user, request, response);
            activityLogService.record(MODULE, "UPDATE_PROFILE", "Updated own profile (" + user.getUsername() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("profile.updated"));
            return "redirect:/profile";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_PROFILE", "Failed to update own profile (" + me.getUsername() + "): "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, me, "details", result, e, new PasswordChangeDto(), "passwordDto");
        }
    }

    @PostMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public String changePassword(@Valid @ModelAttribute("passwordDto") PasswordChangeDto dto, BindingResult result, Model model,
                                 RedirectAttributes redirect) {
        User me = profileService.me(currentId());
        if (result.hasErrors()) {
            return invalid(model, me, "password", result, ProfileDto.of(me), "profileDto");
        }
        try {
            profileService.changePassword(me.getId(), dto);
            activityLogService.record(MODULE, "CHANGE_PASSWORD", "Changed own password (" + me.getUsername() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("profile.password.changed"));
            return "redirect:/profile?tab=password";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CHANGE_PASSWORD", "Failed to change own password (" + me.getUsername() + "): "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, me, "password", result, e, ProfileDto.of(me), "profileDto");
        }
    }

    @PostMapping("/photo")
    @PreAuthorize("isAuthenticated()")
    public String setPhoto(@RequestParam(required = false) MultipartFile photo, HttpServletRequest request,
                           HttpServletResponse response, RedirectAttributes redirect) {
        Long id = currentId();
        try {
            byte[] bytes = photo == null ? new byte[0] : photo.getBytes();
            User user = profileService.setPhoto(id, bytes);
            refreshSession(user, request, response);
            activityLogService.record(MODULE, "UPDATE_PROFILE_PHOTO", "Changed own photo (" + user.getUsername() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("profile.photo.saved"));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_PROFILE_PHOTO", "Failed to change own photo (user #" + id + "): " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        } catch (IOException e) {
            redirect.addFlashAttribute("flashError", messages.get("profile.photo.unreadable"));
        }
        return "redirect:/profile";
    }

    @PostMapping("/photo/remove")
    @PreAuthorize("isAuthenticated()")
    public String removePhoto(HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        User user = profileService.removePhoto(currentId());
        refreshSession(user, request, response);
        activityLogService.record(MODULE, "UPDATE_PROFILE_PHOTO", "Removed own photo (" + user.getUsername() + ")", ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess", messages.get("profile.photo.removed"));
        return "redirect:/profile";
    }

    /** One of the signed-in user's own payslips (a posted or paid run), as PDF; anyone else's is not found. */
    @GetMapping("/payslips/{lineId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> payslip(@PathVariable UUID lineId) {
        PayRunLine line = payRunService.payslipOf(lineId, currentId());
        PayRun run = payRunService.findDetailed(line.getPayRunId());
        byte[] body = PayRunController.payslipPdf(run, List.of(line), pdfService, reportFiles);
        activityLogService.record(MODULE, "EXPORT_PAYSLIPS", "Downloaded their payslip of pay run " + run.getNumber(), ActivityStatus.SUCCESS);
        return ReportFiles.file("payslip-" + run.getPeriod().format(DateTimeFormatter.ofPattern("yyyy-MM")) + ".pdf", MediaType.APPLICATION_PDF,
                body);
    }

    /** The signed-in user's photo. Its address changes with the photo (?v=), so the browser may keep it for a day. */
    @GetMapping("/photo")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> photo() throws IOException {
        String key = AppUserPrincipal.current().map(AppUserPrincipal::getPhotoKey).orElse(null);
        if (key == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = profileService.photo(key)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(in.readAllBytes());
        } catch (BusinessException e) {
            return ResponseEntity.notFound().build(); // storage down: the header shows the initials
        }
    }

    private String page(Model model, User me, String tab) {
        model.addAttribute("me", me);
        model.addAttribute("payslips", payRunService.payslipsOf(me.getId(), 0, 12));
        model.addAttribute("tab", tab);
        return "profile/view";
    }

    private String invalid(Model model, User me, String tab, BindingResult result, Object otherForm, String otherName) {
        model.addAttribute("formErrors", result.getFieldErrors());
        model.addAttribute(otherName, otherForm);
        return page(model, me, tab);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, User me, String tab, BindingResult result, BusinessException e, Object otherForm, String otherName) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, me, tab, result, otherForm, otherName);
    }

    /** The session's user takes the new name and photo (the header reads them from it, never from the database). */
    private void refreshSession(User user, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal current)) {
            return;
        }
        AppUserPrincipal updated = current.withProfile(user.getFullName(), user.getPhotoUrl());
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(updated, null, updated.getAuthorities());
        token.setDetails(auth.getDetails());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    private static Long currentId() {
        return AppUserPrincipal.current().map(AppUserPrincipal::getId)
                .orElseThrow(() -> new IllegalStateException("No signed-in user"));
    }
}
