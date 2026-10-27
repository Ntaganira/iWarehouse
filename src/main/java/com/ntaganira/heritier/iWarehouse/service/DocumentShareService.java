package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import com.ntaganira.heritier.iWarehouse.enums.QuotationStatus;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.ShareableDocument;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.DocumentShareLinkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : DocumentShareService.java
 * - Date      : 2026. 10. 27.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Digital receipt sharing: one random public link per business document, and the read-only view of
 *               the document behind it. The Share button POSTs the document's type and id and gets its link
 *               through {@link #link(ShareableDocument, UUID)}; the public reader opens /r/{token} and sees only
 *               the document, never the rest of the system. The same view is rendered as a PDF. A document is shared only when its own status allows it, and the token alone is the key.
 * </pre>
 */
@Service("share")
@Transactional(readOnly = true)
public class DocumentShareService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DocumentShareLinkRepository repo;
    private final SalesService salesService;
    private final CreditNoteService creditNoteService;
    private final QuotationService quotationService;
    private final DeliveryNoteService deliveryNoteService;
    private final CustomerAccountService accountService;
    private final GoodsReceiptService goodsReceiptService;
    private final PurchaseOrderService purchaseOrderService;
    private final EbmService ebmService;
    private final SettingService settings;
    private final CompanyLogoService companyLogo;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;
    private final Clock clock;
    private final String appUrl;

    public DocumentShareService(DocumentShareLinkRepository repo, SalesService salesService, CreditNoteService creditNoteService,
                                QuotationService quotationService, DeliveryNoteService deliveryNoteService,
                                CustomerAccountService accountService, GoodsReceiptService goodsReceiptService,
                                PurchaseOrderService purchaseOrderService, EbmService ebmService, SettingService settings,
                                CompanyLogoService companyLogo, ActivityLogService activityLogService, Messages messages,
                                NumberFormats num, Clock clock, @Value("${app.url:http://localhost:8080}") String appUrl) {
        this.repo = repo;
        this.salesService = salesService;
        this.creditNoteService = creditNoteService;
        this.quotationService = quotationService;
        this.deliveryNoteService = deliveryNoteService;
        this.accountService = accountService;
        this.goodsReceiptService = goodsReceiptService;
        this.purchaseOrderService = purchaseOrderService;
        this.ebmService = ebmService;
        this.settings = settings;
        this.companyLogo = companyLogo;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
        this.clock = clock;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    /** What a page needs to offer a document: its link, the WhatsApp text and the button's label. */
    public record Share(String url, String whatsappUrl, String title, String message, String nativeText,
                        String intro, String label, String number, String buttonLabel) {
    }

    /** The read-only document a public reader sees. */
    public record PublicDocument(String title, String kind, String number, String status, String statusTone,
                                 LocalDateTime issuedAt, String partyLabel, String partyName, String partyTin,
                                 String referenceLabel, String reference, boolean showPrices, String currency,
                                 List<Row> rows, List<TotalRow> totals, List<PaymentRow> payments, List<String> notes,
                                 String ebmQrDataUri, String ebmNote, String companyName, String companyAddress,
                                 String companyPhone, String companyEmail, String companyTin, String logoDataUri,
                                 String pdfPath) {
    }

    /** One line: what it is, its size or unit, the quantity, the price and the amount. */
    public record Row(String description, String sub, String quantity, String price, String amount, boolean indent) {
    }

    /** A total: a label and its formatted amount or count. */
    public record TotalRow(String label, String value, boolean strong) {
    }

    /** A payment or refund: how it was made, its reference and its amount. */
    public record PaymentRow(String label, String reference, String amount) {
    }

    // ------------------------------------------------------------------ the link on an internal page

    /**
     * The document's public link, made once when its owner first asks to share it (the Share button POSTs the
     * document's type and id; a page never creates a public link just by being opened). The number kept on the
     * link is read from the document itself, never from the caller, and a missing document yields null.
     */
    @Transactional
    public Share link(ShareableDocument documentType, UUID documentId) {
        if (documentType == null || documentId == null) {
            return null;
        }
        String documentNumber = numberOf(documentType, documentId);
        if (documentNumber == null || documentNumber.isBlank() || !isShareable(documentType, documentId)) {
            return null;
        }
        final String ref = documentNumber;
        DocumentShareLink saved = repo.findByDocumentTypeAndDocumentId(documentType, documentId).orElseGet(() -> {
            DocumentShareLink created = new DocumentShareLink();
            created.setToken(newToken());
            created.setDocumentType(documentType);
            created.setDocumentId(documentId);
            created.setDocumentNumber(ref);
            activityLogService.record("DOCUMENTS", "CREATE_SHARE_LINK",
                    "Created a public share link for " + ref, ActivityStatus.SUCCESS);
            return repo.save(created);
        });
        String label = messages.get(documentType.labelKey());
        String company = settings.get(SettingKey.COMPANY_NAME);
        String url = appUrl + "/r/" + saved.getToken();
        String title = label + " " + documentNumber;
        String message = messages.get("share.message", label, documentNumber, company, url);
        String nativeText = messages.get("share.nativeText", label, documentNumber, company);
        String whatsapp = "https://api.whatsapp.com/send?text="
                + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
        return new Share(url, whatsapp, title, message, nativeText,
                messages.get("share.intro", label, documentNumber), label, documentNumber,
                messages.get(documentType.buttonKey()));
    }

    private boolean isShareable(ShareableDocument type, UUID id) {
        return switch (type) {
            case INVOICE -> salesService.findDetailed(id).getStatus() == SalesInvoiceStatus.POSTED;
            case CREDIT_NOTE, CUSTOMER_PAYMENT -> true;
            case QUOTATION, SALES_ORDER -> quotationService.findDetailed(id).getStatus() != QuotationStatus.CANCELLED;
            case DELIVERY_NOTE -> deliveryNoteService.findDetailed(id).getStatus() == DeliveryNoteStatus.RELEASED;
            case GOODS_RECEIPT -> goodsReceiptService.findDetailed(id).getStatus() == GoodsReceiptStatus.POSTED;
            case PURCHASE_ORDER -> purchaseOrderService.findDetailed(id).getStatus() != PurchaseOrderStatus.CANCELLED;
        };
    }
    private String numberOf(ShareableDocument type, UUID id) {
        return switch (type) {
            case INVOICE -> salesService.findDetailed(id).getNumber();
            case CREDIT_NOTE -> creditNoteService.findDetailed(id).getNumber();
            case QUOTATION, SALES_ORDER -> quotationService.findDetailed(id).getNumber();
            case DELIVERY_NOTE -> deliveryNoteService.findDetailed(id).getNumber();
            case CUSTOMER_PAYMENT -> accountService.findDetailed(id).getNumber();
            case GOODS_RECEIPT -> goodsReceiptService.findDetailed(id).getNumber();
            case PURCHASE_ORDER -> purchaseOrderService.findDetailed(id).getNumber();
        };
    }
    // ------------------------------------------------------------------ the public page

    @Transactional(readOnly = true)
    public PublicDocument open(String token) {
        DocumentShareLink link = repo.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Shared document", token));
        return switch (link.getDocumentType()) {
            case INVOICE -> invoice(link);
            case CREDIT_NOTE -> creditNote(link);
            case QUOTATION, SALES_ORDER -> quotation(link);
            case DELIVERY_NOTE -> deliveryNote(link);
            case CUSTOMER_PAYMENT -> customerPayment(link);
            case GOODS_RECEIPT -> goodsReceipt(link);
            case PURCHASE_ORDER -> purchaseOrder(link);
        };
    }

    private PublicDocument invoice(DocumentShareLink link) {
        SalesInvoice invoice = salesService.findDetailed(link.getDocumentId());
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED) {
            throw new NotFoundException("Shared document", link.getToken());
        }
        List<Row> rows = new ArrayList<>();
        for (SalesInvoiceLine line : invoice.getLines()) {
            if (line.isServiceLine()) {
                ProcessingService service = line.getService();
                rows.add(new Row(service.getName(),
                        line.getParentLine() == null ? "" : messages.get("public.onLine", line.getParentLine().getLineNo()),
                        num.m2(line.getServiceQuantity()) + " " + messages.get("sale.unit." + service.getChargeUnit()),
                        num.money(line.getServiceUnitPrice()), num.money(line.getAmount()), true));
            } else {
                String sub = line.getUnitCode() != null ? line.getUnitCode() : messages.get("public.cutToSize");
                if (line.getMark() != null) {
                    sub += " · " + line.getMark();
                }
                String quantity = (line.getQuantity() > 1 ? line.getQuantity() + " × " : "")
                        + num.m2(line.getChargeableAreaM2()) + " m²";
                rows.add(new Row(line.getProduct().getCode() + " " + line.getProduct().getThicknessLabel()
                        + " · " + line.getWidthMm() + " × " + line.getHeightMm() + " mm",
                        sub, quantity, num.money(line.getPricePerM2()), num.money(line.getAmount()), false));
            }
        }
        Vat.Totals vat = salesService.totals(invoice);
        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow(messages.get("public.net"), num.money(vat.net()), false));
        for (Vat.Group group : vat.groups()) {
            if (group.rate().signum() > 0) {
                totals.add(new TotalRow(messages.get("public.vatLine", group.taxCode(), num.percent(group.rate())),
                        num.money(group.vat()), false));
            }
        }
        totals.add(new TotalRow(messages.get("public.total"), num.money(vat.gross()), true));
        if (invoice.getAmountPaid() != null && invoice.getAmountPaid().signum() > 0) {
            totals.add(new TotalRow(messages.get("public.paid"), num.money(invoice.getAmountPaid()), false));
        }
        if (invoice.hasBalanceDue()) {
            totals.add(new TotalRow(messages.get("public.balanceDue"), num.money(invoice.getBalanceDue()), true));
        }
        List<PaymentRow> payments = new ArrayList<>();
        for (SalesPayment payment : salesService.payments(invoice.getId())) {
            payments.add(new PaymentRow(messages.get("payment.method." + payment.getMethod()),
                    payment.getReference(), num.money(payment.getAmount())));
        }
        List<String> notes = new ArrayList<>();
        List<CreditNote> creditNotes = creditNoteService.ofInvoice(invoice.getId());
        if (!creditNotes.isEmpty()) {
            notes.add(messages.get("public.credited", String.join(", ",
                    creditNotes.stream().map(CreditNote::getNumber).toList())));
        }
        if (invoice.hasBalanceDue()) {
            notes.add(messages.get("public.note.balanceDue", num.money(invoice.getBalanceDue())));
        }
        EbmReceipt ebm = ebmService.ofInvoice(invoice.getId()).orElse(null);
        return document(messages.get("public.kind.invoice"), invoice.getNumber(), statusOf(invoice),
                invoice.getPostedAt(), messages.get("public.party.customer"), invoice.getBillTo(),
                invoice.getBuyerTin() != null ? invoice.getBuyerTin() : invoice.getCustomer().getTin(),
                null, null, true, "RWF", rows, totals, payments, notes, ebm, link.getToken());
    }

    private PublicDocument creditNote(DocumentShareLink link) {
        CreditNote note = creditNoteService.findDetailed(link.getDocumentId());
        Map<UUID, SalesInvoiceLine> invoiceLines = new HashMap<>();
        for (SalesInvoiceLine line : note.getInvoice().getLines()) {
            invoiceLines.put(line.getId(), line);
        }
        List<Row> rows = new ArrayList<>();
        for (CreditNoteLine line : creditNoteService.lines(note.getId())) {
            SalesInvoiceLine invoiceLine = invoiceLines.get(line.getInvoiceLineId());
            String description = invoiceLine == null ? messages.get("public.creditLine", line.getLineNo())
                    : invoiceLine.getProduct().getCode() + " " + invoiceLine.getProduct().getThicknessLabel()
                    + " · " + invoiceLine.getWidthMm() + " × " + invoiceLine.getHeightMm() + " mm";
            String sub = invoiceLine == null ? "" : (invoiceLine.getUnitCode() != null ? invoiceLine.getUnitCode()
                    : messages.get("public.cutToSize"));
            rows.add(new Row(description, sub, line.getQuantity() + "", "",
                    num.money(line.getAmount()), false));
        }
        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow(messages.get("public.net"), num.money(note.getNetAmount()), false));
        if (note.getVatAmount().signum() > 0) {
            totals.add(new TotalRow(messages.get("public.vat"), num.money(note.getVatAmount()), false));
        }
        totals.add(new TotalRow(messages.get("public.total"), num.money(note.getTotalAmount()), true));
        if (note.getBalanceReduced().signum() > 0) {
            totals.add(new TotalRow(messages.get("public.balanceReduced"), num.money(note.getBalanceReduced()), false));
        }
        if (note.isRefunded()) {
            totals.add(new TotalRow(messages.get("public.refunded"), num.money(note.getRefundAmount()), false));
        }
        List<PaymentRow> payments = new ArrayList<>();
        if (note.isRefunded()) {
            payments.add(new PaymentRow(messages.get("creditNote.refund." + note.getRefundMethod()),
                    note.getRefundReference(), num.money(note.getRefundAmount())));
        }
        List<String> notes = new ArrayList<>();
        notes.add(messages.get("public.note.reason", note.getReason()));
        if (note.isRefunded()) {
            notes.add(messages.get("public.note.refunded", messages.get("creditNote.refund." + note.getRefundMethod())));
        }
        EbmReceipt ebm = ebmService.ofCreditNote(note.getId()).orElse(null);
        return document(messages.get("public.kind.creditNote"), note.getNumber(),
                new Status(messages.get("public.status.issued"), "success"), note.getPostedAt(),
                messages.get("public.party.customer"), note.getCustomer().getName(), note.getCustomer().getTin(),
                messages.get("public.reference.invoice"), note.getInvoice().getNumber(), true, "RWF",
                rows, totals, payments, notes, ebm, link.getToken());
    }

    private PublicDocument quotation(DocumentShareLink link) {
        Quotation quotation = quotationService.findDetailed(link.getDocumentId());
        if (quotation.getStatus() == QuotationStatus.CANCELLED) {
            throw new NotFoundException("Shared document", link.getToken());
        }
        List<Row> rows = new ArrayList<>();
        for (QuotationLine line : quotation.getLines()) {
            if (line.isServiceLine()) {
                ProcessingService service = line.getService();
                rows.add(new Row(service.getName(),
                        line.getParentLine() == null ? "" : messages.get("public.onLine", line.getParentLine().getLineNo()),
                        num.m2(line.getServiceQuantity()) + " " + messages.get("sale.unit." + service.getChargeUnit()),
                        num.money(line.getPrice()), num.money(line.getAmount()), true));
            } else {
                String description = (line.isSheet() ? messages.get("public.wholeSheet") + " · " : messages.get("public.cutToSize") + " · ")
                        + line.getProduct().getCode() + " " + line.getProduct().getThicknessLabel()
                        + " · " + line.getWidthMm() + " × " + line.getHeightMm() + " mm";
                String sub = line.getMark() != null ? line.getMark() : "";
                String quantity = (line.getQuantity() > 1 ? line.getQuantity() + " × " : "")
                        + num.m2(line.getChargeableAreaM2()) + " m²";
                rows.add(new Row(description, sub, quantity, num.money(line.getPrice()), num.money(line.getAmount()), false));
            }
        }
        Vat.Totals vat = quotationService.totals(quotation);
        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow(messages.get("public.net"), num.money(vat.net()), false));
        for (Vat.Group group : vat.groups()) {
            if (group.rate().signum() > 0) {
                totals.add(new TotalRow(messages.get("public.vatLine", group.taxCode(), num.percent(group.rate())),
                        num.money(group.vat()), false));
            }
        }
        totals.add(new TotalRow(messages.get("public.total"), num.money(vat.gross()), true));
        List<String> notes = new ArrayList<>();
        if (quotation.getNotes() != null && !quotation.getNotes().isBlank()) {
            notes.add(quotation.getNotes());
        }
        if (quotation.getStatus() == QuotationStatus.SENT) {
            notes.add(messages.get("public.note.validUntil", DATE.format(quotation.getValidUntil())));
        }
        Status status = quotation.isExpiredOn(today())
                ? new Status(messages.get("public.status.expired"), "warning")
                : switch (quotation.getStatus()) {
                    case DRAFT -> new Status(messages.get("public.status.draft"), "secondary");
                    case SENT -> new Status(messages.get("public.status.sent"), "info");
                    case CONVERTED -> new Status(messages.get("public.status.converted"), "success");
                    case CANCELLED -> throw new NotFoundException("Shared document", link.getToken());
                };
        boolean order = quotation.isOrder();
        return document(messages.get(order ? "public.kind.salesOrder" : "public.kind.quotation"),
                quotation.getNumber(), status,
                quotation.getSentAt() != null ? quotation.getSentAt() : quotation.getQuoteDate().atStartOfDay(),
                messages.get("public.party.customer"), quotation.getBillTo(),
                quotation.getBuyerTin() != null ? quotation.getBuyerTin() : quotation.getCustomer().getTin(),
                messages.get("public.reference.validUntil"), DATE.format(quotation.getValidUntil()), true, "RWF",
                rows, totals, List.of(), notes, null, link.getToken());
    }

    private PublicDocument deliveryNote(DocumentShareLink link) {
        DeliveryNote note = deliveryNoteService.findDetailed(link.getDocumentId());
        if (note.getStatus() != DeliveryNoteStatus.RELEASED) {
            throw new NotFoundException("Shared document", link.getToken());
        }
        List<Row> rows = new ArrayList<>();
        for (DeliveryNoteLine line : deliveryNoteService.lines(note.getId())) {
            rows.add(new Row(line.getWidthMm() + " × " + line.getHeightMm() + " mm",
                    line.getUnitCode() + " · " + messages.get(line.getKind() == com.ntaganira.heritier.iWarehouse.enums.DeliveryLineKind.SHEET
                            ? "public.deliverySheet" : "public.deliveryPiece"),
                    "1", "", "", false));
        }
        List<String> notes = new ArrayList<>();
        if (note.getDestination() != null && !note.getDestination().isBlank()) {
            notes.add(messages.get("public.note.destination", note.getDestination()));
        }
        notes.add(messages.get("public.note.transport", note.isCollected() ? messages.get("public.collected")
                : (note.getVehiclePlate() + " · " + note.getDriverName())));
        if (note.getShortReason() != null && !note.getShortReason().isBlank()) {
            notes.add(messages.get("public.note.short", note.getShortReason()));
        }
        return document(messages.get("public.kind.deliveryNote"), note.getNumber(),
                new Status(messages.get("public.status.released"), "success"),
                note.getReleasedAt() != null ? note.getReleasedAt() : note.getPreparedAt(),
                messages.get("public.party.customer"), note.getInvoice().getBillTo(), note.getInvoice().getBuyerTin(),
                messages.get("public.reference.invoice"), note.getInvoice().getNumber(), false, "RWF",
                rows, List.of(), List.of(), notes, null, link.getToken());
    }

    private PublicDocument customerPayment(DocumentShareLink link) {
        CustomerPayment payment = accountService.findDetailed(link.getDocumentId());
        List<Row> rows = List.of(new Row(messages.get("public.paymentOnAccount"),
                messages.get("payment.method." + payment.getMethod())
                        + (payment.getReference() != null ? " · " + payment.getReference() : ""),
                "", "", num.money(payment.getAmount()), false));
        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow(messages.get("public.received"), num.money(payment.getAmount()), true));
        if (payment.getCashTendered() != null) {
            totals.add(new TotalRow(messages.get("public.cashTendered"), num.money(payment.getCashTendered()), false));
            totals.add(new TotalRow(messages.get("public.change"), num.money(payment.getChange()), false));
        }
        totals.add(new TotalRow(messages.get("public.accountBalance"),
                num.money(accountService.owed(payment.getCustomer().getId())), false));
        List<String> notes = new ArrayList<>();
        if (payment.getNotes() != null && !payment.getNotes().isBlank()) {
            notes.add(payment.getNotes());
        }
        return document(messages.get("public.kind.customerPayment"), payment.getNumber(),
                new Status(messages.get("public.status.received"), "success"), payment.getPostedAt(),
                messages.get("public.party.customer"), payment.getCustomer().getName(), payment.getCustomer().getTin(),
                messages.get("public.reference.method"), messages.get("payment.method." + payment.getMethod()),
                true, "RWF", rows, totals, List.of(), notes, null, link.getToken());
    }

    private PublicDocument goodsReceipt(DocumentShareLink link) {
        GoodsReceipt receipt = goodsReceiptService.findDetailed(link.getDocumentId());
        if (receipt.getStatus() != GoodsReceiptStatus.POSTED) {
            throw new NotFoundException("Shared document", link.getToken());
        }
        String currency = receipt.getCurrencyCode();
        List<Row> rows = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        int sheets = 0;
        int broken = 0;
        for (CrateBatch crate : receipt.getCrates()) {
            BigDecimal area = Pricing.areaM2(crate.getWidthMm(), crate.getHeightMm())
                    .multiply(BigDecimal.valueOf(crate.getSheets()));
            BigDecimal lineAmount = area.multiply(crate.getCostPerM2()).setScale(2, RoundingMode.HALF_UP);
            amount = amount.add(lineAmount);
            sheets += crate.getSheets();
            broken += crate.getBroken();
            rows.add(new Row(crate.getProduct().getCode() + " " + crate.getProduct().getThicknessLabel()
                    + " · " + crate.getWidthMm() + " × " + crate.getHeightMm() + " mm",
                    crate.getBatchNo() + (crate.getLocation() != null ? " · " + crate.getLocation().getName() : ""),
                    crate.getSheets() + " × " + num.m2(Pricing.areaM2(crate.getWidthMm(), crate.getHeightMm())) + " m²",
                    num.price(crate.getCostPerM2()), num.money(lineAmount), false));
        }
        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow(messages.get("public.crates"), String.valueOf(receipt.getCrates().size()), false));
        totals.add(new TotalRow(messages.get("public.sheets"), String.valueOf(sheets), false));
        if (broken > 0) {
            totals.add(new TotalRow(messages.get("public.broken"), String.valueOf(broken), false));
        }
        totals.add(new TotalRow(messages.get("public.total"), num.money(amount) + " " + currency, true));
        List<String> notes = new ArrayList<>();
        if (receipt.getDeliveryRef() != null) {
            notes.add(messages.get("public.note.deliveryRef", receipt.getDeliveryRef()));
        }
        if (receipt.getVehiclePlate() != null) {
            notes.add(messages.get("public.note.transport", receipt.getVehiclePlate()
                    + (receipt.getDriverName() != null ? " · " + receipt.getDriverName() : "")));
        }
        if (receipt.getNotes() != null && !receipt.getNotes().isBlank()) {
            notes.add(receipt.getNotes());
        }
        Supplier supplier = receipt.getPurchaseOrder().getSupplier();
        return document(messages.get("public.kind.goodsReceipt"), receipt.getNumber(),
                new Status(messages.get("public.status.posted"), "success"),
                receipt.getPostedAt() != null ? receipt.getPostedAt() : receipt.getReceivedDate().atStartOfDay(),
                messages.get("public.party.supplier"), supplier.getName(), supplier.getTin(),
                messages.get("public.reference.purchaseOrder"), receipt.getPurchaseOrder().getNumber(), true, currency,
                rows, totals, List.of(), notes, null, link.getToken());
    }

    private PublicDocument purchaseOrder(DocumentShareLink link) {
        PurchaseOrder order = purchaseOrderService.findDetailed(link.getDocumentId());
        if (order.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new NotFoundException("Shared document", link.getToken());
        }
        String currency = order.getCurrencyCode();
        List<Row> rows = new ArrayList<>();
        for (PurchaseOrderLine line : order.getLines()) {
            BigDecimal amount = PurchaseOrders.lineAmount(line.getQuantity(), line.getWidthMm(), line.getHeightMm(),
                    line.getPricePerM2()).setScale(2, RoundingMode.HALF_UP);
            rows.add(new Row(line.getProduct().getCode() + " " + line.getProduct().getThicknessLabel()
                    + " · " + line.getWidthMm() + " × " + line.getHeightMm() + " mm", "",
                    line.getQuantity() + " × " + num.m2(Pricing.areaM2(line.getWidthMm(), line.getHeightMm())) + " m²",
                    num.price(line.getPricePerM2()), num.money(amount), false));
        }
        PurchaseOrders.Totals totalsValue = purchaseOrderService.totals(order);
        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow(messages.get("public.sheets"), String.valueOf(totalsValue.sheets()), false));
        totals.add(new TotalRow(messages.get("public.area"), num.m2(totalsValue.areaM2()) + " m²", false));
        totals.add(new TotalRow(messages.get("public.total"), num.money(totalsValue.amount()) + " " + currency, true));
        List<String> notes = new ArrayList<>();
        if (order.getIncoterm() != null) {
            notes.add(messages.get("public.note.incoterm", order.getIncoterm().name()));
        }
        if (order.getSupplierRef() != null && !order.getSupplierRef().isBlank()) {
            notes.add(messages.get("public.note.supplierRef", order.getSupplierRef()));
        }
        if (order.getNotes() != null && !order.getNotes().isBlank()) {
            notes.add(order.getNotes());
        }
        Status status = switch (order.getStatus()) {
            case DRAFT -> new Status(messages.get("public.status.draft"), "secondary");
            case PENDING_APPROVAL -> new Status(messages.get("public.status.pendingApproval"), "warning");
            case ORDERED -> new Status(messages.get("public.status.ordered"), "info");
            case PARTIALLY_RECEIVED -> new Status(messages.get("public.status.partiallyReceived"), "warning");
            case RECEIVED -> new Status(messages.get("public.status.received"), "success");
            case CLOSED -> new Status(messages.get("public.status.closed"), "secondary");
            case CANCELLED -> throw new NotFoundException("Shared document", link.getToken());
        };
        Supplier supplier = order.getSupplier();
        return document(messages.get("public.kind.purchaseOrder"), order.getNumber(), status,
                order.getOrderedAt() != null ? order.getOrderedAt() : order.getOrderDate().atStartOfDay(),
                messages.get("public.party.supplier"), supplier.getName(), supplier.getTin(),
                messages.get("public.reference.expected"),
                order.getExpectedDate() == null ? null : DATE.format(order.getExpectedDate()), true, currency,
                rows, totals, List.of(), notes, null, link.getToken());
    }

    // ------------------------------------------------------------------ helpers

    private record Status(String label, String tone) {
    }

    private PublicDocument document(String kind, String number, Status status, LocalDateTime issuedAt,
                                    String partyLabel, String partyName, String partyTin,
                                    String referenceLabel, String reference, boolean showPrices, String currency,
                                    List<Row> rows, List<TotalRow> totals, List<PaymentRow> payments, List<String> notes,
                                    EbmReceipt ebm, String token) {
        String company = settings.get(SettingKey.COMPANY_NAME);
        String ebmNote = null;
        String ebmQr = null;
        if (ebm != null) {
            if (ebm.isSigned()) {
                ebmNote = messages.get("public.ebm.signed", ebm.getReceiptLabel(), ebm.getInvcNo());
                if (ebm.isSimulated()) {
                    ebmNote += " · " + messages.get("posReceipt.notFiscal");
                }
                String data = ebmService.qrData(ebm);
                ebmQr = data == null ? null : Labels.qrPngDataUri(data, 4);
            } else {
                ebmNote = messages.get("public.ebm.unsigned");
            }
        }
        return new PublicDocument(kind + " " + number, kind, number, status.label(), status.tone(), issuedAt,
                partyLabel, partyName, partyTin, referenceLabel, reference, showPrices, currency,
                rows, totals, payments, notes, ebmQr, ebmNote, company,
                settings.get(SettingKey.COMPANY_ADDRESS), settings.get(SettingKey.COMPANY_PHONE),
                settings.get(SettingKey.COMPANY_EMAIL), settings.get(SettingKey.COMPANY_TIN),
                companyLogo.dataUri(), "/r/" + token + "/pdf");
    }

    private Status statusOf(SalesInvoice invoice) {
        return invoice.hasBalanceDue()
                ? new Status(messages.get("public.status.balanceDue"), "warning")
                : new Status(messages.get("public.status.paid"), "success");
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private String newToken() {
        while (true) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!repo.existsByToken(token)) {
                return token;
            }
        }
    }
}
