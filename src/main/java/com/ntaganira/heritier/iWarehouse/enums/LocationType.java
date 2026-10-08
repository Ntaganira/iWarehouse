package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : LocationType.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Levels of the location tree (MD-02): Site &gt; Zone &gt; Rack &gt; Slot. A vehicle is a
 *               virtual location of its own, created with the vehicle by the Fleet module (FLT-01),
 *               never by hand.
 * </pre>
 */
public enum LocationType {

    SITE(null),
    ZONE(SITE),
    RACK(ZONE),
    SLOT(RACK),
    VEHICLE(null);

    private final LocationType parentType;

    LocationType(LocationType parentType) {
        this.parentType = parentType;
    }

    /** The type a location of this type sits under; null for roots (site, vehicle). */
    public LocationType getParentType() {
        return parentType;
    }

    /** The type of the locations that go under this one; null when nothing does. */
    public LocationType getChildType() {
        for (LocationType t : values()) {
            if (t.parentType == this) {
                return t;
            }
        }
        return null;
    }

    public boolean isRoot() {
        return parentType == null;
    }

    /** False for vehicles: the Fleet module creates their location. */
    public boolean isManual() {
        return this != VEHICLE;
    }
}
