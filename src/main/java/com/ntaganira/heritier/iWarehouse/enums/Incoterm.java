package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : Incoterm.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Incoterms 2020, a supplier's default delivery term (MD-05). The flags say which import
 *               costs the supplier's price already covers, so the shipment cost sheet (PRC-03) knows
 *               which ones the business still pays: main freight, cargo insurance, import duty.
 * </pre>
 */
public enum Incoterm {

    EXW(false, false, false),
    FCA(false, false, false),
    FAS(false, false, false),
    FOB(false, false, false),
    CFR(true, false, false),
    CIF(true, true, false),
    CPT(true, false, false),
    CIP(true, true, false),
    DAP(true, true, false),
    DPU(true, true, false),
    DDP(true, true, true);

    private final boolean supplierPaysFreight;
    private final boolean supplierPaysInsurance;
    private final boolean supplierPaysDuty;

    Incoterm(boolean supplierPaysFreight, boolean supplierPaysInsurance, boolean supplierPaysDuty) {
        this.supplierPaysFreight = supplierPaysFreight;
        this.supplierPaysInsurance = supplierPaysInsurance;
        this.supplierPaysDuty = supplierPaysDuty;
    }

    public boolean isSupplierPaysFreight() {
        return supplierPaysFreight;
    }

    public boolean isSupplierPaysInsurance() {
        return supplierPaysInsurance;
    }

    public boolean isSupplierPaysDuty() {
        return supplierPaysDuty;
    }
}
