package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.enums.ShareableDocument;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.DocumentShareService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ShareLinkController.java
 * - Date      : 2026. 10. 27.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Digital receipt sharing: the Share button's POST makes (or returns) the document's public,
 *               read-only link. A page never creates a public link by being opened; only a user who may open
 *               the document's own page may share it.
 * </pre>
 */
@Controller
public class ShareLinkController {

    private final DocumentShareService shareService;

    public ShareLinkController(DocumentShareService shareService) {
        this.shareService = shareService;
    }

    @PostMapping("/share-links")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public DocumentShareService.Share create(@RequestParam ShareableDocument type, @RequestParam UUID id) {
        if (!AppUserPrincipal.currentHas(type.pageAuthority()) || !AppUserPrincipal.currentHas(type.viewPermission())) {
            throw new AccessDeniedException("Cannot share this document");
        }
        DocumentShareService.Share share = shareService.link(type, id);
        if (share == null) {
            throw new NotFoundException("Shared document", id);
        }
        return share;
    }
}
