package com.ntaganira.heritier.iWarehouse.service;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SaleIssued.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A sale posted: its WhatsApp confirmation goes out once the transaction commits.
 * </pre>
 */
public record SaleIssued(UUID invoiceId) {
}
