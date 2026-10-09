package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.LocationDto;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.RackOrientation;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.LocationRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Location tree rules, rack limits and labels (MD-02, MD-03). */
class LocationServiceTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), KIGALI);

    private LocationRepository repo;
    private StockUnitRepository unitRepo;
    private LocationService service;

    private Location site;
    private Location zone;
    private Location rack;
    private final List<Location> all = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(LocationRepository.class);
        when(repo.save(any(Location.class))).thenAnswer(i -> i.getArgument(0));
        unitRepo = mock(StockUnitRepository.class);
        when(repo.findAllByOrderByCodeAsc()).thenAnswer(i -> all.stream()
                .sorted(Comparator.comparing(Location::getCode)).toList());
        service = new LocationService(repo, unitRepo, CLOCK);
        site = location("WH", LocationType.SITE, null);
        zone = location("WH-A", LocationType.ZONE, site);
        rack = location("WH-A-R01", LocationType.RACK, zone);
    }

    @Test
    void withoutParentANewLocationIsASite() {
        Location created = service.create(dto(null, "DEPOT"));

        assertThat(created.getType()).isEqualTo(LocationType.SITE);
        assertThat(created.getParentId()).isNull();
    }

    @Test
    void theParentDecidesTheType() {
        assertThat(service.create(dto(site, "WH-B")).getType()).isEqualTo(LocationType.ZONE);
        assertThat(service.create(dto(zone, "WH-A-R02")).getType()).isEqualTo(LocationType.RACK);
        Location slot = service.create(dto(rack, "WH-A-R01-S01"));
        assertThat(slot.getType()).isEqualTo(LocationType.SLOT);
        assertThat(slot.getParentId()).isEqualTo(rack.getId());
    }

    @Test
    void nothingGoesUnderASlotOrAVehicle() {
        Location slot = location("WH-A-R01-S01", LocationType.SLOT, rack);
        Location truck = location("RAC123A", LocationType.VEHICLE, null);

        assertThatThrownBy(() -> service.create(dto(slot, "X1"))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("location.parent.noChildren"));
        assertThatThrownBy(() -> service.create(dto(truck, "X2"))).isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void nothingGoesUnderAnInactiveParent() {
        zone.setEnabled(false);

        assertThatThrownBy(() -> service.create(dto(zone, "WH-A-R09"))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("location.parent.inactive"));
    }

    @Test
    void codesAreUniqueAcrossTheTree() {
        when(repo.existsByCode("WH-A-R01")).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto(zone, "WH-A-R01"))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("code"));
    }

    @Test
    void onlyRacksKeepLimitsOrientationAndTheOffcutFlag() {
        LocationDto rackDto = dto(zone, "WH-A-OC2");
        rackDto.setMaxWeightKg(1000);
        rackDto.setMaxPieces(100);
        rackDto.setOffcut(true);
        Location newRack = service.create(rackDto);
        assertThat(newRack.getMaxWeightKg()).isEqualTo(1000);
        assertThat(newRack.isOffcut()).isTrue();
        assertThat(newRack.getOrientation()).isEqualTo(RackOrientation.BOTH); // none chosen

        LocationDto zoneDto = dto(site, "WH-C");
        zoneDto.setMaxWeightKg(5000);
        zoneDto.setOffcut(true);
        zoneDto.setOrientation(RackOrientation.VERTICAL);
        Location newZone = service.create(zoneDto);
        assertThat(newZone.getMaxWeightKg()).isNull();
        assertThat(newZone.isOffcut()).isFalse();
        assertThat(newZone.getOrientation()).isNull();
    }

    @Test
    void deactivatingWaitsForTheSubLocations() {
        when(repo.countByParentIdAndEnabledTrue(zone.getId())).thenReturn(2L);

        assertThatThrownBy(() -> service.setEnabled(zone.getId(), false)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo("location.disable.children");
            assertThat(e.getArgs()).containsExactly("WH-A", 2L);
        });
        assertThat(zone.isEnabled()).isTrue();

        assertThat(service.setEnabled(rack.getId(), false).isEnabled()).isFalse();
    }

    @Test
    void reactivatingNeedsAnActiveParent() {
        zone.setEnabled(false);
        rack.setEnabled(false);

        assertThatThrownBy(() -> service.setEnabled(rack.getId(), true)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getArgs()).containsExactly("WH-A-R01", "WH-A"));
        assertThat(service.setEnabled(zone.getId(), true).isEnabled()).isTrue();
    }

    @Test
    void vehicleLocationsAreLeftToTheFleetModule() {
        Location truck = location("RAC123A", LocationType.VEHICLE, null);

        assertThatThrownBy(() -> service.setEnabled(truck.getId(), false)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("location.vehicle.managed"));
        assertThatThrownBy(() -> service.update(truck.getId(), dto(null, "RAC123A"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void ancestorsRunFromTheSiteDown() {
        assertThat(service.ancestors(rack)).extracting(Location::getCode).containsExactly("WH", "WH-A");
        assertThat(service.ancestors(site)).isEmpty();
    }

    @Test
    void suggestedCodeSkipsCodesInUse() {
        when(repo.findAllCodes()).thenReturn(List.of("WH", "WH-A", "WH-A-R01", "WH-A-R02"));

        assertThat(service.suggestCode(zone)).isEqualTo("WH-A-R03");
        assertThat(service.suggestCode(site)).isEqualTo("WH-B");
    }

    private Location location(String code, LocationType type, Location parent) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(type);
        l.setParentId(parent == null ? null : parent.getId());
        if (type == LocationType.RACK) {
            l.setOrientation(RackOrientation.VERTICAL);
        }
        when(repo.findById(l.getId())).thenReturn(Optional.of(l));
        all.add(l);
        return l;
    }

    private static LocationDto dto(Location parent, String code) {
        LocationDto dto = new LocationDto();
        dto.setParentId(parent == null ? null : parent.getId());
        dto.setCode(code);
        return dto;
    }

    @Test
    void aLocationHoldingStockStaysActive() {
        when(unitRepo.countByLocation_IdAndStatusIn(eq(rack.getId()), any())).thenReturn(3L);
        assertThatThrownBy(() -> service.setEnabled(rack.getId(), false)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo("location.disable.stock");
            assertThat(e.getArgs()).containsExactly("WH-A-R01", 3L);
        });
    }

    // ---------------------------------------------------------------- labels (MD-02)

    @Test
    void printingLabelsCoversTheActiveRacksAndSlotsUnderThePlace() {
        Location slot = location("WH-A-R01-S01", LocationType.SLOT, rack);
        Location oldRack = location("WH-A-R09", LocationType.RACK, zone);
        oldRack.setEnabled(false);
        location("WH-A-R09-S01", LocationType.SLOT, oldRack).setEnabled(false);

        LocationService.LabelRun run = service.printLabels(zone.getId());

        assertThat(run.places()).extracting(Location::getCode).containsExactly("WH-A-R01", "WH-A-R01-S01");
        assertThat(run.fixed()).containsExactly(rack, slot);
        assertThat(rack.getLabelPrintedAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 0));
        assertThat(rack.getLabelPrintedBy()).isEqualTo("system");
        assertThat(zone.isLabelled()).isFalse(); // zones and sites carry no label
        assertThat(service.labelledPlaces(site.getId())).containsExactly(rack, slot);
    }

    @Test
    void aReprintKeepsTheFirstPrint() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 1, 9, 0);
        rack.setLabelPrintedAt(first);
        rack.setLabelPrintedBy("supervisor1");

        LocationService.LabelRun run = service.printLabels(rack.getId());

        assertThat(run.places()).containsExactly(rack);
        assertThat(run.fixed()).isEmpty();
        assertThat(rack.getLabelPrintedAt()).isEqualTo(first);
        assertThat(rack.getLabelPrintedBy()).isEqualTo("supervisor1");
    }

    @Test
    void aPlaceWithoutRacksHasNoLabels() {
        Location depot = location("DEPOT", LocationType.SITE, null);

        assertThatThrownBy(() -> service.printLabels(depot.getId())).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getMessageKey()).isEqualTo("location.labels.none");
            assertThat(e.getArgs()).containsExactly("DEPOT");
        });
        assertThat(service.labelPlaces(depot.getId())).isEmpty();
    }

    @Test
    void aPrintedLabelFixesTheCode() {
        rack.setLabelPrintedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        LocationDto renamed = dto(zone, "WH-A-R11");

        assertThatThrownBy(() -> service.update(rack.getId(), renamed)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getField()).isEqualTo("code");
            assertThat(e.getMessageKey()).isEqualTo("location.code.fixed");
        });
        assertThat(rack.getCode()).isEqualTo("WH-A-R01");

        LocationDto sameCode = dto(zone, "WH-A-R01");
        sameCode.setName("Rack one");
        sameCode.setMaxPieces(40);
        assertThat(service.update(rack.getId(), sameCode).getName()).isEqualTo("Rack one"); // the rest stays editable
    }

    @Test
    void aScannedPlaceCodeIsFoundWhateverItsCase() {
        when(repo.findByCode("WH-A-R01")).thenReturn(Optional.of(rack));

        assertThat(service.findByCode(" wh-a-r01 ")).contains(rack);
        assertThat(service.findByCode("  ")).isEmpty();
    }
}
