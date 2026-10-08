package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Stock summary at MAC (INV-09) and reorder alerts (INV-10). */
class StockSummaryTest {

    private final Product clear6 = product("CLR-6", "34478.0353", "50");
    private final Product clear4 = product("CLR-4", null, "20");
    private final Product mirror4 = product("MIR-4", "13204.0923", null);
    private final Location r1 = location("WH-A-R01");
    private final Location r2 = location("WH-A-R02");
    private final Map<UUID, Product> products = Map.of(clear6.getId(), clear6, clear4.getId(), clear4, mirror4.getId(), mirror4);
    private final Map<UUID, Location> locations = Map.of(r1.getId(), r1, r2.getId(), r2);
    private final List<StockSummary.Fact> facts = List.of(
            new StockSummary.Fact(clear6.getId(), r1.getId(), StockStatus.AVAILABLE, 5, new BigDecimal("36.1125")),
            new StockSummary.Fact(clear6.getId(), r2.getId(), StockStatus.RESERVED, 3, new BigDecimal("5.0000")),
            new StockSummary.Fact(mirror4.getId(), r2.getId(), StockStatus.AVAILABLE, 2, new BigDecimal("8.9304")),
            new StockSummary.Fact(clear4.getId(), r1.getId(), StockStatus.AVAILABLE, 1, new BigDecimal("2.0000")));

    @Test
    void byProductTheValueIsTheM2TimesTheMacRoundedOnce() {
        List<StockSummary.Row> rows = StockSummary.group(facts, StockSummary.GroupBy.PRODUCT, products, locations);

        assertThat(rows).extracting(r -> r.product().getCode()).containsExactly("CLR-4", "CLR-6", "MIR-4");
        StockSummary.Row clear = rows.get(1);
        assertThat(clear.pieces()).isEqualTo(8);
        assertThat(clear.areaM2()).isEqualByComparingTo("41.1125");
        // 41.1125 x 34,478.0353 = 1,417,478.23
        assertThat(clear.value()).isEqualByComparingTo("1417478.23");
        assertThat(rows.get(0).value()).isEqualByComparingTo("0");   // no MAC yet
        StockSummary.Row total = StockSummary.total(rows);
        assertThat(total.pieces()).isEqualTo(11);
        assertThat(total.areaM2()).isEqualByComparingTo("52.0429");
        // + 8.9304 x 13,204.0923 = 117,917.83
        assertThat(total.value()).isEqualByComparingTo("1535396.06");
    }

    @Test
    void byLocationAndByStatus() {
        List<StockSummary.Row> byPlace = StockSummary.group(facts, StockSummary.GroupBy.LOCATION, products, locations);
        assertThat(byPlace).extracting(r -> r.location().getCode()).containsExactly("WH-A-R01", "WH-A-R02");
        assertThat(byPlace.get(1).pieces()).isEqualTo(5);

        List<StockSummary.Row> byState = StockSummary.group(facts, StockSummary.GroupBy.STATUS, products, locations);
        assertThat(byState).extracting(StockSummary.Row::status).containsExactly(StockStatus.AVAILABLE, StockStatus.RESERVED);
        assertThat(byState.get(0).pieces()).isEqualTo(8);
    }

    @Test
    void productsBelowTheirReorderLevelCountOnlyAvailableM2() {
        List<StockSummary.Reorder> reorder = StockSummary.reorder(facts, products.values());

        // CLR-6: 36.1125 available (the reserved 5 m² do not count) < 50; CLR-4: 2 < 20; MIR-4 has no level
        assertThat(reorder).extracting(r -> r.product().getCode()).containsExactly("CLR-4", "CLR-6");
        assertThat(reorder.get(0).getShortM2()).isEqualByComparingTo("18");
        assertThat(reorder.get(1).availableM2()).isEqualByComparingTo("36.1125");
        assertThat(reorder.get(1).getShortM2()).isEqualByComparingTo("13.8875");
    }

    private static Product product(String code, String mac, String level) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setMacPerM2(mac == null ? null : new BigDecimal(mac));
        p.setReorderLevelM2(level == null ? null : new BigDecimal(level));
        p.setEnabled(true);
        return p;
    }

    private static Location location(String code) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        return l;
    }
}
