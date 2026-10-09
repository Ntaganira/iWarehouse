package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.AccountDto;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.AccountService;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.JournalService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : AccountController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Chart of accounts screens (ACC-03): the list with balances, an account's ledger and History,
 *               add, edit, activate and deactivate. PAGE_ACCOUNTS + PERM_VIEW_ACCOUNTING; changes
 *               PERM_MANAGE_ACCOUNTS.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/accounts")
public class AccountController {

    static final String MODULE = "Accounting";

    private final AccountService accountService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public AccountController(AccountService accountService, JournalService journalService, DataChangeService dataChangeService,
                             ActivityLogService activityLogService, Messages messages) {
        this.accountService = accountService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String type,
                       @RequestParam(defaultValue = "false") boolean inactive, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<Account> accounts = accountService.findPage(search, type, inactive, Paging.page(page), Paging.SIZE);
        model.addAttribute("accounts", accounts);
        model.addAttribute("net", journalService.netByAccount());
        model.addAttribute("types", AccountType.values());
        model.addAttribute("search", search);
        model.addAttribute("type", type);
        model.addAttribute("inactive", inactive);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "type", type, "inactive", inactive ? "true" : null));
        return "accounts/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "ledger") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("ledger", "details", "history").contains(tab) ? tab : "ledger";
        Account account = accountService.findById(id);
        BigDecimal net = journalService.netByAccount().getOrDefault(id, BigDecimal.ZERO);
        model.addAttribute("account", account);
        model.addAttribute("balance", account.getType().isDebitNormal() ? net : net.negate());
        model.addAttribute("ledger", journalService.ledger(id, Paging.pageOf("ledger", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("Account", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "accounts/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_MANAGE_ACCOUNTS')")
    public String createForm(Model model) {
        return form(model, new AccountDto(), null);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_MANAGE_ACCOUNTS')")
    public String create(@Valid @ModelAttribute("accountDto") AccountDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, null, result);
        }
        try {
            Account account = accountService.create(dto);
            activityLogService.record(MODULE, "CREATE_ACCOUNT", "Added account " + describe(account), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("account.created", account.getCode()));
            return "redirect:/accounting/accounts/" + account.getId() + "?tab=details";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_ACCOUNT", "Failed to add account " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, null, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_MANAGE_ACCOUNTS')")
    public String editForm(@PathVariable UUID id, Model model) {
        Account account = accountService.findById(id);
        AccountDto dto = new AccountDto();
        dto.setId(id);
        dto.setCode(account.getCode());
        dto.setName(account.getName());
        dto.setType(account.getType());
        dto.setDescription(account.getDescription());
        return form(model, dto, account);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_MANAGE_ACCOUNTS')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("accountDto") AccountDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        dto.setId(id);
        Account current = accountService.findById(id);
        if (result.hasErrors()) {
            return invalid(model, dto, current, result);
        }
        try {
            Account account = accountService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_ACCOUNT", "Updated account " + describe(account), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("account.updated", account.getCode()));
            return "redirect:/accounting/accounts/" + id + "?tab=details";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_ACCOUNT", "Failed to update account " + current.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, current, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_MANAGE_ACCOUNTS')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTS') and hasAuthority('PERM_MANAGE_ACCOUNTS')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_ACCOUNT" : "DISABLE_ACCOUNT";
        try {
            Account account = accountService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated" : "Deactivated") + " account " + describe(account),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(enabled ? "account.enabledMsg" : "account.disabledMsg", account.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change the status of account " + codeOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/accounting/accounts/" + id + "?tab=details";
    }

    private String codeOf(UUID id) {
        try {
            return accountService.findById(id).getCode();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    /** "5100 Rent (EXPENSE)" for the activity log. */
    private static String describe(Account account) {
        return account.getCode() + " " + account.getName() + " (" + account.getType() + ")";
    }

    private String form(Model model, AccountDto dto, Account account) {
        model.addAttribute("accountDto", dto);
        model.addAttribute("account", account);
        model.addAttribute("types", AccountType.values());
        // The type is fixed once lines were posted, and on the accounts the posting rules use
        model.addAttribute("typeFixed", account != null && (account.isSystem() || accountService.isUsed(account.getId())));
        return "accounts/form";
    }

    private String invalid(Model model, AccountDto dto, Account account, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto, account);
    }

    private String rejected(Model model, AccountDto dto, Account account, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, account, result);
    }
}
