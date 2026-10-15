package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.TillSession;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.CashCount;
import com.ntaganira.heritier.iWarehouse.service.CreditNoteService;
import com.ntaganira.heritier.iWarehouse.service.CustomerAccountService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.JournalService;
import com.ntaganira.heritier.iWarehouse.service.SalesService;
import com.ntaganira.heritier.iWarehouse.service.TillService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
    private final CustomerAccountService accountService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public TillSessionController(TillService tillService, SalesService salesService, CreditNoteService creditNoteService,
                                 CustomerAccountService accountService, JournalService journalService, DataChangeService dataChangeService,
                                 ActivityLogService activityLogService, Messages messages) {
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.tillService = tillService;
        this.salesService = salesService;
        this.creditNoteService = creditNoteService;
        this.accountService = accountService;
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
        String open = List.of("invoices", "balances", "accounts", "refunds", "history").contains(tab) ? tab : "invoices";
        TillSession session = tillService.findById(id);
        model.addAttribute("till", session);
        model.addAttribute("summary", tillService.summary(session));
        model.addAttribute("invoices", Paging.of(salesService.invoicesOf(session), Paging.pageOf("invoices", open, page)));
        // Balances of orders paid in this till (POS-08): their cash is in its drawer
        model.addAttribute("balances", Paging.of(salesService.balancesTaken(session), Paging.pageOf("balances", open, page)));
        // Cash refunded on credit notes (POS-09): it left the drawer
        model.addAttribute("refunds", Paging.of(creditNoteService.refundsOf(session), Paging.pageOf("refunds", open, page)));
        // Cash taken on customer accounts (ACC-09): it is in the drawer
        model.addAttribute("accountPayments", Paging.of(accountService.paymentsOf(session), Paging.pageOf("accounts", open, page)));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.TILL_OPENED, JournalSource.TILL_CLOSED));
        // The count note by note, Treasury's verification, the deposit that banked it (F-13)
        model.addAttribute("count", CashCount.parse(session.getDenominations()));
        model.addAttribute("canVerify", !session.isOpen() && !session.isVerified()
                && !session.getCashierId().equals(AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null)));
        model.addAttribute("history", dataChangeService.history("TillSession", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "till-sessions/view";
    }

    /** Treasury verifies a closed till's count (F-13): never its cashier. */
    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAuthority('PAGE_TILL_SESSIONS') and hasAuthority('PERM_VERIFY_CASH_COUNT')")
    public String verify(@PathVariable UUID id, @RequestParam(required = false) String verifyNote, RedirectAttributes redirect) {
        try {
            TillSession till = tillService.verify(id, verifyNote);
            activityLogService.record("Till Sessions", "APPROVE_CASH_COUNT", "Verified the cash count of till " + till.getNumber() + ": "
                    + till.getCountedCash().toPlainString() + " RWF" + (till.getVerifyNote() == null ? "" : " (" + till.getVerifyNote() + ")"),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("till.verified", till.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record("Till Sessions", "APPROVE_CASH_COUNT", "Failed to verify the cash count of till " + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/till-sessions/" + id;
    }
}
