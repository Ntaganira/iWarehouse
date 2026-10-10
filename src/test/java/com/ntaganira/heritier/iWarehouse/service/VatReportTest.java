package com.ntaganira.heritier.iWarehouse.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monthly VAT report (TAX-05): VAT per tax letter worked out on each document, then added up over the month, credit
 * notes taken off; and the Excel export of reports (numbers stay numbers).
 */
class VatReportTest {

    @Test
    void vatIsWorkedOutPerDocumentAndLetterThenAddedUp() {
        UUID inv1 = UUID.randomUUID();
        UUID inv2 = UUID.randomUUID();
        UUID cn1 = UUID.randomUUID();
        VatReport.Result r = VatReport.of(
                List.of(line(inv1, "B", "18", "21500"), line(inv1, "B", "18", "21500"), line(inv1, "A", "0", "5000"),
                        line(inv2, "B", "18", "195008")),
                List.of(line(cn1, "B", "18", "13500")));

        assertThat(r.invoices()).isEqualTo(2);
        assertThat(r.creditNotes()).isEqualTo(1);
        assertThat(r.letters()).extracting(VatReport.Letter::taxCode).containsExactly("A", "B");
        VatReport.Letter b = r.letters().get(1);
        // Invoice 1: 43,000 x 18/118 = 6,559.32; invoice 2: 195,008 x 18/118 = 29,746.98; credit note: 13,500 x 18/118 = 2,059.32
        assertThat(b.salesGross()).isEqualByComparingTo("238008");
        assertThat(b.salesVat()).isEqualByComparingTo("36306.30");
        assertThat(b.creditGross()).isEqualByComparingTo("13500");
        assertThat(b.creditVat()).isEqualByComparingTo("2059.32");
        assertThat(b.getVat()).isEqualByComparingTo("34246.98");
        assertThat(b.getNet()).isEqualByComparingTo("190261.02");                      // (238,008 - 36,306.30) - (13,500 - 2,059.32)
        VatReport.Letter a = r.letters().get(0);
        assertThat(a.getVat()).isEqualByComparingTo("0");                              // exempt
        assertThat(a.getNet()).isEqualByComparingTo("5000");
        assertThat(r.getVat()).isEqualByComparingTo("34246.98");
        assertThat(VatReport.of(List.of(), List.of()).letters()).isEmpty();
    }

    @Test
    void anExcelExportKeepsAmountsAsNumbersAndDatesAsDates() throws Exception {
        byte[] file = Excel.write(Excel.sheet("Trial balance", "Trial balance", "As at 09/10/2026", "Code", "Account", "Debit")
                .row("1030", "Bank", new BigDecimal("100000.50"))
                .row(LocalDate.of(2026, 10, 9), 3, null)
                .bold(null, "Total", new BigDecimal("100000.50"))
                .build());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet s = wb.getSheet("Trial balance");
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Trial balance");
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("As at 09/10/2026");
            assertThat(s.getRow(3).getCell(2).getStringCellValue()).isEqualTo("Debit");
            Cell amount = s.getRow(4).getCell(2);
            assertThat(amount.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(amount.getNumericCellValue()).isEqualTo(100000.50);
            assertThat(amount.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(s.getRow(5).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 9));
            assertThat(s.getRow(5).getCell(2)).isNull();                               // nothing in an empty cell
            assertThat(wb.getFontAt(s.getRow(6).getCell(1).getCellStyle().getFontIndex()).getBold()).isTrue();
        }
    }

    private static VatReport.DocLine line(UUID doc, String code, String rate, String amount) {
        return new VatReport.DocLine(doc, code, new BigDecimal(rate), new BigDecimal(amount));
    }
}
