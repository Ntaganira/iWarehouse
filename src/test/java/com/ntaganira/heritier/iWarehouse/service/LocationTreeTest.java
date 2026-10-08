package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Tree order and code suggestions for locations (MD-02). */
class LocationTreeTest {

    private record Node(UUID id, UUID parent, String code) {
    }

    private static Node node(String code, Node parent) {
        return new Node(UUID.randomUUID(), parent == null ? null : parent.id(), code);
    }

    private static List<String> lines(List<LocationTree.Row<Node>> rows) {
        return rows.stream().map(r -> "  ".repeat(r.depth()) + r.item().code() + "(" + r.children() + ")").toList();
    }

    @Test
    void childrenFollowTheirParentInCodeOrder() {
        Node wh = node("WH", null);
        Node zoneB = node("WH-B", wh);
        Node zoneA = node("WH-A", wh);
        Node r2 = node("WH-A-R02", zoneA);
        Node r1 = node("WH-A-R01", zoneA);
        Node depot = node("DEPOT", null);
        List<Node> shuffled = List.of(r2, zoneB, depot, r1, wh, zoneA);

        List<LocationTree.Row<Node>> rows = LocationTree.flatten(shuffled, Node::id, Node::parent, Comparator.comparing(Node::code));

        assertThat(lines(rows)).containsExactly(
                "DEPOT(0)",
                "WH(2)",
                "  WH-A(2)",
                "    WH-A-R01(0)",
                "    WH-A-R02(0)",
                "  WH-B(0)");
    }

    @Test
    void anItemWhoseParentIsFilteredOutIsShownAsARoot() {
        Node wh = node("WH", null);
        Node zone = node("WH-A", wh);
        Node rack = node("WH-A-R01", zone);

        List<LocationTree.Row<Node>> rows = LocationTree.flatten(List.of(wh, rack), Node::id, Node::parent, Comparator.comparing(Node::code));

        assertThat(lines(rows)).containsExactly("WH(0)", "WH-A-R01(0)");
    }

    @Test
    void zonesTakeTheNextFreeLetter() {
        assertThat(LocationTree.suggestCode("WH", LocationType.ZONE, Set.of("WH"))).isEqualTo("WH-A");
        assertThat(LocationTree.suggestCode("WH", LocationType.ZONE, Set.of("WH", "WH-A", "WH-B"))).isEqualTo("WH-C");
    }

    @Test
    void racksAndSlotsAreNumberedFromOneSkippingCodesInUse() {
        assertThat(LocationTree.suggestCode("WH-A", LocationType.RACK, Set.of("WH-A-R01", "WH-A-R02", "WH-A-OC")))
                .isEqualTo("WH-A-R03");
        assertThat(LocationTree.suggestCode("WH-A", LocationType.RACK, Set.of("WH-A-R02"))).isEqualTo("WH-A-R01");
        assertThat(LocationTree.suggestCode("WH-A-R01", LocationType.SLOT, Set.of())).isEqualTo("WH-A-R01-S01");
    }

    @Test
    void noSuggestionForRootsOrWhenTheCodeWouldBeTooLong() {
        assertThat(LocationTree.suggestCode("WH", LocationType.SITE, Set.of())).isNull();
        assertThat(LocationTree.suggestCode(null, LocationType.ZONE, Set.of())).isNull();
        String longParent = "A".repeat(27);
        assertThat(LocationTree.suggestCode(longParent, LocationType.RACK, Set.of())).isNull();
    }

    @Test
    void typesNestSiteZoneRackSlot() {
        assertThat(LocationType.SITE.getChildType()).isEqualTo(LocationType.ZONE);
        assertThat(LocationType.ZONE.getChildType()).isEqualTo(LocationType.RACK);
        assertThat(LocationType.RACK.getChildType()).isEqualTo(LocationType.SLOT);
        assertThat(LocationType.SLOT.getChildType()).isNull();
        assertThat(LocationType.VEHICLE.isRoot()).isTrue();
        assertThat(LocationType.VEHICLE.isManual()).isFalse();
        assertThat(LocationType.VEHICLE.getChildType()).isNull();
    }
}
