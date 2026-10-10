package com.ntaganira.heritier.iWarehouse.ebm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : VsdcClient.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : What iWarehouse asks of a VSDC (TAX-02): the business's real one over HTTP (HttpVsdcClient) or the
 *               simulator for development and tests (SimulatedVsdc). A VSDC that cannot be reached throws
 *               VsdcUnavailableException; any answer, even a refusal, comes back as a Reply with its result code.
 * </pre>
 */
public interface VsdcClient {

    Vsdc.Reply<Vsdc.InitData> init(Vsdc.InitRequest request);

    Vsdc.Reply<JsonNode> saveItem(Vsdc.ItemRequest request);

    Vsdc.Reply<Vsdc.Signature> saveSale(Vsdc.SaleRequest request);

    /** Signed by the simulator: the receipt is not fiscal. */
    boolean simulated();
}
