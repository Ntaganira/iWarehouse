package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.PdfService;
import com.ntaganira.heritier.iWarehouse.service.PrintTable;
import com.ntaganira.heritier.iWarehouse.service.SettingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ReportFiles.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Report downloads (RPT-07, ACC-11): a report builds its Excel sheets once and is downloaded as Excel or
 *               as PDF (the same sheets printed by reports/pdf.html: the company, each sheet's title, what it covers,
 *               the table, who printed it and when, page numbers; landscape when a sheet has more than 6 columns).
 *               Export links pass format=XLSX or format=PDF (fragments/export :: buttons); the controller logs the
 *               export with the format's label.
 * </pre>
 */
@Component
public class ReportFiles {

    /** The file a report is downloaded as. */
    public enum Format {
        XLSX("Excel"), PDF("PDF");

        private final String label;

        Format(String label) {
            this.label = label;
        }

        /** "Excel" or "PDF", for the activity log. */
        public String label() {
            return label;
        }
    }

    private final PdfService pdfService;
    private final SettingService settings;
    private final Clock clock;

    public ReportFiles(PdfService pdfService, SettingService settings, Clock clock) {
        this.pdfService = pdfService;
        this.settings = settings;
        this.clock = clock;
    }

    /** The report's sheets as {@code name}.xlsx or {@code name}.pdf. */
    public ResponseEntity<byte[]> download(Format format, String name, Excel.Sheet... sheets) {
        if (format == Format.PDF) {
            return file(name + ".pdf", MediaType.APPLICATION_PDF, pdf(sheets));
        }
        return file(name + ".xlsx", MediaType.parseMediaType(Excel.CONTENT_TYPE), Excel.write(sheets));
    }

    /** The sheets printed on A4. */
    public byte[] pdf(Excel.Sheet... sheets) {
        Map<String, Object> model = letterhead();
        model.put("tables", Arrays.stream(sheets).map(PrintTable::of).toList());
        model.put("landscape", PrintTable.landscape(sheets));
        return pdfService.render("reports/pdf", model);
    }

    /** The company and who prints, for any PDF made from a template. */
    public Map<String, Object> letterhead() {
        Map<String, Object> model = new HashMap<>();
        model.put("companyName", settings.get(SettingKey.COMPANY_NAME));
        model.put("companyAddress", settings.get(SettingKey.COMPANY_ADDRESS));
        model.put("companyTin", settings.get(SettingKey.COMPANY_TIN));
        model.put("companyPhone", settings.get(SettingKey.COMPANY_PHONE));
        model.put("companyEmail", settings.get(SettingKey.COMPANY_EMAIL));
        model.put("printedAt", LocalDateTime.now(clock));
        model.put("printedBy", AppUserPrincipal.current().map(AppUserPrincipal::getFullName).orElse(""));
        return model;
    }

    public static ResponseEntity<byte[]> file(String filename, MediaType type, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(type)
                .body(body);
    }
}
