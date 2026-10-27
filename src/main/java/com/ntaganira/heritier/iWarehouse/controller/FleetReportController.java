package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.FleetAnalysis;
import com.ntaganira.heritier.iWarehouse.service.FleetReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : FleetReportController.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The fleet reports (RPT-04): sales per trip and per driver with the breakage rate, the end-of-day
 *               discrepancies and the floats outstanding, for the trips back in a period; on a page and in Excel or PDF
 *               (RPT-07). PAGE_FLEET_REPORTS + PERM_VIEW_FLEET_REPORTS.
 * </pre>
 */
@Controller
@RequestMapping("/reports/fleet")
public class FleetReportController {

    private static final String FLEET = "hasAuthority('PAGE_FLEET_REPORTS') and hasAuthority('PERM_VIEW_FLEET_REPORTS')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final FleetReportService reports;
    private final ActivityLogService activityLogService;
    private final ReportFiles reportFiles;
    private final Messages messages;

    public FleetReportController(FleetReportService reports, ActivityLogService activityLogService, ReportFiles reportFiles, Messages messages) {
        this.reports = reports;
        this.activityLogService = activityLogService;
        this.reportFiles = reportFiles;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(FLEET)
    public String report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "TRIPS") FleetAnalysis.View view, @RequestParam(required = false) UUID driver,
                         @RequestParam(required = false) UUID vehicle, @RequestParam(defaultValue = "0") int page, Model model) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, reports.today());
        FleetReportService.Report report = reports.report(p.from(), p.to(), driver, vehicle);
        List<?> rows = switch (view) {
            case TRIPS -> report.trips();
            case DRIVERS -> report.drivers();
            case VEHICLES -> report.vehicles();
            case DISCREPANCIES -> report.discrepancies();
            case FLOATS -> report.floats();
        };
        model.addAttribute("report", report);
        model.addAttribute("rows", Paging.of(rows, Paging.page(page)));
        model.addAttribute("views", FleetAnalysis.View.values());
        model.addAttribute("view", view);
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        model.addAttribute("today", reports.today());
        model.addAttribute("driver", driver == null ? null : driver.toString());
        model.addAttribute("vehicle", vehicle == null ? null : vehicle.toString());
        model.addAttribute("query", QueryString.of("from", p.from().toString(), "to", p.to().toString(), "view", view.name(),
                "driver", driver == null ? null : driver.toString(), "vehicle", vehicle == null ? null : vehicle.toString()));
        return "reports/fleet";
    }

    @GetMapping("/export")
    @PreAuthorize(FLEET)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "TRIPS") FleetAnalysis.View view, @RequestParam(required = false) UUID driver,
                                         @RequestParam(required = false) UUID vehicle,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, reports.today());
        FleetReportService.Report report = reports.report(p.from(), p.to(), driver, vehicle);
        String title = m("fleetReport.view." + view);
        String covers = view == FleetAnalysis.View.FLOATS ? m("fleetReport.floatsSubtitle")
                : m("fleetReport.exportSubtitle", p.from().format(DAY), p.to().format(DAY));
        Excel.Builder sheet = switch (view) {
            case TRIPS -> {
                Excel.Builder s = Excel.sheet(title, m("fleetReport.title"), covers, m("fleetReport.trip"), m("fleetReport.date"), m("fleetReport.vehicle"),
                        m("fleetReport.driver"), m("fleetReport.loaded"), m("fleetReport.sold"), m("fleetReport.broken"), m("fleetReport.returned"),
                        m("fleetReport.missing"), m("fleetReport.sales"), m("fleetReport.cash"), m("fleetReport.counted"), m("fleetReport.shortage"),
                        m("fleetReport.status"));
                for (FleetAnalysis.TripRow r : report.trips()) {
                    s.row(r.number(), r.date(), r.vehicle(), r.driver(), r.loaded(), r.sold(), r.broken(), r.returned(), r.missing(), r.sales(),
                            r.cash(), r.counted(), r.shortage().signum() == 0 ? null : r.shortage(), m("trip.status." + r.status()));
                }
                FleetAnalysis.DriverRow t = report.total();
                s.bold(m("cutting.total"), null, null, null, t.loaded(), t.sold(), t.broken(), null, t.missing(), t.sales(), t.cash(), null,
                        t.shortage().signum() == 0 ? null : t.shortage(), null);
                yield s;
            }
            case DRIVERS -> {
                Excel.Builder s = Excel.sheet(title, m("fleetReport.title"), covers, m("fleetReport.driver"), m("fleetReport.trips"),
                        m("fleetReport.loaded"), m("fleetReport.sold"), m("fleetReport.broken"), m("fleetReport.breakageRate"), m("fleetReport.missing"),
                        m("fleetReport.sales"), m("fleetReport.cash"), m("fleetReport.shortage"));
                for (FleetAnalysis.DriverRow r : report.drivers()) {
                    s.row(driverCells(r, r.driver()));
                }
                s.bold(driverCells(report.total(), m("cutting.total")));
                yield s;
            }
            case VEHICLES -> {
                Excel.Builder s = Excel.sheet(title, m("fleetReport.title"), covers, m("fleetReport.vehicle"), m("fleetReport.trips"),
                        m("fleetReport.km"), m("fleetReport.sales"), m("fleetReport.fuel"), m("fleetReport.litres"), m("fleetReport.maintenance"),
                        m("fleetReport.otherCosts"), m("fleetReport.totalCost"), m("fleetReport.costPerKm"));
                for (FleetAnalysis.VehicleRow r : report.vehicles()) {
                    s.row(vehicleCells(r, r.plate()));
                }
                s.bold(vehicleCells(report.vehicleTotal(), m("cutting.total")));
                yield s;
            }
            case DISCREPANCIES -> {
                Excel.Builder s = Excel.sheet(title, m("fleetReport.title"), covers, m("fleetReport.case"), m("fleetReport.trip"), m("fleetReport.date"),
                        m("fleetReport.driver"), m("fleetReport.unit"), m("fleetReport.kind"), m("fleetReport.value"), m("fleetReport.outcome"));
                for (FleetAnalysis.DiscrepancyRow r : report.discrepancies()) {
                    s.row(r.caseNumber(), r.trip(), r.date(), r.driver(), r.unitCode(), m("auditCase.kind." + r.kind()), r.value(),
                            r.outcome() == null ? m("auditCase.line.open") : m("auditCase.outcome." + r.outcome()));
                }
                yield s;
            }
            case FLOATS -> {
                Excel.Builder s = Excel.sheet(title, m("fleetReport.title"), covers, m("fleetReport.driver"), m("fleetReport.trips"),
                        m("fleetReport.outstanding"), m("fleetReport.oldest"));
                for (FleetAnalysis.FloatRow r : report.floats()) {
                    s.row(r.driver(), r.trips(), r.amount(), r.oldestTrip() + " · " + r.oldestReturn().format(DAY_TIME));
                }
                s.bold(m("cutting.total"), null, report.floatsOutstanding(), null);
                yield s;
            }
        };
        activityLogService.record(ReportController.MODULE, "EXPORT_FLEET_REPORT", "Exported the fleet report (" + view + ") from " + p.from()
                + " to " + p.to() + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "fleet-" + view.name().toLowerCase() + "-" + p.from() + "-" + p.to(), sheet.build());
    }

    private static Object[] vehicleCells(FleetAnalysis.VehicleRow r, String first) {
        return new Object[]{first, r.trips(), r.km(), r.sales(), r.fuel(), r.litres(), r.maintenance(), r.other(), r.getTotalCost(), r.getCostPerKm()};
    }

    private Object[] driverCells(FleetAnalysis.DriverRow r, String label) {
        return Arrays.asList(label, r.trips(), r.loaded(), r.sold(), r.broken(), r.getBreakageRate(), r.missing(), r.sales(), r.cash(),
                r.shortage().signum() == 0 ? null : r.shortage()).toArray();
    }

    private String m(String key, Object... args) {
        return messages.get(key, args);
    }
}
