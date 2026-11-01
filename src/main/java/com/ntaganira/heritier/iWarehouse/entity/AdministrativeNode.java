package com.ntaganira.heritier.iWarehouse.entity;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : AdministrativeNode.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What every Rwandan administrative tier has in common, so the dashboard's rename, visibility
 *               and delete rules are written once.
 * </pre>
 */
public interface AdministrativeNode {

    UUID getId();

    String getCode();

    String getName();

    void setName(String name);

    boolean isEnabled();

    void setEnabled(boolean enabled);

    boolean isAllowEdit();
}
