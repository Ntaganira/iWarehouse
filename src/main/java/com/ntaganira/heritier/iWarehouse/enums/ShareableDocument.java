package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ShareableDocument.java
 * - Date      : 2026. 10. 27.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A business document a customer, supplier or driver may read through a public receipt link
 *               (digital receipt sharing). The customer-facing sales documents and the purchasing papers a
 *               supplier needs; payroll, trip papers and internal stock papers are never shared.
 * </pre>
 */
public enum ShareableDocument {

    INVOICE("invoice", "share.doc.invoice", "share.button.receipt", "PAGE_INVOICES", "PERM_VIEW_INVOICE"),
    CREDIT_NOTE("credit-note", "share.doc.creditNote", "share.button.receipt", "PAGE_CREDIT_NOTES", "PERM_VIEW_CREDIT_NOTE"),
    QUOTATION("quotation", "share.doc.quotation", "share.button.quotation", "PAGE_QUOTATIONS", "PERM_VIEW_QUOTATION"),
    SALES_ORDER("sales-order", "share.doc.salesOrder", "share.button.order", "PAGE_SALES_ORDERS", "PERM_VIEW_SALES_ORDER"),
    DELIVERY_NOTE("delivery-note", "share.doc.deliveryNote", "share.button.delivery", "PAGE_DELIVERY_NOTES", "PERM_VIEW_DELIVERY_NOTE"),
    CUSTOMER_PAYMENT("customer-payment", "share.doc.customerPayment", "share.button.receipt", "PAGE_CUSTOMER_PAYMENTS", "PERM_VIEW_CUSTOMER_ACCOUNT"),
    GOODS_RECEIPT("goods-receipt", "share.doc.goodsReceipt", "share.button.document", "PAGE_RECEIVING", "PERM_VIEW_GOODS_RECEIPT"),
    PURCHASE_ORDER("purchase-order", "share.doc.purchaseOrder", "share.button.document", "PAGE_PROCUREMENT", "PERM_VIEW_PURCHASE_ORDER");

    private final String key;
    private final String labelKey;
    private final String buttonKey;
    private final String pageAuthority;
    private final String viewPermission;

    ShareableDocument(String key, String labelKey, String buttonKey, String pageAuthority, String viewPermission) {
        this.key = key;
        this.labelKey = labelKey;
        this.buttonKey = buttonKey;
        this.pageAuthority = pageAuthority;
        this.viewPermission = viewPermission;
    }

    /** The key used on the public address and in the message keys: invoice, credit-note, ... */
    public String key() {
        return key;
    }

    /** The document's name in the reader's language. */
    public String labelKey() {
        return labelKey;
    }

    /** The text of the action button on its page. */
    public String buttonKey() {
        return buttonKey;
    }

    /** The page code of the document's own screen: a user who may not open it may not share it. */
    public String pageAuthority() {
        return pageAuthority;
    }

    /** The view permission of the document's own screen. */
    public String viewPermission() {
        return viewPermission;
    }
}
