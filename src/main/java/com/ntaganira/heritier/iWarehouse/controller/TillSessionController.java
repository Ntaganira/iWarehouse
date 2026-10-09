package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import com.ntaganira.heritier.iWarehouse.service.CreditNoteService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.JournalService;
import com.ntaganira.heritier.iWarehouse.service.SalesService;
import com.ntaganira.heritier.iWarehouse.service.TillService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : TillSessionController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Till sessions (POS-10): the list with their differences, a session with what it took per
 *               payment method, its invoices, journals and History. PAGE_TILL_SESSIONS + PERM_VIEW_TILL_SESSION.
 * </pre>
 */
@Controller
@RequestMapping("/till-sessions")
public class TillSessionController {

    private final TillService tillService;
    private final SalesService salesService;
    private final CreditNoteService creditNoteService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;

    public TillSessionController(TillService tillService, SalesService salesService, CreditNoteService creditNoteService,
                                 JournalService journalService, DataChangeService dataChangeService) {
        this.tillService = tillService;
        this.salesService = salesService;
        this.creditNoteService = creditNoteService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_TILL_SESSIONS') and hasAuthority('PERM_VIEW_TILL_SESSION')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("sessions", tillService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", TillStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "till-sessions/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_TILL_SESSIONS') and hasAuthority('PERM_VIEW_TILL_SESSION')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "invoices") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("invoices", "balances", "refunds", "history").contains(tab) ? tab : "invoices";
        TillSession session = tillService.findById(id);
        model.addAttribute("till", session);
        model.addAttribute("summary", tillService.summary(session));
        model.addAttribute("invoices", Paging.of(salesService.invoicesOf(session), Paging.pageOf("invoices", open, page)));
        // Balances of orders paid in this till (POS-08): their cash is in its drawer
        model.addAttribute("balances", Paging.of(salesService.balancesTaken(session), Paging.pageOf("balances", open, page)));
        // Cash refunded on credit notes (POS-09): it left the drawer
        model.addAttribute("refunds", Paging.of(creditNoteService.refundsOf(session), Paging.pageOf("refunds", open, page)));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.TILL_OPENED, JournalSource.TILL_CLOSED));
        model.addAttribute("history", dataChangeService.history("TillSession", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "till-sessions/view";
    }
}
