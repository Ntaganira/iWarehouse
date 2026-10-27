package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.InventoryKpiService;
import com.ntaganira.heritier.iWarehouse.service.InventoryKpis;
import com.ntaganira.heritier.iWarehouse.service.InventoryReportService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : InventorySheets.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The stock position by site as an export sheet (IMP 14.1, 18): each site's units and m² at the end, what
 *               came in and went out (cost with PERM_VIEW_STOCK_COST), glass in transit and on vehicles apart; shared by
 *               the daily inventory report and the weekly to annual reports. The inventory KPIs as rows, one column per
 *               period, then the target (the KPI page, the inventory reports, the management pack).
 * </pre>
 */
@Component
public class InventorySheets {

    private final Messages messages;

    public InventorySheets(Messages messages) {
        this.messages = messages;
    }

    public Excel.Builder sites(InventoryReportService.Sites sites, String title, String sub, boolean seeCost) {
        Excel.Builder sheet = Excel.sheet(title, title, sub, messages.get("inventoryReport.site"), messages.get("inventoryReport.unitsHeld"),
                messages.get("summary.area"), messages.get("inventoryReport.unitsIn"), messages.get("inventoryReport.m2In"),
                messages.get("inventoryReport.costIn"), messages.get("inventoryReport.unitsOut"), messages.get("inventoryReport.m2Out"),
                messages.get("inventoryReport.costOut"));
        for (InventoryReportService.SiteRow s : sites.rows()) {
            sheet.row(label(s), s.units(), s.areaM2(), s.inUnits(), s.inM2(), seeCost ? s.inCost() : null, s.outUnits(), s.outM2(),
                    seeCost ? s.outCost() : null);
        }
        InventoryReportService.SiteRow t = sites.total();
        sheet.bold(messages.get("cutting.total"), t.units(), t.areaM2(), t.inUnits(), t.inM2(), seeCost ? t.inCost() : null, t.outUnits(), t.outM2(),
                seeCost ? t.outCost() : null);
        return sheet;
    }

    /** A site's code and name; glass in transit and on vehicles by name. */
    public String label(InventoryReportService.SiteRow s) {
        return switch (s.key()) {
            case "TRANSIT" -> messages.get("inventoryReport.inTransit");
            case "VEHICLES" -> messages.get("inventoryReport.onVehicles");
            default -> s.code() + (s.name() == null ? "" : " - " + s.name());
        };
    }

    /** KPIs as rows, one column per period, then the target. */
    public Excel.Builder kpis(String title, String sub, List<InventoryKpiService.Measure> measures, List<String> labels) {
        List<String> headers = new ArrayList<>(List.of(messages.get("inventoryKpi.kpi")));
        headers.addAll(labels);
        headers.add(messages.get("inventoryKpi.target"));
        Excel.Builder sheet = Excel.sheet(title, title, sub, headers.toArray(String[]::new));
        for (InventoryKpis.Code code : InventoryKpis.Code.values()) {
            List<Object> cells = new ArrayList<>(List.of(messages.get("inventoryKpi.code." + code)));
            BigDecimal target = null;
            boolean ceiling = false;
            for (InventoryKpiService.Measure m : measures) {
                InventoryKpis.Kpi k = m.get(code);
                cells.add(k.value());
                target = k.target();
                ceiling = k.ceiling();
            }
            cells.add(target == null ? messages.get("inventoryKpi.noTarget")
                    : (ceiling ? (code == InventoryKpis.Code.ORDER_TO_DELIVERY ? "<= " : "< ") : ">= ") + target.stripTrailingZeros().toPlainString());
            sheet.row(cells.toArray());
        }
        return sheet;
    }
}
