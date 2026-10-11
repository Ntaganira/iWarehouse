package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.service.GlobalSearchService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SearchController.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : The header's search (/search?q=): a code opens its record at once, other text lists the matches by kind.
 *               Every signed-in user has it, like My profile; each kind shows only to who may open its list.
 * </pre>
 */
@Controller
@RequestMapping("/search")
public class SearchController {

    private final GlobalSearchService searchService;

    public SearchController(GlobalSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public String search(@RequestParam(required = false) String q, Model model) {
        GlobalSearchService.Result result = searchService.search(q);
        if (result.jumpTo() != null) {
            return "redirect:" + result.jumpTo();
        }
        model.addAttribute("result", result);
        return "search/results";
    }
}
