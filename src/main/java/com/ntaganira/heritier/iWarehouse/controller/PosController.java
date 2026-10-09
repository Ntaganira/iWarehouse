package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PosController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The counter POS (POS-01, POS-04, POS-10): open the till with a float; ring up a sale by scanning
 *               labels or from the smallest-fit search; choose the customer and the buyer's name and TIN; take a
 *               split payment, which issues the invoice; close the till with the cash counted. Change a line's
 *               price with a reason, ask a manager for a discount above the cashier's limit (POS-06) or credit
 *               above the customer's limit (POS-05), withdraw a request; the page follows their decisions.
 *               PAGE_POS + PERM_SELL.
 * </pre>
 */
@Controller
@RequestMapping("/pos")
public class PosController {

    static final String MODULE = "Sales";
    private static final int SEARCH_ROWS = 12;

    private final TillService tillService;
    private final SalesService salesService;
    private final StockService stockService;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;
    private final PriceListService priceListService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public PosController(TillService tillService, SalesService salesService, StockService stockService,
                         ProductRepository productRepo, CustomerRepository customerRepo, PriceListService priceListService,
                         ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.tillService = tillService;
        this.salesService = salesService;
        this.stockService = stockService;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
        this.priceListService = priceListService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String pos(@RequestParam(required = false) UUID product, @RequestParam(required = false) Integer minWidth,
                      @RequestParam(required = false) Integer minHeight, Model model) {
        Optional<TillSession> till = tillService.current();
        if (till.isEmpty()) {
            return "pos/open";
        }
        TillSession session = till.get();
        SalesInvoice sale = salesService.cart(session).orElse(null);
        Customer customer = sale != null ? sale.getCustomer() : customerRepo.findByDefaultCustomerTrue().orElse(null);
        Integer w = positive(minWidth);
        Integer h = positive(minHeight);
        boolean searching = product != null || w != null || h != null;
        List<StockUnit> results = List.of();
        if (searching) {
            // Available pieces of the glass; with a size, those at least that big either way round, smallest first
            // (INV-06): a size left empty counts as 1 mm
            boolean sized = w != null || h != null;
            results = stockService.findPage(new StockService.UnitFilter(null, product, null, "AVAILABLE", null,
                    sized ? (w == null ? 1 : w) : null, sized ? (h == null ? 1 : h) : null), 0, SEARCH_ROWS).getContent();
        }
        Set<UUID> inSale = new HashSet<>();
        if (sale != null) {
            sale.getLines().forEach(l -> inSale.add(l.getStockUnitId()));
        }
        model.addAttribute("till", session);
        model.addAttribute("summary", tillService.summary(session));
        model.addAttribute("sale", sale);
        model.addAttribute("customer", customer);
        model.addAttribute("totals", sale == null ? Vat.Totals.NONE : salesService.totals(sale));
        model.addAttribute("credit", customer == null ? null : salesService.credit(customer));
        // Approval requests (POS-05, POS-06): the latest of each line, those waiting, the credit approved
        List<SaleApproval> approvals = salesService.approvals(sale);
        Map<UUID, SaleApproval> lineApprovals = new HashMap<>();
        approvals.stream().filter(a -> a.getLineId() != null).forEach(a -> lineApprovals.put(a.getLineId(), a));
        model.addAttribute("lineApprovals", lineApprovals);
        model.addAttribute("waiting", approvals.stream().filter(SaleApproval::isPending).toList());
        model.addAttribute("creditApproval", approvals.stream().filter(a -> a.isCredit() && !a.isPending()).reduce((a, b) -> b)
                .filter(a -> a.getStatus() != SaleApprovalStatus.WITHDRAWN).orElse(null));
        model.addAttribute("approvalState", approvalState(approvals));
        model.addAttribute("discountLimit", salesService.discountLimit());
        model.addAttribute("depositMinimum", salesService.depositMinimum(sale));
        model.addAttribute("customers", salesService.customers());
        List<Product> products = productRepo.findByEnabledTrueOrderByCodeAsc();
        model.addAttribute("products", products);
        // Sizes are cut from cuttable glass only (tempered glass is made to size)
        model.addAttribute("cuttable", products.stream().filter(p -> p.getGlassType().isCuttable()).toList());
        List<ProcessingService> services = priceListService.services().stream().filter(ProcessingService::isEnabled).toList();
        model.addAttribute("services", services);
        model.addAttribute("holeServices", services.stream().anyMatch(sv -> sv.getChargeUnit() == ChargeUnit.HOLE));
        model.addAttribute("results", results);
        model.addAttribute("prices", customer == null ? Map.of() : salesService.prices(customer, results));
        model.addAttribute("inSale", inSale);
        model.addAttribute("searching", searching);
        model.addAttribute("product", product);
        model.addAttribute("minWidth", w);
        model.addAttribute("minHeight", h);
        model.addAttribute("searchQuery", QueryString.of("product", product == null ? null : product.toString(),
                "minWidth", w == null ? null : w.toString(), "minHeight", h == null ? null : h.toString()));
        return "pos/index";
    }

    // ---------------------------------------------------------------- the till (POS-10)

    @PostMapping("/till/open")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String openTill(@RequestParam(required = false) BigDecimal openingFloat, RedirectAttributes redirect) {
        try {
            TillSession session = tillService.open(openingFloat);
            activityLogService.record(MODULE, "OPEN_TILL", "Opened till " + session.getNumber() + " with a float of "
                    + num.money(session.getOpeningFloat()) + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("till.openedMsg", session.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "OPEN_TILL", "Failed to open a till", e);
        }
        return "redirect:/pos";
    }

    @GetMapping("/till")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String closeForm(Model model, RedirectAttributes redirect) {
        Optional<TillSession> till = tillService.current();
        if (till.isEmpty()) {
            redirect.addFlashAttribute("flashError", messages.get("till.notOpen"));
            return "redirect:/pos";
        }
        model.addAttribute("till", till.get());
        model.addAttribute("summary", tillService.summary(till.get()));
        model.addAttribute("sale", salesService.cart(till.get()).filter(s -> !s.getLines().isEmpty()).orElse(null));
        return "pos/close";
    }

    @PostMapping("/till/close")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String closeTill(@RequestParam(required = false) BigDecimal countedCash, @RequestParam(required = false) String note,
                            RedirectAttributes redirect) {
        Optional<TillSession> till = tillService.current();
        if (till.isEmpty()) {
            redirect.addFlashAttribute("flashError", messages.get("till.notOpen"));
            return "redirect:/pos";
        }
        try {
            TillSession session = tillService.close(till.get().getId(), countedCash, note);
            activityLogService.record(MODULE, "CLOSE_TILL", "Closed till " + session.getNumber() + ": "
                    + num.money(session.getCountedCash()) + " RWF counted, " + num.money(session.getExpectedCash()) + " expected"
                    + (session.getDifference().signum() == 0 ? "" : ", difference " + num.money(session.getDifference())
                    + " (" + session.getCloseNote() + ")"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute(session.getDifference().signum() == 0 ? "flashSuccess" : "flashWarning",
                    session.getDifference().signum() == 0 ? messages.get("till.closedMsg", session.getNumber())
                            : messages.get("till.closedDifference", session.getNumber(), num.money(session.getDifference())));
            return "redirect:/till-sessions/" + session.getId();
        } catch (BusinessException e) {
            fail(redirect, "CLOSE_TILL", "Failed to close till " + till.get().getNumber(), e);
            redirect.addFlashAttribute("countedCash", countedCash == null ? null : countedCash.toPlainString());
            redirect.addFlashAttribute("note", note);
            return "redirect:/pos/till";
        }
    }

    // ---------------------------------------------------------------- the sale being rung up (POS-01)

    @PostMapping("/add")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String add(@RequestParam(required = false) String code, @RequestParam(required = false) UUID unitId,
                      @RequestParam(required = false) String back, RedirectAttributes redirect) {
        try {
            SalesInvoice sale = salesService.addUnit(code, unitId);
            SalesInvoiceLine line = sale.getLines().get(sale.getLines().size() - 1);
            activityLogService.record(MODULE, "UPDATE_SALE", "Added " + line.getUnitCode() + " ("
                    + num.money(line.getAmount()) + " RWF) to the sale at till " + sale.getTillSession().getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sale.added", line.getUnitCode(), num.money(line.getAmount())));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_SALE", "Failed to add " + (code != null ? code.trim() : unitId) + " to a sale", e);
        }
        return "redirect:/pos" + query(back);
    }

    /** Adds a size to cut, with its processing (POS-02). */
    @PostMapping("/custom")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String custom(@RequestParam(required = false) UUID productId, @RequestParam(required = false) Integer widthMm,
                         @RequestParam(required = false) Integer heightMm, @RequestParam(required = false) Integer quantity,
                         @RequestParam(required = false) List<UUID> serviceIds, @RequestParam(required = false) Integer holes,
                         @RequestParam(required = false) String mark, @RequestParam(required = false) String back,
                         RedirectAttributes redirect) {
        try {
            SalesInvoice sale = salesService.addCustom(new SalesService.CustomSize(productId, widthMm, heightMm, quantity, serviceIds,
                    holes, mark));
            SalesInvoiceLine size = sale.getLines().stream().filter(SalesInvoiceLine::isCustomPiece)
                    .reduce((a, b) -> b).orElseThrow();
            BigDecimal amount = sale.getLines().stream().filter(l -> l == size || l.getParentLine() == size)
                    .map(SalesInvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String what = size.getProduct().getCode() + " " + size.getWidthMm() + " x " + size.getHeightMm() + " x " + size.getQuantity();
            activityLogService.record(MODULE, "UPDATE_SALE", "Added the size " + what + (size.getProcessing() == null ? "" : " (" + size.getProcessing() + ")")
                    + ", " + num.money(amount) + " RWF, to the sale at till " + sale.getTillSession().getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sale.customAdded", what, num.money(amount)));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_SALE", "Failed to add a size to a sale", e);
            Map<String, Object> form = new HashMap<>();
            form.put("productId", productId);
            form.put("widthMm", widthMm);
            form.put("heightMm", heightMm);
            form.put("quantity", quantity);
            form.put("serviceIds", serviceIds == null ? List.of() : serviceIds);
            form.put("holes", holes);
            form.put("mark", mark);
            redirect.addFlashAttribute("customForm", form);
            redirect.addFlashAttribute("customField", e.getField());
        }
        return "redirect:/pos" + query(back);
    }

    @PostMapping("/lines/{lineId}/remove")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String remove(@PathVariable UUID lineId, @RequestParam(required = false) String back, RedirectAttributes redirect) {
        try {
            SalesInvoiceLine line = salesService.removeLine(lineId);
            activityLogService.record(MODULE, "UPDATE_SALE", "Removed " + line.getUnitCode() + " from the sale", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sale.removed", line.getUnitCode()));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_SALE", "Failed to remove a line from a sale", e);
        }
        return "redirect:/pos" + query(back);
    }

    @PostMapping("/customer")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String customer(@RequestParam(required = false) UUID customerId, @RequestParam(required = false) String buyerName,
                           @RequestParam(required = false) String buyerTin, @RequestParam(required = false) String back,
                           RedirectAttributes redirect) {
        try {
            SalesInvoice sale = salesService.setCustomer(customerId, buyerName, buyerTin);
            activityLogService.record(MODULE, "UPDATE_SALE", "Sale at till " + sale.getTillSession().getNumber() + " for "
                    + sale.getCustomer().getName() + (sale.getBuyerTin() == null ? "" : ", TIN " + sale.getBuyerTin()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sale.customerSet", sale.getBillTo()));
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_SALE", "Failed to set the customer of a sale", e);
        }
        return "redirect:/pos" + query(back);
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String cancel(RedirectAttributes redirect) {
        try {
            SalesInvoice sale = salesService.cancel();
            activityLogService.record(MODULE, "CANCEL_SALE", "Cancelled the sale at till " + sale.getTillSession().getNumber()
                    + " (" + sale.getLines().size() + " line(s))", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sale.cancelled"));
        } catch (BusinessException e) {
            fail(redirect, "CANCEL_SALE", "Failed to cancel a sale", e);
        }
        return "redirect:/pos";
    }

    // ---------------------------------------------------------------- price changes and approvals (POS-05, POS-06)

    /** Changes a line's price with a reason: at once within the cashier's limit, otherwise asked of a manager. */
    @PostMapping("/lines/{lineId}/price")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String price(@PathVariable UUID lineId, @RequestParam(required = false) BigDecimal price,
                        @RequestParam(required = false) String reason, @RequestParam(required = false) String back,
                        RedirectAttributes redirect) {
        try {
            SalesService.PriceChange change = reason == null || reason.isBlank()
                    ? salesService.changePrice(lineId, price, reason)
                    : AuditContext.withReason(reason.trim(), () -> salesService.changePrice(lineId, price, reason));
            String what = change.line().getLabel();
            if (change.approval() != null) {
                SaleApproval a = change.approval();
                activityLogService.record(MODULE, "CREATE_SALE_APPROVAL", "Asked " + a.getNumber() + " for " + what + ": "
                        + num.money(a.getListPrice()) + " to " + num.money(a.getRequestedPrice()) + " (" + num.m2(a.getDiscountPercent())
                        + "% off, limit " + num.m2(a.getLimitPercent()) + "%): " + a.getReason(), ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashWarning", messages.get("sale.price.asked", a.getNumber(), num.m2(a.getDiscountPercent()),
                        num.m2(a.getLimitPercent())));
            } else if (change.line().isPriceChanged()) {
                activityLogService.record(MODULE, "UPDATE_SALE", "Changed the price of " + what + " from " + num.money(change.listPrice())
                        + " to " + num.money(change.price()) + " (" + num.m2(change.discount()) + "% off): " + change.line().getPriceReason(),
                        ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("sale.price.changed", what, num.money(change.line().getAmount())));
            } else {
                activityLogService.record(MODULE, "UPDATE_SALE", "Put " + what + " back at its list price " + num.money(change.listPrice()),
                        ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("sale.price.restored", what, num.money(change.line().getAmount())));
            }
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_SALE", "Failed to change the price of a sale line", e);
            Map<String, Object> form = new HashMap<>();
            form.put("lineId", lineId);
            form.put("price", plain(price));
            form.put("reason", reason);
            redirect.addFlashAttribute("priceForm", form);
            redirect.addFlashAttribute("priceField", e.getField());
        }
        return "redirect:/pos" + query(back);
    }

    /** Asks a manager to approve credit above what the customer has left (POS-05). */
    @PostMapping("/credit-approval")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String creditApproval(@RequestParam(required = false) BigDecimal cash, @RequestParam(required = false) BigDecimal mobileMoney,
                                 @RequestParam(required = false) String mobileMoneyRef, @RequestParam(required = false) BigDecimal card,
                                 @RequestParam(required = false) String cardRef, @RequestParam(required = false) BigDecimal bankTransfer,
                                 @RequestParam(required = false) String bankRef, @RequestParam(required = false) BigDecimal credit,
                                 @RequestParam(required = false) String creditReason, RedirectAttributes redirect) {
        try {
            SaleApproval a = AuditContext.withReason(creditReason == null ? null : creditReason.trim(),
                    () -> salesService.requestCredit(credit, creditReason));
            activityLogService.record(MODULE, "CREATE_SALE_APPROVAL", "Asked " + a.getNumber() + " for credit of " + num.money(a.getCreditAmount())
                    + " RWF for " + a.getSubject() + " (limit " + num.money(a.getCreditLimit()) + ", owed " + num.money(a.getOwed())
                    + "): " + a.getReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashWarning", messages.get("sale.credit.asked", a.getNumber(), num.money(a.getCreditAmount())));
        } catch (BusinessException e) {
            fail(redirect, "CREATE_SALE_APPROVAL", "Failed to ask for credit approval", e);
            Map<String, String> form = payForm(cash, mobileMoney, mobileMoneyRef, card, cardRef, bankTransfer, bankRef, credit);
            form.put("creditReason", creditReason);
            redirect.addFlashAttribute("payForm", form);
            redirect.addFlashAttribute("payField", e.getField());
            redirect.addFlashAttribute("creditOver", true);
        }
        return "redirect:/pos";
    }

    /** Takes back a request still waiting, with a reason. */
    @PostMapping("/approvals/{id}/withdraw")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String withdraw(@PathVariable UUID id, @RequestParam(required = false) String reason, @RequestParam(required = false) String back,
                           RedirectAttributes redirect) {
        try {
            SaleApproval a = AuditContext.withReason(reason == null ? null : reason.trim(), () -> salesService.withdrawRequest(id, reason));
            activityLogService.record(MODULE, "CANCEL_SALE_APPROVAL", "Withdrew " + a.getNumber() + " (" + a.getSubject() + "): "
                    + a.getDecisionNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sale.approval.withdrawn", a.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "CANCEL_SALE_APPROVAL", "Failed to withdraw an approval request", e);
        }
        return "redirect:/pos" + query(back);
    }

    /** Where the sale's requests stand, for the page to reload once a manager decides. */
    @GetMapping(value = "/approvals/state", produces = "application/json")
    @ResponseBody
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public Map<String, String> approvalsState() {
        SalesInvoice sale = tillService.current().flatMap(salesService::cart).orElse(null);
        return Map.of("state", approvalState(salesService.approvals(sale)));
    }

    /** Each request with its status: changes when one is decided. */
    private static String approvalState(List<SaleApproval> approvals) {
        StringBuilder state = new StringBuilder();
        approvals.forEach(a -> state.append(a.getNumber()).append(':').append(a.getStatus()).append(';'));
        return state.toString();
    }

    // ---------------------------------------------------------------- payment (POS-04)

    @PostMapping("/pay")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String pay(@RequestParam(required = false) BigDecimal cash, @RequestParam(required = false) BigDecimal mobileMoney,
                      @RequestParam(required = false) String mobileMoneyRef, @RequestParam(required = false) BigDecimal card,
                      @RequestParam(required = false) String cardRef, @RequestParam(required = false) BigDecimal bankTransfer,
                      @RequestParam(required = false) String bankRef, @RequestParam(required = false) BigDecimal credit,
                      @RequestParam(defaultValue = "false") boolean deposit, RedirectAttributes redirect) {
        SalePayments.Entered entered = new SalePayments.Entered(cash, mobileMoney, mobileMoneyRef, card, cardRef, bankTransfer, bankRef, credit);
        try {
            SalesService.Paid paid = salesService.pay(entered, deposit);
            SalesInvoice invoice = paid.invoice();
            activityLogService.record(MODULE, "CREATE_SALES_INVOICE", "Issued " + invoice.getNumber() + " to " + invoice.getBillTo()
                    + ": " + num.money(invoice.getTotalAmount()) + " RWF"
                    + (invoice.hasBalanceDue() ? ", deposit " + num.money(invoice.getAmountPaid()) + " RWF, balance due "
                    + num.money(invoice.getBalanceDue()) + " RWF" : "")
                    + (paid.journal() == null ? "" : ", journal " + paid.journal().getNumber())
                    + (paid.jobs().isEmpty() ? "" : ", cutting jobs " + paid.jobs().stream().map(CuttingJob::getNumber).toList()),
                    ActivityStatus.SUCCESS);
            String jobs = paid.jobs().stream().map(CuttingJob::getNumber).reduce((a, b) -> a + ", " + b).orElse(null);
            String paidText = invoice.hasBalanceDue()
                    ? messages.get("sale.paidDeposit", invoice.getNumber(), num.money(invoice.getAmountPaid()), num.money(invoice.getBalanceDue()))
                    : messages.get("sale.paid", invoice.getNumber());
            if (paid.change().signum() > 0) {
                paidText = paidText + ". " + messages.get("sale.giveChange", num.money(paid.change()));
            }
            redirect.addFlashAttribute("flashSuccess", jobs == null ? paidText : paidText + ". " + messages.get("sale.jobsCreated", jobs));
            return "redirect:/invoices/" + invoice.getId();
        } catch (BusinessException e) {
            fail(redirect, "CREATE_SALES_INVOICE", "Failed to take the payment of a sale", e);
            Map<String, String> form = payForm(cash, mobileMoney, mobileMoneyRef, card, cardRef, bankTransfer, bankRef, credit);
            form.put("deposit", String.valueOf(deposit));
            redirect.addFlashAttribute("payForm", form);
            redirect.addFlashAttribute("payField", e.getField());
            // Credit above what the customer has left: the dialog offers to ask a manager (POS-05)
            redirect.addFlashAttribute("creditOver", e.getMessageKey().startsWith("sale.pay.credit.over"));
            return "redirect:/pos";
        }
    }

    // ---------------------------------------------------------------- helpers

    private void fail(RedirectAttributes redirect, String action, String what, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(MODULE, action, what + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    /** The search the cashier was on, kept across a POST (only its own parameters). */
    private static String query(String back) {
        if (back == null || back.isBlank() || !back.matches("[A-Za-z0-9=&%_.-]+")) {
            return "";
        }
        return "?" + back;
    }

    static Map<String, String> payForm(BigDecimal cash, BigDecimal mobileMoney, String mobileMoneyRef, BigDecimal card,
                                               String cardRef, BigDecimal bankTransfer, String bankRef, BigDecimal credit) {
        Map<String, String> form = new HashMap<>();
        form.put("cash", plain(cash));
        form.put("mobileMoney", plain(mobileMoney));
        form.put("mobileMoneyRef", mobileMoneyRef);
        form.put("card", plain(card));
        form.put("cardRef", cardRef);
        form.put("bankTransfer", plain(bankTransfer));
        form.put("bankRef", bankRef);
        form.put("credit", plain(credit));
        return form;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
