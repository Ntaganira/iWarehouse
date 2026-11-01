package com.ntaganira.heritier.iWarehouse.service;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : WhatsAppGateway.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The external WhatsApp gateway (TASK.md). A gateway is configured by Settings
 *               (whatsapp.gateway-url, whatsapp.token, whatsapp.sender) and receives one JSON
 *               message per sale confirmation.
 * </pre>
 */
public interface WhatsAppGateway {

    /** The Settings name an address, so a confirmation can be sent. */
    boolean configured();

    /** Send one text message to an international number without the plus. Throws on failure. */
    void send(String number, String message);
}
