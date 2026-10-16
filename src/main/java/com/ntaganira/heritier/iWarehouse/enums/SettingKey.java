package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : SettingKey.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Every configurable setting (ADM-03): its row key in the settings table, the matching
 *               SettingsDto property and the fallback used if the row is missing. A new setting is a
 *               new constant here, a field on SettingsDto and a seed row in its module's migration.
 * </pre>
 */
public enum SettingKey {

    COMPANY_NAME("company.name", "companyName", "iWarehouse"),
    COMPANY_TIN("company.tin", "companyTin", null),
    COMPANY_ADDRESS("company.address", "companyAddress", null),
    COMPANY_PHONE("company.phone", "companyPhone", null),
    COMPANY_EMAIL("company.email", "companyEmail", null),
    /**
     * The company's logo on documents: its storage key (CompanyLogoService). A file, not a form value: it has no SettingsDto
     * property (null), so the settings form neither shows nor saves it; it is uploaded and removed on its own.
     */
    COMPANY_LOGO("company.logo", null, null),
    /** Branch used when a document is numbered without an explicit branch (MD-07). */
    BRANCH_CODE("company.branch-code", "branchCode", "WH"),

    /** Leftovers at least this big (m2) become off-cut units (PRD-04). */
    OFFCUT_MIN_AREA("production.offcut.min-area", "offcutMinArea", "0.25"),
    /** ...and with both sides at least this long (mm) (PRD-04). */
    OFFCUT_MIN_SIDE("production.offcut.min-side", "offcutMinSide", "300"),
    /** kg per m2 per mm of thickness: weight = area x thickness x density. */
    GLASS_DENSITY("production.glass-density", "glassDensity", "2.5"),
    /** Glass held longer than this many days is slow-moving (RPT-02). */
    SLOW_MOVING_DAYS("stock.slow-moving-days", "slowMovingDays", "90"),

    /** Default minimum chargeable area per piece (m2) for new price lists (MD-06). */
    MIN_CHARGEABLE_AREA("pricing.min-chargeable-area", "minChargeableArea", "0.25"),
    /** Days a quotation's prices hold by default (POS-03). */
    QUOTATION_VALIDITY_DAYS("sales.quotation-validity-days", "quotationValidityDays", "14"),

    /** The smallest deposit on an order, % of its total (POS-08); the glass taken at once is paid in full. */
    DEPOSIT_MIN_PERCENT("sales.deposit-min-percent", "depositMinPercent", "50"),

    /** Stock adjustments worth more than this (RWF) need supervisor approval (INV-07). */
    ADJUSTMENT_APPROVAL_LIMIT("approval.adjustment-limit", "adjustmentApprovalLimit", "0"),
    /** RWF above which a vehicle cost waits for another person's approval before it posts. */
    VEHICLE_COST_APPROVAL_LIMIT("approval.vehicle-cost-limit", "vehicleCostApprovalLimit", "100000"),
    /** Discounts and price overrides above this percentage need manager approval (POS-06). */
    DISCOUNT_APPROVAL_PERCENT("approval.discount-limit-percent", "discountApprovalPercent", "0"),
    /** A discount that takes a line below what its glass cost needs approval (SDP 8). */
    APPROVAL_BELOW_COST("approval.below-cost", "approvalBelowCost", "true"),
    /** Counter exceptions are recommended (Director of Commercial) before they are decided (F-03). */
    APPROVAL_SALE_RECOMMEND("approval.sale-recommend", "approvalSaleRecommend", "false"),
    /** Customers with invoices overdue get no credit until a manager approves it (SDP 11). */
    CREDIT_BLOCK_OVERDUE("credit.block-overdue", "creditBlockOverdue", "true"),
    /** Days past due before an invoice blocks credit. */
    CREDIT_OVERDUE_GRACE_DAYS("credit.overdue-grace-days", "creditOverdueGraceDays", "0"),
    /** A credit note is asked, approved by another person, then issued (F-14); off: issued at once, as before. */
    APPROVAL_CREDIT_NOTE_REQUIRED("approval.credit-note-required", "approvalCreditNoteRequired", "true"),
    /** A credit note asked is reviewed (Finance) before it is approved. */
    APPROVAL_CREDIT_NOTE_REVIEW("approval.credit-note-review", "approvalCreditNoteReview", "true"),
    /** A receivable write-off asked is reviewed before it is approved. */
    APPROVAL_WRITE_OFF_REVIEW("approval.write-off-review", "approvalWriteOffReview", "true"),
    /** RWF above which a purchase order waits for another person's approval (CMP 06); 0: every order. */
    APPROVAL_PURCHASE_ORDER_LIMIT("approval.purchase-order-limit", "approvalPurchaseOrderLimit", "0"),
    /** A supplier payment is prepared, then authorised by another person (CMP 07); off: paid at once, as before. */
    APPROVAL_SUPPLIER_PAYMENT_AUTHORISE("approval.supplier-payment-authorise", "approvalSupplierPaymentAuthorise", "true"),
    /** RWF above which a supplier payment needs two authorisations (CMP 07: two signatories above 500,000). */
    APPROVAL_SUPPLIER_PAYMENT_DUAL_LIMIT("approval.supplier-payment-dual-limit", "approvalSupplierPaymentDualLimit", "500000"),
    /** A goods receipt is posted by someone other than who drafted it (F-06). */
    APPROVAL_RECEIPT_SECOND_PERSON("approval.receipt-second-person", "approvalReceiptSecondPerson", "true"),
    /** Credit terms change only through an approved credit application (F-02 C-D). */
    APPROVAL_CREDIT_APPLICATION("approval.credit-application", "approvalCreditApplication", "true"),
    /** The Director of Finance reviews a credit application after Credit Control assessed it (F-02 C). */
    APPROVAL_CREDIT_APPLICATION_REVIEW("approval.credit-application-review", "approvalCreditApplicationReview", "true"),
    /** Months after which an approved credit limit is reviewed (F-02 C). */
    CREDIT_REVIEW_MONTHS("credit.review-months", "creditReviewMonths", "12"),
    /** Days overdue after which a customer's credit limit is reviewed at once (F-02 C). */
    CREDIT_REVIEW_OVERDUE_DAYS("credit.review-overdue-days", "creditReviewOverdueDays", "90"),
    /** Prices change only once another person approves them (SDP 5). */
    APPROVAL_PRICE_CHANGE("approval.price-change", "approvalPriceChange", "true"),
    /** A new supplier is approved before it is ordered from or paid (CMP 08). */
    APPROVAL_SUPPLIER_ONBOARDING("approval.supplier-onboarding", "approvalSupplierOnboarding", "true"),
    /** References a supplier needs before it is approved (CMP 08). */
    SUPPLIER_REFERENCES_REQUIRED("supplier.references-required", "supplierReferencesRequired", "2"),
    /** Stock movements but sales get a Transaction Ticket (decision D6). */
    TICKET_ENABLED("ticket.enabled", "ticketEnabled", "true"),
    /** A goods receipt posts only from its countersigned ticket (Annex I step 4). */
    TICKET_GATE_RECEIPTS("ticket.gate-receipts", "ticketGateReceipts", "true"),
    /** Tickets are raised for movements from this day (yyyy-MM-dd): earlier ones were never ticketed. */
    TICKET_SINCE("ticket.since", null, null),
    /** A transfer between sites is approved before it leaves (IMP 11.1). */
    TRANSFER_APPROVAL("transfer.approval", "transferApproval", "true"),
    /** Hours after dispatch when labels not received are a difference (IMP 11.2). */
    TRANSFER_DIFFERENCE_HOURS("transfer.difference-hours", "transferDifferenceHours", "24"),
    /** Stock lost or found above this % of the stock value is flagged on the monthly reconciliation (BP 7.4). */
    RECONCILIATION_TOLERANCE_PERCENT("reconciliation.tolerance-percent", "reconciliationTolerancePercent", "0.5"),
    /** The till takes payment only for orders the Order Desk raised (F-01, decision D4). */
    SALES_ORDER_DESK("sales.order-desk", "salesOrderDesk", "true"),
    /** An order not paid within this many days lapses. */
    SALES_ORDER_VALID_DAYS("sales.order-valid-days", "salesOrderValidDays", "3"),
    /** Goods leave only on a delivery note released by the warehouse (F-04). */
    SALES_RELEASE_BY_NOTE("sales.release-by-note", "releaseByNote", "true"),
    /** No goods before the EBM receipt is signed, unless overridden with a reason (decision D3). */
    EBM_BEFORE_RELEASE("ebm.before-release", "ebmBeforeRelease", "true"),
    /** Working days for the signed delivery note to come back (IMP 9.3). */
    DELIVERY_SIGNED_COPY_DAYS("delivery.signed-copy-days", "signedCopyDays", "3"),
    /** Cut pieces are verified by another person before release (IMP 10 step 3). */
    CUTTING_VERIFY("cutting.verify", "cuttingVerify", "true"),

    // --- The daily close, cash and receivables (M14)
    /** The RWF notes and coins counted at the till, largest first (F-13). */
    CASH_DENOMINATIONS("cash.denominations", "cashDenominations", "5000,2000,1000,500,100,50,20,10,5,1"),
    /** Treasury verifies a till's count before its cash is banked (F-13). */
    CASH_VERIFY_COUNT("cash.verify-count", "cashVerifyCount", "true"),
    /** The day's close is prepared by this time the next day (BP 5.3). HH:mm. */
    CLOSE_DAILY_REPORT_BY("close.daily-report-by", "dailyReportBy", "10:00"),
    /** Bank and MoMo accounts are reconciled by this working day of the month (BP Table 9). */
    RECONCILIATION_DUE_WORKING_DAY("reconciliation.due-working-day", "reconciliationDueWorkingDay", "5"),
    /** Goods paid by bank transfer leave once Finance confirms the money arrived (SDP 14). */
    RELEASE_TRANSFER_CONFIRMED("release.transfer-confirmed", "releaseTransferConfirmed", "true"),
    /** The share of receivables more than 90 days overdue is kept below this (Annex D). */
    CREDIT_OVER90_MAX_PERCENT("credit.over90-max-percent", "creditOver90MaxPercent", "5"),
    /** Days of credit sales the days sales outstanding is worked out on. */
    CREDIT_DSO_DAYS("credit.dso-days", "creditDsoDays", "90"),
    /** The month's accounts are ready by this working day of the next month (BP 5.3). */
    CALENDAR_ACCOUNTS_WORKING_DAY("calendar.accounts-working-day", "accountsWorkingDay", "10"),

    // --- The field cycle (M15)
    /** The load request is approved before the vehicle leaves (F-09). */
    FLEET_LOAD_APPROVAL("fleet.load-approval", "fleetLoadApproval", "true"),
    /** No new load while the driver's or the vehicle's last trip is not cleared (BP 7.3 rule 9). */
    FLEET_PREVIOUS_CLEARED("fleet.previous-cleared", "fleetPreviousCleared", "true"),
    /** The clearance is countersigned by the Director of Finance, which closes the trip (F-11). */
    FLEET_CLEARANCE_COUNTERSIGN("fleet.clearance-countersign", "fleetClearanceCountersign", "true"),
    /** Vehicle costs may be paid from a trip's float up to this day; blank = always (decision D9). yyyy-MM-dd. */
    FLEET_COSTS_FROM_FLOAT_UNTIL("fleet.costs-from-float-until", "fleetCostsFromFloatUntil", "2026-12-31"),
    /** Van stock shortages are kept within this % of the value loaded (Annex D). */
    FLEET_SHORTAGE_TOLERANCE_PERCENT("fleet.shortage-tolerance-percent", "fleetShortageTolerancePercent", "0.5"),
    /** The phone sells on credit to approved customers within their limit (decision D8). */
    MOBILE_FIELD_CREDIT("mobile.field-credit", "mobileFieldCredit", "true"),

    // Damage, counts and variances (M16: IMP 13, 15, 16; F-07, F-08; decisions D5, D14)
    /** A damage report is inspected by Dispatch before it is decided (F-07). */
    DAMAGE_INSPECTION("damage.inspection", "damageInspection", "true"),
    /** Damage worth this much or more (RWF) is told the same day to the Internal Controller and Security (F-07, Board tolerance). */
    DAMAGE_ALERT_VALUE("damage.alert-value", "damageAlertValue", "500000"),
    /** The first count is blind: what is expected is not shown until the count closes (CSH-012, D14). */
    COUNT_BLIND("count.blind", "countBlind", "true"),
    /** Count types that always get a second, independent count (D14). Comma-separated StockCountType names. */
    COUNT_SECOND_TYPES("count.second-types", "countSecondTypes", "ANNUAL,SURPRISE"),
    /** A second count wherever the first finds differences (D14). */
    COUNT_SECOND_ON_DIFFERENCES("count.second-on-differences", "countSecondOnDifferences", "true"),
    /** A variance above this % of the count's value is investigated (IMP 14.3, 16.2). */
    COUNT_VARIANCE_TOLERANCE_PERCENT("count.variance-tolerance-percent", "countVarianceTolerancePercent", "0.5"),
    /** From this % of the count's value a variance is moderate (VR-010). */
    COUNT_VARIANCE_MODERATE_PERCENT("count.variance-moderate-percent", "countVarianceModeratePercent", "0.5"),
    /** From this % a variance is material and told to the Board (VR-010, BP Table 9). */
    COUNT_VARIANCE_MATERIAL_PERCENT("count.variance-material-percent", "countVarianceMaterialPercent", "2"),
    /** A variance is resolved within this many working days, then escalated (IMP 14.2). */
    COUNT_VARIANCE_DAYS("count.variance-days", "countVarianceDays", "5"),

    // Inventory reports and KPIs (M16: Inventory Policy 13, 19; Annex D)
    /** Glass held longer than this many days is obsolete (IMP 13): listed apart on the slow-moving report. */
    STOCK_OBSOLETE_DAYS("stock.obsolete-days", "stockObsoleteDays", "365"),
    /** Inventory accuracy kept at or above this % (IMP 19). */
    KPI_ACCURACY_TARGET("kpi.accuracy-target", "kpiAccuracyTarget", "99"),
    /** Consecutive months below the accuracy target that call for a formal review (IMP 19: two). */
    KPI_ACCURACY_MONTHS("kpi.accuracy-months", "kpiAccuracyMonths", "2"),
    /** Stock lost kept below this % of the inventory value (IMP 19). */
    KPI_LOSS_MAX_PERCENT("kpi.loss-max-percent", "kpiLossMaxPercent", "0.5"),
    /** Damaged stock kept below this % of the inventory value (IMP 19). */
    KPI_DAMAGE_MAX_PERCENT("kpi.damage-max-percent", "kpiDamageMaxPercent", "1"),
    /** Inventory turnover aimed at, times a year (IMP 19: set by management); blank = no target. */
    KPI_TURNOVER_TARGET("kpi.turnover-target", "kpiTurnoverTarget", null),
    /** Deliveries without errors or shortages kept at or above this % (IMP 19). */
    KPI_DELIVERY_ACCURACY_TARGET("kpi.delivery-accuracy-target", "kpiDeliveryAccuracyTarget", "98"),
    /** Reconciliations done on time aimed at, % (IMP 19: 100). */
    KPI_RECONCILIATION_TARGET("kpi.reconciliation-target", "kpiReconciliationTarget", "100"),
    /** Variances resolved within their working days aimed at, % (IMP 19: 100). */
    KPI_VARIANCE_RESOLUTION_TARGET("kpi.variance-resolution-target", "kpiVarianceResolutionTarget", "100"),
    /** Days from payment to the last hand-over aimed at (Annex D: the agreed target). */
    KPI_ORDER_DAYS_TARGET("kpi.order-days-target", "kpiOrderDaysTarget", "3"),

    // Expenditure control (M17: Cost Management & Expenditure Control Policy 05, 06, 09)
    /** An approved requisition comes before any order or purchase bill (CMP 06). */
    EXPENSE_REQUISITION_REQUIRED("expense.requisition-required", "expenseRequisitionRequired", "true"),
    /** From this amount (RWF) an order or a purchase bill needs a requisition; 0: always (CMP 06). */
    EXPENSE_REQUISITION_FROM("expense.requisition-from", "expenseRequisitionFrom", "0"),
    /** An order or bill may exceed its requisition's chosen quote by at most this % (CMP 06). */
    EXPENSE_REQUISITION_TOLERANCE_PERCENT("expense.requisition-tolerance-percent", "expenseRequisitionTolerancePercent", "10"),
    /** From this amount three written quotes (CMP 06: 100K). */
    EXPENSE_QUOTES_THREE_FROM("expense.quotes-three-from", "expenseQuotesThreeFrom", "100000"),
    /** From this amount a formal request for quotation (CMP 06: 1M). */
    EXPENSE_RFQ_FROM("expense.rfq-from", "expenseRfqFrom", "1000000"),
    /** From this amount a tender (CMP 06: 5M). */
    EXPENSE_TENDER_FROM("expense.tender-from", "expenseTenderFrom", "5000000"),
    /** Each quote needed is kept as a document on the requisition (CMP 06: written quotes). */
    EXPENSE_QUOTE_DOCUMENTS("expense.quote-documents", "expenseQuoteDocuments", "true"),
    /** An expense bill is submitted with the supplier's original invoice as a document (CMP 07). */
    EXPENSE_INVOICE_DOCUMENT("expense.invoice-document", "expenseInvoiceDocument", "true"),
    /** The head of department recommends an expense bill before it is approved (CMP 05). */
    EXPENSE_RECOMMEND("expense.recommend", "expenseRecommend", "true"),
    /** From this amount Finance reviews an expense bill before it is approved (CMP 05: 500K). */
    EXPENSE_FINANCE_REVIEW_FROM("expense.finance-review-from", "expenseFinanceReviewFrom", "500000"),
    /** Orders and bills to one supplier within this many days are looked at together for splitting (CMP 06, 09). */
    EXPENSE_SPLIT_DAYS("expense.split-days", "expenseSplitDays", "7"),
    /** Days the Board has to ratify emergency spending before it is reported (CMP 05). */
    EXPENSE_RATIFY_DAYS("expense.ratify-days", "expenseRatifyDays", "10"),
    /** The cost centre vehicle costs are posted to (F-15 A). */
    EXPENSE_FLEET_COST_CENTRE("expense.fleet-cost-centre", "expenseFleetCostCentre", "SC"),

    /** Rate source documents use unless they ask for another (ACC-02). A RateSource name. */
    DEFAULT_RATE_SOURCE("currency.default-rate-source", "defaultRateSource", "BNR"),
    /** Documents refuse a rate older than this many days, so a forgotten update is noticed (ACC-02). */
    MAX_RATE_AGE_DAYS("currency.max-rate-age-days", "maxRateAgeDays", "7"),

    /** Alerts go by email too (RPT-06), when a mail account is configured. */
    ALERT_EMAIL("alerts.email", "alertEmail", "false"),
    /** Days before a driving licence, insurance or inspection expires that the fleet is told (FLT-04). */
    FLEET_EXPIRY_ALERT_DAYS("fleet.expiry-alert-days", "fleetExpiryAlertDays", "30"),
    /** Hours after a trip came back that a float not cleared is reported to the accountant (RPT-06). */
    FLOAT_ALERT_HOURS("fleet.float-alert-hours", "floatAlertHours", "24"),
    /** Km before a vehicle's service falls due that the fleet is told (the days are FLEET_EXPIRY_ALERT_DAYS). */
    SERVICE_ALERT_KM("fleet.service-alert-km", "serviceAlertKm", "500"),

    /** How receipts are signed (TAX-02): SIMULATOR (development: receipts marked not fiscal) or VSDC. An EbmMode name. */
    EBM_MODE("ebm.mode", "ebmMode", "SIMULATOR"),
    /** The business's VSDC (RRA's WAR on its own server), e.g. http://localhost:8088/rravsdc. */
    EBM_VSDC_URL("ebm.vsdc-url", "ebmVsdcUrl", null),
    /** The branch RRA registered the device for (bhfId): 00 is the head office. */
    EBM_BRANCH_ID("ebm.branch-id", "ebmBranchId", "00"),
    /** The device serial RRA approved (dvcSrlNo), sent when the device is initialised. */
    EBM_DEVICE_SERIAL("ebm.device-serial", "ebmDeviceSerial", null),
    /** RRA item classification of glass (itemClsCd, from the VSDC's classification list). */
    EBM_GLASS_ITEM_CLASS("ebm.glass-item-class", "ebmGlassItemClass", null),
    /** RRA item classification of processing (edging, drilling...). */
    EBM_SERVICE_ITEM_CLASS("ebm.service-item-class", "ebmServiceItemClass", null),
    /** Country of origin of the glass in its EBM item code (orgnNatCd). */
    EBM_ORIGIN_COUNTRY("ebm.origin-country", "ebmOriginCountry", "RW"),
    /** Where a receipt's QR code leads: RRA's receipt check, followed by TIN + branch + signature. */
    EBM_RECEIPT_URL("ebm.receipt-url", "ebmReceiptUrl", "https://myrra.rra.gov.rw/common/link/ebm/receipt/indexEbmReceiptData?Data=");

    private final String key;
    private final String property;
    private final String defaultValue;

    SettingKey(String key, String property, String defaultValue) {
        this.key = key;
        this.property = property;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public String property() {
        return property;
    }

    public String defaultValue() {
        return defaultValue;
    }
}
