package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.FloatClearanceDto;
import com.ntaganira.heritier.iWarehouse.entity.FloatClearance;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : FloatController.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : /floats (ACC-06, ACC-07): the trips back whose cash the accountant has not counted yet, the clearances made,
 *               and what each driver holds and owes (from the ledger). Clearing a float (CLEAR_FLOAT) counts the trip's cash
 *               against its cash sales and what the driver declared, into the vault or the bank. PAGE_FLOATS + VIEW_FLOATS.
 * </pre>
 */
@Controller
@RequestMapping("/floats")
public class FloatController {

    static final String MODULE = "Accounting";

    private final FloatService floatService;
    private final TripService tripService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final NumberFormats num;
    private final Messages messages;

    public FloatController(FloatService floatService, TripService tripService, JournalService journalService, DataChangeService dataChangeService,
                           ActivityLogService activityLogService, NumberFormats num, Messages messages) {
        this.floatService = floatService;
        this.tripService = tripService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.num = num;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_FLOATS') and hasAuthority('PERM_VIEW_FLOATS')")
    public String list(@RequestParam(defaultValue = "clear") String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("clear", "clearances", "drivers").contains(tab) ? tab : "clear";
        List<FloatService.ToClear> toClear = floatService.toClear();
        List<FloatService.DriverRow> drivers = floatService.drivers();
        model.addAttribute("toClear", Paging.of(toClear, Paging.pageOf("clear", open, page)));
        model.addAttribute("toClearTotal", toClear.stream().map(FloatService.ToClear::expected).reduce(java.math.BigDecimal.ZERO,
                java.math.BigDecimal::add));
        model.addAttribute("clearances", floatService.clearances(Paging.pageOf("clearances", open, page), Paging.SIZE));
        model.addAttribute("drivers", Paging.of(drivers, Paging.pageOf("drivers", open, page)));
        model.addAttribute("floatTotal", drivers.stream().map(FloatService.DriverRow::floatBalance).reduce(java.math.BigDecimal.ZERO,
                java.math.BigDecimal::add));
        model.addAttribute("shortageTotal", drivers.stream().map(FloatService.DriverRow::shortage).reduce(java.math.BigDecimal.ZERO,
                java.math.BigDecimal::add));
        model.addAttribute("tab", open);
        return "floats/list";
    }

    @GetMapping("/clear/{tripId}")
    @PreAuthorize("hasAuthority('PAGE_FLOATS') and hasAuthority('PERM_CLEAR_FLOAT')")
    public String form(@PathVariable UUID tripId, Model model) {
        if (!model.containsAttribute("floatDto")) {
            model.addAttribute("floatDto", new FloatClearanceDto());
        }
        return form(tripId, model, null);
    }

    @PostMapping("/clear/{tripId}")
    @PreAuthorize("hasAuthority('PAGE_FLOATS') and hasAuthority('PERM_CLEAR_FLOAT')")
    public String clear(@PathVariable UUID tripId, @Valid @ModelAttribute("floatDto") FloatClearanceDto dto, BindingResult result,
                        Model model, RedirectAttributes redirect) {
        Trip trip = tripService.findDetailed(tripId);
        if (result.hasErrors()) {
            return form(tripId, model, trip);
        }
        try {
            FloatClearance c = floatService.clear(tripId, dto);
            activityLogService.record(MODULE, "CLEAR_FLOAT", "Cleared the float of trip " + trip.getNumber() + " (" + trip.getDriver().getUsername()
                    + "): " + num.money(c.getExpected()) + " RWF expected, " + num.money(c.getCounted()) + " counted"
                    + (c.getShortage().signum() > 0 ? ", " + num.money(c.getShortage()) + " short" : "")
                    + (c.getOverage().signum() > 0 ? ", " + num.money(c.getOverage()) + " over" : "") + ", into " + c.getDepositAccount()
                    + " (" + c.getNumber() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("float.clearedNow", trip.getNumber(), c.getNumber()));
            return "redirect:/floats/" + c.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CLEAR_FLOAT", "Failed to clear the float of trip " + trip.getNumber() + ": " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return form(tripId, model, trip);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_FLOATS') and hasAuthority('PERM_VIEW_FLOATS')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        FloatClearance c = floatService.findDetailed(id);
        model.addAttribute("clearance", c);
        model.addAttribute("journals", journalService.forSource(id, JournalSource.FLOAT_CLEARANCE));
        model.addAttribute("history", dataChangeService.history("FloatClearance", id.toString(), Paging.page(page), Paging.SIZE));
        return "floats/view";
    }

    private String form(UUID tripId, Model model, Trip known) {
        Trip trip = known != null ? known : tripService.findDetailed(tripId);
        model.addAttribute("trip", trip);
        model.addAttribute("expected", floatService.cashOf(tripId));
        model.addAttribute("accounts", List.of(AccountKey.CASH_VAULT, AccountKey.BANK));
        return "floats/form";
    }
}
