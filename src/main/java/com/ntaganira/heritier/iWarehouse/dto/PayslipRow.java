package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : PayslipRow.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : One payslip in a list (an employee's pay history, My profile): its run, month, state and amounts.
 * </pre>
 */
public record PayslipRow(UUID lineId, UUID runId, String runNumber, LocalDate period, PayRunStatus status, BigDecimal gross,
                         BigDecimal netPay) {
}
