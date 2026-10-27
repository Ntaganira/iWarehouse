package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.service.DocumentShareService;
import com.ntaganira.heritier.iWarehouse.service.PdfService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PublicReceiptController.java
 * - Date      : 2026. 10. 27.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The public, read-only page of a shared document (digital receipt sharing). The random token in the
 *               address is the only key; no sign-in is needed, and the page never links back into the system. The
 *               same document is offered as a PDF. Nothing here is cached, indexed or sent as a referrer.
 * </pre>
 */
@Controller
public class PublicReceiptController {

    private final DocumentShareService shareService;
    private final ReportFiles reportFiles;
    private final PdfService pdfService;
    private final Messages messages;
    private final Clock clock;

    public PublicReceiptController(DocumentShareService shareService, ReportFiles reportFiles, PdfService pdfService,
                                   Messages messages, Clock clock) {
        this.shareService = shareService;
        this.reportFiles = reportFiles;
        this.pdfService = pdfService;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/r/{token}")
    public String receipt(@PathVariable String token, Model model, HttpServletResponse response) {
        DocumentShareService.PublicDocument document = shareService.open(token);
        privateResponse(response);
        model.addAttribute("doc", document);
        model.addAttribute("generatedAt", LocalDateTime.now(clock));
        return "public/receipt";
    }

    @GetMapping("/r/{token}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String token) {
        DocumentShareService.PublicDocument document = shareService.open(token);
        Map<String, Object> model = reportFiles.letterhead();
        model.put("printedBy", messages.get("public.sharedLink"));
        model.put("doc", document);
        byte[] body = pdfService.render("public/receipt-pdf", model);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Disposition", "attachment; filename=\""
                        + DocumentShareService.pdfFileName(document.number()) + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }

    private static void privateResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
