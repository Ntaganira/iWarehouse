package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.EbmMode;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : SettingsDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Settings form (ADM-03). One property per SettingKey, matched by name; the limits here
 *               are the only validation the values get, so keep them in step with the SRS.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class SettingsDto {

    // --- Company (printed on invoices and receipts) ---

    @NotBlank(message = "{settings.companyName.required}")
    @Size(max = 150, message = "{settings.companyName.size}")
    private String companyName;

    /** Rwanda TIN: 9 digits. */
    @Pattern(regexp = "^$|^[0-9]{9}$", message = "{settings.companyTin.pattern}")
    private String companyTin;

    @Size(max = 255, message = "{settings.companyAddress.size}")
    private String companyAddress;

    @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "{settings.companyPhone.pattern}")
    private String companyPhone;

    @Email(message = "{settings.companyEmail.invalid}")
    @Size(max = 100, message = "{settings.companyEmail.size}")
    private String companyEmail;

    @NotBlank(message = "{settings.branchCode.required}")
    @Pattern(regexp = "^[A-Z0-9]{1,10}$", message = "{settings.branchCode.pattern}")
    private String branchCode;

    // --- Production (PRD-04) ---

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0.01", message = "{settings.offcutMinArea.range}")
    @DecimalMax(value = "10", message = "{settings.offcutMinArea.range}")
    @Digits(integer = 2, fraction = 4, message = "{settings.area.digits}")
    private BigDecimal offcutMinArea;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 50, message = "{settings.offcutMinSide.range}")
    @Max(value = 3000, message = "{settings.offcutMinSide.range}")
    private Integer offcutMinSide;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "1", message = "{settings.glassDensity.range}")
    @DecimalMax(value = "5", message = "{settings.glassDensity.range}")
    @Digits(integer = 1, fraction = 3, message = "{settings.glassDensity.digits}")
    private BigDecimal glassDensity;

    // --- Pricing (MD-06) ---

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.minChargeableArea.range}")
    @DecimalMax(value = "10", message = "{settings.minChargeableArea.range}")
    @Digits(integer = 2, fraction = 4, message = "{settings.area.digits}")
    private BigDecimal minChargeableArea;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 7, message = "{settings.slowMovingDays.range}")
    @Max(value = 730, message = "{settings.slowMovingDays.range}")
    private Integer slowMovingDays;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.quotationValidityDays.range}")
    @Max(value = 365, message = "{settings.quotationValidityDays.range}")
    private Integer quotationValidityDays;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.depositMinPercent.range}")
    @DecimalMax(value = "100", message = "{settings.depositMinPercent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal depositMinPercent;

    // --- Approvals (INV-07, POS-06) ---

    /** RWF, no decimals. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.amount.range}")
    @Digits(integer = 16, fraction = 0, message = "{settings.amount.digits}")
    private BigDecimal adjustmentApprovalLimit;

    /** RWF above which a vehicle cost waits for another person's approval. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.amount.range}")
    @Digits(integer = 16, fraction = 0, message = "{settings.amount.digits}")
    private BigDecimal vehicleCostApprovalLimit;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.discountApprovalPercent.range}")
    @DecimalMax(value = "100", message = "{settings.discountApprovalPercent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal discountApprovalPercent;

    /** A discount that takes a line below what its glass cost needs approval (SDP 8). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalBelowCost;

    /** Counter exceptions are recommended before they are decided (F-03). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalSaleRecommend;

    /** Customers with invoices overdue get no credit until a manager approves it (SDP 11). */
    @NotNull(message = "{settings.value.required}")
    private Boolean creditBlockOverdue;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 0, message = "{settings.creditOverdueGraceDays.range}")
    @Max(value = 90, message = "{settings.creditOverdueGraceDays.range}")
    private Integer creditOverdueGraceDays;

    /** Credit notes are asked for and approved before they are issued (F-14). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalCreditNoteRequired;

    /** Finance reviews credit note requests before they are approved (F-14). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalCreditNoteReview;

    /** Finance reviews receivable write-offs before they are approved (F-14). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalWriteOffReview;

    /** RWF above which a purchase order is approved before it goes to the supplier (CMP 06). */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.amount.range}")
    @Digits(integer = 16, fraction = 0, message = "{settings.amount.digits}")
    private BigDecimal approvalPurchaseOrderLimit;

    /** Supplier payments are prepared, then authorised by another person (CMP 07). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalSupplierPaymentAuthorise;

    /** RWF above which a supplier payment needs two authorisations (CMP 07). */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.amount.range}")
    @Digits(integer = 16, fraction = 0, message = "{settings.amount.digits}")
    private BigDecimal approvalSupplierPaymentDualLimit;

    /** Goods receipts are posted by another person than who entered them (CMP 06). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalReceiptSecondPerson;

    /** Credit terms change only through an approved credit application (F-02 C-D). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalCreditApplication;

    /** The Director of Finance reviews a credit application after Credit Control (F-02 C). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalCreditApplicationReview;

    /** Months after which an approved credit limit is reviewed (F-02 C). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.creditReviewMonths.range}")
    @Max(value = 60, message = "{settings.creditReviewMonths.range}")
    private Integer creditReviewMonths;

    /** Days overdue after which a credit limit is reviewed at once (F-02 C). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.creditReviewOverdueDays.range}")
    @Max(value = 365, message = "{settings.creditReviewOverdueDays.range}")
    private Integer creditReviewOverdueDays;

    /** Prices change only once another person approves them (SDP 5). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalPriceChange;

    /** A new supplier, or new bank details, wait for approval (CMP 08). */
    @NotNull(message = "{settings.value.required}")
    private Boolean approvalSupplierOnboarding;

    /** References a supplier needs before it is approved (CMP 08). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 0, message = "{settings.supplierReferencesRequired.range}")
    @Max(value = 10, message = "{settings.supplierReferencesRequired.range}")
    private Integer supplierReferencesRequired;

    // --- Stock controls (M12) ---

    /** Every stock movement but a sale gets a Transaction Ticket (BP 7.4, decision D6). */
    @NotNull(message = "{settings.value.required}")
    private Boolean ticketEnabled;

    /** A goods receipt posts only from its countersigned ticket (Annex I step 4). */
    @NotNull(message = "{settings.value.required}")
    private Boolean ticketGateReceipts;

    /** A transfer between sites is approved before it leaves (TRF-007). */
    @NotNull(message = "{settings.value.required}")
    private Boolean transferApproval;

    /** Hours after the expected arrival that a transfer still in transit is reported (IMP 11). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.transferDifferenceHours.range}")
    @Max(value = 720, message = "{settings.transferDifferenceHours.range}")
    private Integer transferDifferenceHours;

    /** Stock written off, broken or lost above this % of the stock value is flagged in the monthly reconciliation. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.reconciliationTolerancePercent.range}")
    @DecimalMax(value = "100", message = "{settings.reconciliationTolerancePercent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal reconciliationTolerancePercent;

    // --- The Order Desk and the release of goods (M13)

    /** The till takes payment only for orders the Order Desk raised (decision D4). */
    @NotNull(message = "{settings.value.required}")
    private Boolean salesOrderDesk;

    /** An order not paid within this many days lapses. */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.salesOrderValidDays.range}")
    @Max(value = 90, message = "{settings.salesOrderValidDays.range}")
    private Integer salesOrderValidDays;

    /** Goods leave only on a delivery note released by the warehouse (F-04). */
    @NotNull(message = "{settings.value.required}")
    private Boolean releaseByNote;

    /** No goods before the EBM receipt is signed, unless overridden with a reason (decision D3). */
    @NotNull(message = "{settings.value.required}")
    private Boolean ebmBeforeRelease;

    /** Working days for the signed delivery note to come back (IMP 9.3). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.signedCopyDays.range}")
    @Max(value = 30, message = "{settings.signedCopyDays.range}")
    private Integer signedCopyDays;

    /** Cut pieces are verified by another person before release (IMP 10 step 3). */
    @NotNull(message = "{settings.value.required}")
    private Boolean cuttingVerify;

    // --- The daily close, cash and receivables (M14)

    /** The RWF notes and coins counted at the till, largest first (F-13). */
    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^\\s*\\d{1,6}(\\s*,\\s*\\d{1,6}){0,19}\\s*$", message = "{settings.cashDenominations.pattern}")
    private String cashDenominations;

    /** Treasury verifies a till's count before its cash is banked (F-13). */
    @NotNull(message = "{settings.value.required}")
    private Boolean cashVerifyCount;

    /** The day's close is prepared by this time the next day (BP 5.3). */
    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "{settings.dailyReportBy.pattern}")
    private String dailyReportBy;

    /** Bank and MoMo accounts are reconciled by this working day of the month (BP Table 9, Annex D). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.workingDay.range}")
    @Max(value = 20, message = "{settings.workingDay.range}")
    private Integer reconciliationDueWorkingDay;

    /** Goods paid by bank transfer leave once Finance confirms the money arrived (SDP 14). */
    @NotNull(message = "{settings.value.required}")
    private Boolean releaseTransferConfirmed;

    /** The share of receivables more than 90 days overdue is kept below this (Annex D). */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.creditOver90MaxPercent.range}")
    @DecimalMax(value = "100", message = "{settings.creditOver90MaxPercent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal creditOver90MaxPercent;

    /** Days of credit sales the days sales outstanding is worked out on. */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 7, message = "{settings.creditDsoDays.range}")
    @Max(value = 365, message = "{settings.creditDsoDays.range}")
    private Integer creditDsoDays;

    /** The month's accounts are ready by this working day of the next month (BP 5.3). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.workingDay.range}")
    @Max(value = 20, message = "{settings.workingDay.range}")
    private Integer accountsWorkingDay;

    // --- The field cycle (M15)

    /** The load request is approved before the vehicle leaves (F-09). */
    @NotNull(message = "{settings.value.required}")
    private Boolean fleetLoadApproval;

    /** No new load while the last trip is not cleared (BP 7.3 rule 9). */
    @NotNull(message = "{settings.value.required}")
    private Boolean fleetPreviousCleared;

    /** The clearance is countersigned by the Director of Finance (F-11). */
    @NotNull(message = "{settings.value.required}")
    private Boolean fleetClearanceCountersign;

    /** Vehicle costs are paid from a float up to this day; blank = always (decision D9). */
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "{settings.date.pattern}")
    private String fleetCostsFromFloatUntil;

    /** Van stock shortages kept within this % of what was loaded (Annex D). */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.fleetShortageTolerancePercent.range}")
    @DecimalMax(value = "100", message = "{settings.fleetShortageTolerancePercent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal fleetShortageTolerancePercent;

    /** The phone sells on credit to approved customers within their limit (decision D8). */
    @NotNull(message = "{settings.value.required}")
    private Boolean mobileFieldCredit;

    // --- Damage, counts and variances (M16)

    /** A damage report is inspected by Dispatch before it is decided (F-07). */
    @NotNull(message = "{settings.value.required}")
    private Boolean damageInspection;

    /** Damage at or above this value (RWF) is told the same day (F-07, the Board's tolerance). */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal damageAlertValue;

    /** The first count is blind (CSH-012, D14). */
    @NotNull(message = "{settings.value.required}")
    private Boolean countBlind;

    /** Count types that always get a second count (D14): comma-separated. */
    @Pattern(regexp = "^$|^(CYCLE|QUARTERLY|ANNUAL|SURPRISE)(,(CYCLE|QUARTERLY|ANNUAL|SURPRISE))*$", message = "{settings.countSecondTypes.pattern}")
    private String countSecondTypes;

    /** A second count wherever the first finds differences (D14). */
    @NotNull(message = "{settings.value.required}")
    private Boolean countSecondOnDifferences;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal countVarianceTolerancePercent;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal countVarianceModeratePercent;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal countVarianceMaterialPercent;

    /** Working days to resolve a variance before it is escalated (IMP 14.2). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.countVarianceDays.range}")
    @Max(value = 60, message = "{settings.countVarianceDays.range}")
    private Integer countVarianceDays;

    // --- Inventory reports and KPIs (Inventory Policy 13, 19; Annex D) ---

    /** Glass held longer than this many days is obsolete (IMP 13). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 30, message = "{settings.stockObsoleteDays.range}")
    @Max(value = 3650, message = "{settings.stockObsoleteDays.range}")
    private Integer stockObsoleteDays;

    /** Inventory accuracy kept at or above this % (IMP 19). */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal kpiAccuracyTarget;

    /** Consecutive months below the accuracy target that call for a formal review (IMP 19: two). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.kpiAccuracyMonths.range}")
    @Max(value = 12, message = "{settings.kpiAccuracyMonths.range}")
    private Integer kpiAccuracyMonths;

    /** Stock lost kept below this % of the inventory value. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal kpiLossMaxPercent;

    /** Damaged stock kept below this % of the inventory value. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal kpiDamageMaxPercent;

    /** Inventory turnover aimed at, times a year; blank = no target (set by management). */
    @Pattern(regexp = "^$|^\\d{1,3}(\\.\\d{1,2})?$", message = "{settings.kpiTurnoverTarget.pattern}")
    private String kpiTurnoverTarget;

    /** Deliveries without errors or shortages, % aimed at. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal kpiDeliveryAccuracyTarget;

    /** Reconciliations done on time, % aimed at. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal kpiReconciliationTarget;

    /** Variances resolved in time, % aimed at. */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal kpiVarianceResolutionTarget;

    /** Days from payment to the last hand-over aimed at (Annex D). */
    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.kpiOrderDaysTarget.range}")
    @DecimalMax(value = "365", message = "{settings.kpiOrderDaysTarget.range}")
    @Digits(integer = 3, fraction = 1, message = "{settings.kpiOrderDaysTarget.range}")
    private BigDecimal kpiOrderDaysTarget;

    // --- Expenditure control (M17: Cost Management & Expenditure Control Policy 05, 06, 09) ---

    @NotNull(message = "{settings.value.required}")
    private Boolean expenseRequisitionRequired;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal expenseRequisitionFrom;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal expenseRequisitionTolerancePercent;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal expenseQuotesThreeFrom;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal expenseRfqFrom;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal expenseTenderFrom;

    @NotNull(message = "{settings.value.required}")
    private Boolean expenseQuoteDocuments;

    @NotNull(message = "{settings.value.required}")
    private Boolean expenseInvoiceDocument;

    @NotNull(message = "{settings.value.required}")
    private Boolean expenseRecommend;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal expenseFinanceReviewFrom;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.expenseSplitDays.range}")
    @Max(value = 90, message = "{settings.expenseSplitDays.range}")
    private Integer expenseSplitDays;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.expenseRatifyDays.range}")
    @Max(value = 90, message = "{settings.expenseRatifyDays.range}")
    private Integer expenseRatifyDays;

    @Pattern(regexp = "^$|^[A-Za-z0-9-]{2,10}$", message = "{costCentre.code.pattern}")
    private String expenseFleetCostCentre;

    // --- Petty cash and staff advances (M17: Cost Management & Expenditure Control Policy 07, 08) ---

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal pettyCashMaxFloat;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.money.range}")
    @Digits(integer = 16, fraction = 2, message = "{settings.money.range}")
    private BigDecimal pettyCashMaxPayment;

    @NotNull(message = "{settings.value.required}")
    @DecimalMin(value = "0", message = "{settings.percent.range}")
    @DecimalMax(value = "100", message = "{settings.percent.range}")
    @Digits(integer = 3, fraction = 2, message = "{settings.percent.digits}")
    private BigDecimal pettyCashReplenishPercent;

    @NotNull(message = "{settings.value.required}")
    private Boolean pettyCashReceiptRequired;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.pettyCashAuditDay.range}")
    @Max(value = 28, message = "{settings.pettyCashAuditDay.range}")
    private Integer pettyCashAuditDay;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.advanceRetireDays.range}")
    @Max(value = 60, message = "{settings.advanceRetireDays.range}")
    private Integer advanceRetireDays;

    @NotNull(message = "{settings.value.required}")
    private Boolean advanceBlockOpen;

    @NotNull(message = "{settings.value.required}")
    private Boolean advanceReceiptsRequired;

    @NotNull(message = "{settings.value.required}")
    private Boolean advancePayrollRecovery;

    // --- Currency (ACC-02) ---

    @NotNull(message = "{settings.value.required}")
    private RateSource defaultRateSource;

    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.maxRateAgeDays.range}")
    @Max(value = 90, message = "{settings.maxRateAgeDays.range}")
    private Integer maxRateAgeDays;

    // --- Alerts (RPT-06) ---

    @NotNull(message = "{settings.value.required}")
    private Boolean alertEmail;

    /** Days before a licence, insurance or inspection expires that the fleet is told (FLT-04). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.fleetExpiryAlertDays.range}")
    @Max(value = 180, message = "{settings.fleetExpiryAlertDays.range}")
    private Integer fleetExpiryAlertDays;

    /** Hours after a trip came back that a float not cleared is reported (RPT-06). */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 1, message = "{settings.floatAlertHours.range}")
    @Max(value = 168, message = "{settings.floatAlertHours.range}")
    private Integer floatAlertHours;

    /** Km before a vehicle's service falls due that the fleet is told. */
    @NotNull(message = "{settings.value.required}")
    @Min(value = 0, message = "{settings.serviceAlertKm.range}")
    @Max(value = 20000, message = "{settings.serviceAlertKm.range}")
    private Integer serviceAlertKm;

    // --- EBM fiscal signing (TAX-02) ---

    @NotNull(message = "{settings.value.required}")
    private EbmMode ebmMode;

    @Pattern(regexp = "^$|^https?://[^\\s]{3,240}$", message = "{settings.ebmVsdcUrl.pattern}")
    private String ebmVsdcUrl;

    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^[0-9]{2}$", message = "{settings.ebmBranchId.pattern}")
    private String ebmBranchId;

    @Size(max = 100, message = "{settings.ebmDeviceSerial.size}")
    private String ebmDeviceSerial;

    @Pattern(regexp = "^$|^[0-9A-Za-z]{1,10}$", message = "{settings.ebmItemClass.pattern}")
    private String ebmGlassItemClass;

    @Pattern(regexp = "^$|^[0-9A-Za-z]{1,10}$", message = "{settings.ebmItemClass.pattern}")
    private String ebmServiceItemClass;

    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^[A-Z]{2}$", message = "{settings.ebmOriginCountry.pattern}")
    private String ebmOriginCountry;

    @NotBlank(message = "{settings.value.required}")
    @Pattern(regexp = "^https://[^\\s]{3,240}$", message = "{settings.ebmReceiptUrl.pattern}")
    private String ebmReceiptUrl;

    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode == null ? null : branchCode.trim().toUpperCase(Locale.ROOT);
    }

    public void setEbmOriginCountry(String ebmOriginCountry) {
        this.ebmOriginCountry = ebmOriginCountry == null ? null : ebmOriginCountry.trim().toUpperCase(Locale.ROOT);
    }

    public void setCompanyTin(String companyTin) {
        this.companyTin = companyTin == null ? null : companyTin.replace(" ", "");
    }
}
