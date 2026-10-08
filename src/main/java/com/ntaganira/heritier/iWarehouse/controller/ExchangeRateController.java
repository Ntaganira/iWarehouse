package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.ExchangeRateDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.ExchangeRate;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ExchangeRateController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Exchange rates (ACC-02): record a rate, correct one with a reason (kept in the change
 *               log), import a CSV file. PAGE_CURRENCIES + PERM_MANAGE_EXCHANGE_RATE. Rates are never
 *               deleted. Listed on /currencies.
 * </pre>
 */
@Controller
@RequestMapping("/currencies/rates")
public class ExchangeRateController {

    private final ExchangeRateService rateService;
    private final CurrencyService currencyService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public ExchangeRateController(ExchangeRateService rateService, CurrencyService currencyService,
                                  DataChangeService dataChangeService, ActivityLogService activityLogService,
                                  Messages messages) {
        this.rateService = rateService;
        this.currencyService = currencyService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_EXCHANGE_RATE')")
    public String createForm(@RequestParam(required = false) String currency, Model model) {
        ExchangeRateDto dto = new ExchangeRateDto();
        dto.setCurrencyCode(currency);
        dto.setRateDate(rateService.today());
        dto.setSource(rateService.defaultSource());
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_EXCHANGE_RATE')")
    public String create(@Valid @ModelAttribute("rateDto") ExchangeRateDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            ExchangeRate rate = rateService.create(dto);
            activityLogService.record(CurrencyController.MODULE, "CREATE_EXCHANGE_RATE", "Recorded " + describe(rate)
                    + (dto.isConfirmed() ? " (large change confirmed)" : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("rate.created", rate.getCurrencyCode(), rate.getRateDate()));
            return "redirect:/currencies";
        } catch (BusinessException e) {
            activityLogService.record(CurrencyController.MODULE, "CREATE_EXCHANGE_RATE", "Failed to record "
                    + dto.getCurrencyCode() + " " + dto.getSource() + " rate of " + dto.getRateDate() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_EXCHANGE_RATE')")
    public String editForm(@PathVariable UUID id, Model model) {
        ExchangeRate rate = rateService.findById(id);
        ExchangeRateDto dto = new ExchangeRateDto();
        dto.setId(id);
        fixed(dto, rate);
        dto.setRate(rate.getRate().stripTrailingZeros());
        dto.setNote(rate.getNote());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_EXCHANGE_RATE')")
    public String correct(@PathVariable UUID id, @Valid @ModelAttribute("rateDto") ExchangeRateDto dto,
                          BindingResult result, Model model, RedirectAttributes redirect) {
        ExchangeRate current = rateService.findById(id);
        dto.setId(id);
        fixed(dto, current); // currency, date and source can't change
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            ExchangeRateService.Correction correction =
                    AuditContext.withReason(dto.getReason(), () -> rateService.correct(id, dto));
            if (!correction.changed()) {
                redirect.addFlashAttribute("flashWarning", messages.get("settings.noChanges"));
                return "redirect:/currencies";
            }
            ExchangeRate rate = correction.rate();
            activityLogService.record(CurrencyController.MODULE, "UPDATE_EXCHANGE_RATE", "Corrected " + rate.getCurrencyCode()
                    + " " + rate.getSource() + " rate of " + rate.getRateDate() + ": "
                    + correction.oldRate().stripTrailingZeros().toPlainString() + " -> "
                    + rate.getRate().stripTrailingZeros().toPlainString() + " (reason: " + dto.getReason().trim() + ")",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("rate.corrected", rate.getCurrencyCode(), rate.getRateDate()));
            return "redirect:/currencies";
        } catch (BusinessException e) {
            activityLogService.record(CurrencyController.MODULE, "UPDATE_EXCHANGE_RATE", "Failed to correct "
                    + current.getCurrencyCode() + " " + current.getSource() + " rate of " + current.getRateDate() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/import")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_EXCHANGE_RATE')")
    public String importForm(Model model) {
        return importPage(model, rateService.defaultSource(), false, List.of());
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_EXCHANGE_RATE')")
    public String importFile(@RequestParam("file") MultipartFile file,
                             @RequestParam(defaultValue = "BNR") RateSource source,
                             @RequestParam(defaultValue = "false") boolean confirmed,
                             Model model, RedirectAttributes redirect) throws IOException {
        if (file.isEmpty()) {
            return importPage(model, source, confirmed, List.of(messages.get("rate.import.noFile")));
        }
        String name = file.getOriginalFilename();
        ExchangeRateService.ImportResult result =
                rateService.importCsv(new String(file.getBytes(), StandardCharsets.UTF_8), source, confirmed, name);
        if (!result.ok()) {
            List<String> problems = result.problems().stream()
                    .map(p -> {
                        String text = messages.get(p.messageKey(), p.args());
                        return p.line() > 0 ? messages.get("rate.import.line", p.line(), text) : text;
                    })
                    .toList();
            activityLogService.record(CurrencyController.MODULE, "IMPORT_EXCHANGE_RATES", "Refused " + source
                    + " rate file " + name + ": " + problems.size() + " problem(s), first: " + problems.get(0),
                    ActivityStatus.FAILED);
            return importPage(model, source, confirmed, problems);
        }
        activityLogService.record(CurrencyController.MODULE, "IMPORT_EXCHANGE_RATES", "Imported " + result.added()
                + " " + source + " rates from " + name + " (" + result.unchanged() + " already recorded)"
                + (confirmed ? ", large changes confirmed" : ""), ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess", messages.get("rate.import.done", result.added(), result.unchanged()));
        return "redirect:/currencies";
    }

    private static void fixed(ExchangeRateDto dto, ExchangeRate rate) {
        dto.setCurrencyCode(rate.getCurrencyCode());
        dto.setRateDate(rate.getRateDate());
        dto.setSource(rate.getSource());
    }

    private static String describe(ExchangeRate rate) {
        return rate.getCurrencyCode() + " " + rate.getSource() + " rate of " + rate.getRateDate() + ": 1 "
                + rate.getCurrencyCode() + " = " + rate.getRate().stripTrailingZeros().toPlainString() + " RWF";
    }

    private String importPage(Model model, RateSource source, boolean confirmed, List<String> problems) {
        model.addAttribute("sources", RateSource.values());
        model.addAttribute("source", source);
        model.addAttribute("confirmed", confirmed);
        model.addAttribute("problems", problems);
        model.addAttribute("activeCodes", currencyService.findActiveForeign().stream().map(Currency::getCode).toList());
        model.addAttribute("maxLines", ExchangeRateService.MAX_IMPORT_LINES);
        return "currencies/import";
    }

    private String form(Model model, ExchangeRateDto dto) {
        model.addAttribute("rateDto", dto);
        model.addAttribute("currencies", currencyService.findActiveForeign());
        model.addAttribute("sources", RateSource.values());
        model.addAttribute("today", rateService.today());
        // Latest default-source rate per currency, for the live comparison while typing (rate-form.js)
        model.addAttribute("latestJson", rateService.latestRates().stream()
                .filter(latest -> latest.rate() != null)
                .map(latest -> Map.of("code", latest.currency().getCode(),
                        "rate", latest.rate().getRate().stripTrailingZeros().toPlainString(),
                        "date", latest.rate().getRateDate().toString()))
                .toList());
        model.addAttribute("defaultSource", rateService.defaultSource());
        if (dto.getId() != null) {
            model.addAttribute("history", dataChangeService.history("ExchangeRate", dto.getId().toString(), 0, 10));
        }
        return "currencies/rate-form";
    }

    private String invalid(Model model, ExchangeRateDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field (and offer to confirm a large change), or as a toast. */
    private String rejected(Model model, ExchangeRateDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        model.addAttribute("largeChange", "rate.largeChange".equals(e.getMessageKey()));
        return invalid(model, dto, result);
    }
}
