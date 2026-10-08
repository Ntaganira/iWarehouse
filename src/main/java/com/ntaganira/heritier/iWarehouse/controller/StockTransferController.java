package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.StockTransferDto;
import com.ntaganira.heritier.iWarehouse.entity.StockTransfer;
import com.ntaganira.heritier.iWarehouse.entity.StockTransferLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.StockService;
import com.ntaganira.heritier.iWarehouse.service.StockTransferService;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StockTransferController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Transfer screens (INV-07): list, transfer page with its units and History, and the
 *               form (scan or type the codes, choose where they go). PAGE_STOCK_TRANSFERS +
 *               PERM_VIEW_STOCK_TRANSFER; moving units PERM_TRANSFER_STOCK.
 * </pre>
 */
@Controller
@RequestMapping("/stock-transfers")
public class StockTransferController {

    static final String MODULE = "Stock Transfers";
    private static final int PAGE_SIZE = 20;

    private final StockTransferService transferService;
    private final StockService stockService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;

    public StockTransferController(StockTransferService transferService, StockService stockService,
                                   DataChangeService dataChangeService, ActivityLogService activityLogService,
                                   Validator validator, Messages messages) {
        this.transferService = transferService;
        this.stockService = stockService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_STOCK_TRANSFERS') and hasAuthority('PERM_VIEW_STOCK_TRANSFER')")
    public String list(@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, Model model) {
        Page<StockTransfer> transfers = transferService.findPage(search, Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("transfers", transfers);
        model.addAttribute("counts", transferService.unitCounts(transfers.getContent()));
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("search", search));
        return "stock-transfers/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_STOCK_TRANSFERS') and hasAuthority('PERM_VIEW_STOCK_TRANSFER')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "units") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        StockTransfer transfer = transferService.findDetailed(id);
        model.addAttribute("transfer", transfer);
        model.addAttribute("units", transferService.unitsOf(transfer));
        model.addAttribute("locations", stockService.locationsById());
        model.addAttribute("history", dataChangeService.historyWithChildren("StockTransfer", id.toString(),
                List.of("StockTransferLine"), "transfer", Math.max(page, 0), 20));
        model.addAttribute("tab", "history".equals(tab) ? tab : "units");
        return "stock-transfers/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_STOCK_TRANSFERS') and hasAuthority('PERM_TRANSFER_STOCK')")
    public String createForm(@RequestParam(required = false) String codes, Model model) {
        StockTransferDto dto = new StockTransferDto();
        dto.setCodes(codes);
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_STOCK_TRANSFERS') and hasAuthority('PERM_TRANSFER_STOCK')")
    public String create(@ModelAttribute("transferDto") StockTransferDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        validator.validate(dto, result);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto);
        }
        try {
            StockTransfer transfer = transferService.create(dto);
            String codes = transfer.getLines().stream().map(StockTransferLine::getUnitCode).collect(Collectors.joining(", "));
            activityLogService.record(MODULE, "CREATE_STOCK_TRANSFER", "Moved " + transfer.getLines().size() + " unit(s) to "
                    + transfer.getToLocation().getCode() + " (" + transfer.getNumber() + "): " + codes
                    + (transfer.getNote() == null ? "" : "; " + transfer.getNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("transfer.created", transfer.getLines().size(),
                    transfer.getToLocation().getCode(), transfer.getNumber()));
            return "redirect:/stock-transfers/" + transfer.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_STOCK_TRANSFER", "Failed to move units: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto);
        }
    }

    private String form(Model model, StockTransferDto dto) {
        model.addAttribute("transferDto", dto);
        model.addAttribute("places", transferService.places());
        return "stock-transfers/form";
    }
}
