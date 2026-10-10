package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How long glass has been held (RPT-02): off-cut age bands, slow-moving glass and the units held longest. */
class StockAgeingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final Product clear6 = product("CLR-6", "27000");
    private final Product mirror4 = product("MIR-4", null);

    @Test
    void bandsCountTheDaysSinceTheUnitWasCreated() {
        assertThat(StockAgeing.Band.of(0)).isEqualTo(StockAgeing.Band.DAYS_0_30);
        assertThat(StockAgeing.Band.of(30)).isEqualTo(StockAgeing.Band.DAYS_0_30);
        assertThat(StockAgeing.Band.of(31)).isEqualTo(StockAgeing.Band.DAYS_31_90);
        assertThat(StockAgeing.Band.of(180)).isEqualTo(StockAgeing.Band.DAYS_91_180);
        assertThat(StockAgeing.Band.of(181)).isEqualTo(StockAgeing.Band.OVER_180);
        assertThat(unit("U1", clear6, UnitKind.OFFCUT, "0.5", TODAY.plusDays(1)).days(TODAY)).isZero();   // never negative
    }

    @Test
    void offcutsPerGlassByAgeBandWithTheTotalLast() {
        List<StockAgeing.Unit> units = List.of(
                unit("U1", clear6, UnitKind.OFFCUT, "0.5000", TODAY.minusDays(3)),
                unit("U2", clear6, UnitKind.OFFCUT, "0.2500", TODAY.minusDays(45)),
                unit("U3", clear6, UnitKind.OFFCUT, "0.3000", TODAY.minusDays(200)),
                unit("U4", mirror4, UnitKind.OFFCUT, "0.4000", TODAY.minusDays(10)),
                unit("U5", clear6, UnitKind.SHEET, "7.2225", TODAY.minusDays(300)));     // a sheet is no off-cut

        List<StockAgeing.AgeRow> rows = StockAgeing.offcuts(units, TODAY);

        assertThat(rows).hasSize(3);
        StockAgeing.AgeRow clear = rows.get(0);
        assertThat(clear.product().getCode()).isEqualTo("CLR-6");
        assertThat(clear.pieces()).isEqualTo(3);
        assertThat(clear.areaM2()).isEqualByComparingTo("1.05");
        assertThat(clear.get(StockAgeing.Band.DAYS_0_30).areaM2()).isEqualByComparingTo("0.5");
        assertThat(clear.get(StockAgeing.Band.DAYS_31_90).pieces()).isEqualTo(1);
        assertThat(clear.get(StockAgeing.Band.DAYS_91_180).isEmpty()).isTrue();
        assertThat(clear.get(StockAgeing.Band.OVER_180).areaM2()).isEqualByComparingTo("0.3");
        assertThat(clear.value()).isEqualByComparingTo("28350.00");                      // 1.05 x 27,000
        assertThat(rows.get(1).value()).isEqualByComparingTo("0");                       // MIR-4 has no MAC yet
        StockAgeing.AgeRow total = rows.get(2);
        assertThat(total.product()).isNull();
        assertThat(total.pieces()).isEqualTo(4);
        assertThat(total.get(StockAgeing.Band.DAYS_0_30).pieces()).isEqualTo(2);
        assertThat(StockAgeing.offcuts(List.of(), TODAY)).hasSize(1);                   // only the (empty) total
    }

    @Test
    void slowMovingIsWhatIsHeldLongerThanTheDaysWithWhatTheGlassSold() {
        List<StockAgeing.Unit> units = List.of(
                unit("U1", clear6, UnitKind.SHEET, "7.2225", TODAY.minusDays(120)),
                unit("U2", clear6, UnitKind.SHEET, "7.2225", TODAY.minusDays(5)),
                unit("U3", mirror4, UnitKind.SHEET, "4.0000", TODAY.minusDays(91)),
                unit("U4", mirror4, UnitKind.SHEET, "4.0000", TODAY.minusDays(90)));      // exactly 90 days is not older
        Map<UUID, StockAgeing.Sold> sold = Map.of(clear6.getId(), new StockAgeing.Sold(new BigDecimal("14.4450"), TODAY.minusDays(2)));

        List<StockAgeing.SlowRow> rows = StockAgeing.slow(units, sold, TODAY, 90);

        assertThat(rows).extracting(r -> r.product().getCode()).containsExactly("CLR-6", "MIR-4");   // largest value first
        StockAgeing.SlowRow clear = rows.get(0);
        assertThat(clear.pieces()).isEqualTo(2);
        assertThat(clear.oldPieces()).isEqualTo(1);
        assertThat(clear.oldAreaM2()).isEqualByComparingTo("7.2225");
        assertThat(clear.oldValue()).isEqualByComparingTo("195007.50");
        assertThat(clear.value()).isEqualByComparingTo("390015.00");
        assertThat(clear.isUnsold()).isFalse();
        assertThat(clear.lastSold()).isEqualTo(TODAY.minusDays(2));
        StockAgeing.SlowRow mirror = rows.get(1);
        assertThat(mirror.oldPieces()).isEqualTo(1);
        assertThat(mirror.isUnsold()).isTrue();
        assertThat(mirror.lastSold()).isNull();
        assertThat(StockAgeing.total(rows).oldPieces()).isEqualTo(2);
        assertThat(StockAgeing.slow(units, sold, TODAY, 365)).isEmpty();
    }

    @Test
    void unitsHeldLongerOldestFirstThenByCode() {
        List<StockAgeing.Unit> units = List.of(
                unit("U-B", clear6, UnitKind.SHEET, "1", TODAY.minusDays(100)),
                unit("U-A", clear6, UnitKind.SHEET, "1", TODAY.minusDays(100)),
                unit("U-C", clear6, UnitKind.SHEET, "1", TODAY.minusDays(200)),
                unit("U-D", clear6, UnitKind.SHEET, "1", TODAY.minusDays(10)));

        assertThat(StockAgeing.olderThan(units, TODAY, 90)).extracting(StockAgeing.Unit::code).containsExactly("U-C", "U-A", "U-B");
        assertThat(StockAgeing.oldestFirst(units)).extracting(StockAgeing.Unit::code).containsExactly("U-C", "U-A", "U-B", "U-D");
        assertThat(units.get(0).getValue()).isEqualByComparingTo("27000.00");
    }

    private static StockAgeing.Unit unit(String code, Product product, UnitKind kind, String area, LocalDate since) {
        return new StockAgeing.Unit(UUID.randomUUID(), code, product, kind, 1000, 1000, new BigDecimal(area), null, StockStatus.AVAILABLE, since);
    }

    private static Product product(String code, String mac) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setMacPerM2(mac == null ? null : new BigDecimal(mac));
        return p;
    }
}
