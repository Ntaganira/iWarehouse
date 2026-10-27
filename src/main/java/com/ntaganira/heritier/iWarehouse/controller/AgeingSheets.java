package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.service.Ageing;
import com.ntaganira.heritier.iWarehouse.service.CollectionAnalysis;
import com.ntaganira.heritier.iWarehouse.service.CustomerAccountService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.PrintTable;
import com.ntaganira.heritier.iWarehouse.service.SalesControlReportService;
import com.ntaganira.heritier.iWarehouse.service.SupplierAccountService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : AgeingSheets.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The aged receivables and payables as export sheets (ACC-09, RPT-07): one row per party, one column per
 *               ageing bucket and the total; collection performance by territory, salesperson or customer (Q.05). Shared
 *               by their own exports and the monthly management pack (T.10).
 * </pre>
 */
@Component
public class AgeingSheets {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Messages messages;

    public AgeingSheets(Messages messages) {
        this.messages = messages;
    }

    public Excel.Sheet receivables(String name, CustomerAccountService.Receivables receivables, LocalDate today) {
        Excel.Builder sheet = Excel.sheet(name, messages.get("receivables.title"), messages.get("fin.asOf", today.format(DAY)),
                headers(messages.get("invoice.customer"), messages.get("party.terms")));
        for (CustomerAccountService.Aged a : receivables.customers()) {
            sheet.row(row(a.customer().getName(), terms(a.customer().getPaymentTermsDays()), a.ageing()));
        }
        sheet.bold(totalRow(receivables.totals(), receivables.total()));
        return sheet.build();
    }

    public Excel.Sheet payables(String name, SupplierAccountService.PayablesReport payables, LocalDate today) {
        Excel.Builder sheet = Excel.sheet(name, messages.get("payables.title"), messages.get("fin.asOf", today.format(DAY)),
                headers(messages.get("supplierInvoice.supplier"), messages.get("payables.owedIn")));
        for (SupplierAccountService.Aged a : payables.suppliers()) {
            String owed = a.open().stream().filter(o -> o.getAmount().signum() != 0)
                    .map(o -> PrintTable.text(o.getAmount().setScale(2, RoundingMode.HALF_UP)) + " " + o.currency())
                    .collect(Collectors.joining("; "));
            sheet.row(row(a.supplier().getName(), owed, a.ageing()));
        }
        sheet.bold(totalRow(payables.totals(), payables.total()));
        return sheet.build();
    }

    /** Collection performance of a period (Q.05). */
    public Excel.Sheet collections(SalesControlReportService.Collections r, String name) {
        Excel.Builder sheet = Excel.sheet(name, messages.get("collections.title"), messages.get("fin.period", r.from().format(DAY), r.to().format(DAY)),
                messages.get("collections.group." + r.by()), messages.get("collections.opening"), messages.get("collections.charged"),
                messages.get("collections.collected"), messages.get("collections.rate"), messages.get("collections.onTime"),
                messages.get("collections.otherCredits"), messages.get("collections.owed"), messages.get("collections.overdue"));
        for (SalesControlReportService.CollectionRow row : r.rows()) {
            CollectionAnalysis.Row f = row.figures();
            sheet.row(row.label() == null || row.label().isBlank() ? messages.get("reports.none") : row.label(), f.opening(), f.charged(),
                    f.collected(), f.getRate(), f.getOnTimePercent(), f.otherCredits(), f.getOwed(), f.overdue());
        }
        CollectionAnalysis.Row t = r.total();
        sheet.bold(messages.get("cutting.total"), t.opening(), t.charged(), t.collected(), t.getRate(), t.getOnTimePercent(), t.otherCredits(),
                t.getOwed(), t.overdue());
        return sheet.build();
    }

    /** Party, a second column, one column per ageing bucket, the total. */
    private String[] headers(String party, String second) {
        List<String> headers = new ArrayList<>(List.of(party, second));
        for (Ageing.Bucket b : Ageing.Bucket.values()) {
            headers.add(messages.get("ageing." + b));
        }
        headers.add(messages.get("customerAccount.total"));
        return headers.toArray(String[]::new);
    }

    private static Object[] row(String party, String second, Ageing.Result ageing) {
        List<Object> cells = new ArrayList<>(List.of(party, second));
        for (Ageing.Bucket b : Ageing.Bucket.values()) {
            cells.add(ageing.get(b));
        }
        cells.add(ageing.balance());
        return cells.toArray();
    }

    private Object[] totalRow(Map<Ageing.Bucket, BigDecimal> totals, BigDecimal total) {
        List<Object> cells = new ArrayList<>();
        cells.add(messages.get("fin.total"));
        cells.add(null);
        for (Ageing.Bucket b : Ageing.Bucket.values()) {
            cells.add(totals.getOrDefault(b, BigDecimal.ZERO));
        }
        cells.add(total);
        return cells.toArray();
    }

    private String terms(int days) {
        return days == 0 ? messages.get("party.termsNone") : messages.get("party.termsDays", days);
    }
}
