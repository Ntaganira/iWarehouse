package com.ntaganira.heritier.iWarehouse.ebm;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : VsdcUnavailableException.java
 * - Date      : 2026. 10. 10.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The VSDC could not be reached or did not answer as a VSDC (TAX-03, AT-09): the receipt stays queued
 *               and is sent again later. Keeps the request that was being sent.
 * </pre>
 */
public class VsdcUnavailableException extends RuntimeException {

    private final String requestJson;

    public VsdcUnavailableException(String message, String requestJson) {
        super(message);
        this.requestJson = requestJson;
    }

    public String getRequestJson() {
        return requestJson;
    }
}
