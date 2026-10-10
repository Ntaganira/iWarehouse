package com.ntaganira.heritier.iWarehouse.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reports printed and exported (RPT-07): the PDF's cells as text (amounts with their column's decimals, counts, dates,
 * numbers aligned right), landscape for wide sheets, and Excel showing each column with the same decimals.
 */
class PrintTableTest {

    private final Excel.Sheet sheet = Excel.sheet("Stock", "Stock summary", "By glass, on 10/10/2026", "Glass", "Pieces", "m²", "Value")
            .row("CLR-6", 8, new BigDecimal("41.1125"), new BigDecimal("1417478.23"))
            .row("CLR-4", 1L, new BigDecimal("2"), BigDecimal.ZERO)
            .row("MIR-4", null, null, new BigDecimal("-1250.5"))
            .bold("Total", 9, new BigDecimal("43.1125"), new BigDecimal("1416227.73"))
            .build();

    @Test
    void cellsShowTheirColumnsDecimalsAndNumbersAlignRight() {
        PrintTable.Table t = PrintTable.of(sheet);

        assertThat(t.title()).isEqualTo("Stock summary");
        assertThat(t.subtitle()).isEqualTo("By glass, on 10/10/2026");
        assertThat(t.numeric()).containsExactly(false, true, true, true);
        assertThat(t.rows().get(0).cells()).extracting(PrintTable.Cell::text).containsExactly("CLR-6", "8", "41.1125", "1,417,478.23");
        // 2 and 0 take their column's decimals: 2.0000 m², 0.00 RWF
        assertThat(t.rows().get(1).cells()).extracting(PrintTable.Cell::text).containsExactly("CLR-4", "1", "2.0000", "0.00");
        assertThat(t.rows().get(2).cells()).extracting(PrintTable.Cell::text).containsExactly("MIR-4", "", "", "-1,250.50");
        assertThat(t.rows().get(2).cells().get(1).number()).isFalse();
        assertThat(t.rows().get(3).bold()).isTrue();
        assertThat(t.isEmpty()).isFalse();
    }

    @Test
    void textOfDatesCountsAndLongAmounts() {
        assertThat(PrintTable.text(LocalDate.of(2026, 10, 9))).isEqualTo("09/10/2026");
        assertThat(PrintTable.text(1234567L)).isEqualTo("1,234,567");
        assertThat(PrintTable.text(new BigDecimal("0.123456"))).isEqualTo("0.1235");      // at most 4 decimals
        assertThat(PrintTable.text(null)).isEmpty();
        assertThat(PrintTable.amount(new BigDecimal("2.5"), 0)).isEqualTo("3");
    }

    @Test
    void moreThanSixColumnsPrintLandscape() {
        Excel.Sheet wide = Excel.sheet("W", "Wide", null, "1", "2", "3", "4", "5", "6", "7").build();
        assertThat(PrintTable.landscape(sheet)).isFalse();
        assertThat(PrintTable.landscape(sheet, wide)).isTrue();
        assertThat(PrintTable.of(wide).isEmpty()).isTrue();
    }

    @Test
    void excelShowsEachColumnWithItsMostDecimals() throws Exception {
        assertThat(Excel.scales(sheet)).containsExactly(0, 0, 4, 2);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(Excel.write(sheet)))) {
            Sheet s = wb.getSheetAt(0);
            Cell area = s.getRow(5).getCell(2);          // title, subtitle, blank, header, CLR-6, CLR-4
            Cell value = s.getRow(5).getCell(3);
            assertThat(area.getNumericCellValue()).isEqualTo(2.0);
            assertThat(area.getCellStyle().getDataFormatString()).isEqualTo("#,##0.0000");
            assertThat(value.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(s.getRow(4).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
        }
    }
}
