package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CuttingJobDto;
import com.ntaganira.heritier.iWarehouse.dto.CuttingResultDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cutting jobs (PRD-01..08): drafts, taking a source that fits (PRD-02), recording the cut with AT-02
 * (units, cullet, costs by area, MAC), breakage, the 1% area check, racks, putting a sheet back,
 * cancelling and cutting the rest.
 */
class CuttingJobServiceTest {

    private CuttingJobRepository repo;
    private CuttingJobOutputRepository outputRepo;
    private ProductRepository productRepo;
    private CustomerRepository customerRepo;
    private StockUnitRepository unitRepo;
    private StockMovementRepository movementRepo;
    private StockCostEntryRepository costEntryRepo;
    private StockAdjustmentLineRepository adjustmentLineRepo;
    private CuttingJobService service;

    private final Location zone = location("WH-A", LocationType.ZONE, null, false, null, null);
    private final Location rack = location("WH-A-R04", LocationType.RACK, zone.getId(), false, 30, 3000);
    private final Location offcutRack = location("WH-A-OC", LocationType.RACK, zone.getId(), true, 100, 1000);
    private final Product clear6 = product("CLR-6", GlassType.CLEAR);
    private final Product tempered6 = product("TMP-6", GlassType.TEMPERED);
    private final Customer customer = customer("Kigali Builders");
    private List<Object[]> loads;
    private StockUnit sheet;

    @BeforeEach
    void setUp() {
        repo = mock(CuttingJobRepository.class);
        outputRepo = mock(CuttingJobOutputRepository.class);
        productRepo = mock(ProductRepository.class);
        customerRepo = mock(CustomerRepository.class);
        unitRepo = mock(StockUnitRepository.class);
        movementRepo = mock(StockMovementRepository.class);
        costEntryRepo = mock(StockCostEntryRepository.class);
        LocationRepository locationRepo = mock(LocationRepository.class);
        adjustmentLineRepo = mock(StockAdjustmentLineRepository.class);
        ProcessingServiceRepository serviceRepo = mock(ProcessingServiceRepository.class);
        when(locationRepo.findAll()).thenReturn(List.of(zone, rack, offcutRack));
        when(serviceRepo.findAllByOrderByEnabledDescCodeAsc()).thenReturn(List.of(service("DRILLING"), service("EDGING")));
        when(productRepo.findById(clear6.getId())).thenReturn(Optional.of(clear6));
        when(productRepo.findById(tempered6.getId())).thenReturn(Optional.of(tempered6));
        when(productRepo.lockAllById(any())).thenReturn(List.of(clear6));
        when(customerRepo.findById(customer.getId())).thenReturn(Optional.of(customer));

        DocumentNumberService numbers = mock(DocumentNumberService.class);
        AtomicInteger unitNo = new AtomicInteger(41);
        AtomicInteger jobNo = new AtomicInteger(1);
        when(numbers.next(DocumentType.STOCK_UNIT)).thenAnswer(a -> String.format("U-WH-%06d", unitNo.getAndIncrement()));
        when(numbers.next(DocumentType.CUTTING_JOB)).thenAnswer(a -> String.format("CUT-WH-2026-%06d", jobNo.getAndIncrement()));
        when(unitRepo.save(any())).thenAnswer(a -> {
            StockUnit u = a.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(repo.save(any())).thenAnswer(a -> {
            CuttingJob j = a.getArgument(0);
            j.setId(UUID.randomUUID());
            return j;
        });
        SettingService settings = mock(SettingService.class);
        when(settings.getDecimal(SettingKey.OFFCUT_MIN_AREA)).thenReturn(new BigDecimal("0.25"));
        when(settings.getInt(SettingKey.OFFCUT_MIN_SIDE)).thenReturn(300);
        when(settings.getDecimal(SettingKey.GLASS_DENSITY)).thenReturn(new BigDecimal("2.5"));

        // The AT-01 sheet: 3210 x 2250 mm, landed cost 249,017.61 RWF, on WH-A-R04 with 19 others.
        sheet = unit("U-WH-000021", 3210, 2250, "108.34", "249017.61", StockStatus.AVAILABLE, rack);
        when(unitRepo.findById(sheet.getId())).thenReturn(Optional.of(sheet));
        loads = new ArrayList<>();
        loads.add(new Object[]{rack.getId(), 20L, new BigDecimal("2166.80")});
        when(unitRepo.loadByLocation(any())).thenAnswer(a -> loads);
        when(unitRepo.sumArea(eq(clear6.getId()), any())).thenReturn(new BigDecimal("144.4500"));

        Clock clock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneId.of("Africa/Kigali"));
        StockService stockService = new StockService(unitRepo, movementRepo, costEntryRepo, adjustmentLineRepo, mock(StockCountRepository.class), mock(SalesInvoiceLineRepository.class), mock(TripRepository.class), locationRepo, numbers, clock);
        service = new CuttingJobService(repo, mock(CuttingJobLineRepository.class), outputRepo, productRepo, customerRepo,
                serviceRepo, unitRepo, stockService, mock(PostingService.class), numbers, settings, clock);
    }

    // ---------------------------------------------------------------- drafts (PRD-01)

    @Test
    void temperedGlassCannotBeCut() {
        CuttingJobDto dto = form(tempered6, line(1000, 500, 1));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("cutting.product.notCuttable");
                    assertThat(((BusinessException) e).getField()).isEqualTo("productId");
                });
    }

    @Test
    void piecesForACustomerNameTheCustomer() {
        CuttingJobDto dto = form(clear6, line(1000, 500, 1));
        dto.setPurpose(CuttingPurpose.CUSTOMER);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.customer.required");
    }

    @Test
    void aNewJobIsNumberedWithItsSizesInOrderAndProcessingSorted() {
        CuttingJobDto dto = form(clear6, line(2000, 1000, 1), line(1500, 1000, 2));
        dto.getLines().get(0).setProcessing(new ArrayList<>(List.of("EDGING", "DRILLING", "EDGING")));
        dto.setCustomerId(customer.getId());   // ignored: for stock
        dto.setCustomerRef("Q-17");

        CuttingJob job = service.create(dto);

        assertThat(job.getNumber()).isEqualTo("CUT-WH-2026-000001");
        assertThat(job.getStatus()).isEqualTo(CuttingJobStatus.DRAFT);
        assertThat(job.getCustomer()).isNull();
        assertThat(job.getCustomerRef()).isNull();
        assertThat(job.getLines()).extracting(CuttingJobLine::getLineNo).containsExactly(1, 2);
        assertThat(job.getLines().get(0).getProcessing()).isEqualTo("DRILLING,EDGING");
        assertThat(job.getLines().get(1).getProcessing()).isNull();
        assertThat(job.getPieces()).isEqualTo(3);
        assertThat(job.getPiecesWantedM2()).isEqualByComparingTo("5.0000");
    }

    @Test
    void processingMustBeAServiceOffered() {
        CuttingJobDto dto = form(clear6, line(1000, 500, 1));
        dto.getLines().get(0).setProcessing(new ArrayList<>(List.of("PAINTING")));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("field", "lines[0].processing");
    }

    // ---------------------------------------------------------------- the source (PRD-02)

    @Test
    void takingAFittingSheetReservesItForTheJob() {
        CuttingJob job = draftAt02();

        service.start(job.getId(), sheet.getId(), null);

        assertThat(job.getStatus()).isEqualTo(CuttingJobStatus.IN_PROGRESS);
        assertThat(job.getSourceUnitId()).isEqualTo(sheet.getId());
        assertThat(job.getSourceCode()).isEqualTo("U-WH-000021");
        assertThat(job.getSourceAreaM2()).isEqualByComparingTo("7.2225");
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.IN_CUTTING);
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(MovementType.CUTTING_START);
        assertThat(movement.getValue().getFromStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(movement.getValue().getRefNumber()).isEqualTo(job.getNumber());
    }

    @Test
    void aSheetOfOtherGlassIsRefused() {
        CuttingJob job = draftAt02();
        StockUnit mirror = unit("U-WH-000099", 3210, 2250, "108.34", "1000.00", StockStatus.AVAILABLE, rack);
        mirror.setProduct(product("MIR-4", GlassType.MIRROR));
        when(unitRepo.findById(mirror.getId())).thenReturn(Optional.of(mirror));

        assertThatThrownBy(() -> service.start(job.getId(), mirror.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.source.wrongProduct");
        assertThat(mirror.getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void everyPieceMustFitTheSourceEitherWayRound() {
        CuttingJob job = draftAt02();
        StockUnit offcut = unit("U-WH-000050", 1600, 2100, "50.40", "100.00", StockStatus.AVAILABLE, offcutRack);
        when(unitRepo.findById(offcut.getId())).thenReturn(Optional.of(offcut));

        // 2000 x 1000 fits 1600 x 2100 turned; the pieces need 5 m², the off-cut has 3.36 m²
        assertThatThrownBy(() -> service.start(job.getId(), offcut.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.source.tooSmall");

        StockUnit strip = unit("U-WH-000051", 3210, 900, "43.34", "100.00", StockStatus.AVAILABLE, offcutRack);
        when(unitRepo.findById(strip.getId())).thenReturn(Optional.of(strip));
        assertThatThrownBy(() -> service.start(job.getId(), strip.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.source.pieceTooBig");
    }

    @Test
    void aReservedUnitCannotBeTaken() {
        CuttingJob job = draftAt02();
        sheet.setStatus(StockStatus.RESERVED);

        assertThatThrownBy(() -> service.start(job.getId(), sheet.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.source.notAvailable");
    }

    @Test
    void puttingTheSheetBackMakesItAvailableAndTheJobADraft() {
        CuttingJob job = takenAt02();

        service.release(job.getId(), "Wrong sheet");

        assertThat(job.getStatus()).isEqualTo(CuttingJobStatus.DRAFT);
        assertThat(job.getSourceUnitId()).isNull();
        assertThat(job.getOperatorName()).isNull();
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, atLeastOnce()).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(MovementType.CUTTING_RELEASE);
        assertThat(movement.getValue().getReason()).isEqualTo("Wrong sheet");
    }

    @Test
    void cancellingAJobBeingCutPutsTheSheetBack() {
        CuttingJob job = takenAt02();

        service.cancel(job.getId(), "Customer changed the order");

        assertThat(job.getStatus()).isEqualTo(CuttingJobStatus.CANCELLED);
        assertThat(job.getCancelReason()).isEqualTo("Customer changed the order");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    @Test
    void piecesGivenUpComeOffTheSalesDraftJobsAndAnEmptyJobIsCancelled() {   // POS-09
        UUID invoiceId = UUID.randomUUID();
        UUID sizeA = UUID.randomUUID();
        UUID sizeB = UUID.randomUUID();
        CuttingJob cutting = draftAt02();                                      // being cut: never touched
        cutting.setStatus(CuttingJobStatus.IN_PROGRESS);
        cutting.getLines().get(0).setSalesLineId(sizeA);
        CuttingJob draft = draftAt02();
        draft.setNumber("CUT-WH-2026-000010");
        draft.getLines().get(0).setSalesLineId(sizeA);                         // 1 piece of A
        draft.getLines().get(1).setSalesLineId(sizeB);                         // 2 pieces of B
        when(repo.findBySalesInvoiceIdOrderByNumberAsc(invoiceId)).thenReturn(List.of(cutting, draft));

        assertThat(service.takeOffSale(invoiceId, sizeB, 1, "Given up")).isEqualTo(1);
        assertThat(draft.getLines()).extracting(CuttingJobLine::getQuantity).containsExactly(1, 1);

        assertThat(service.takeOffSale(invoiceId, sizeA, 2, "Given up")).isEqualTo(1);   // only the draft's piece
        assertThat(draft.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getSalesLineId()).isEqualTo(sizeB);
            assertThat(l.getLineNo()).isEqualTo(1);
        });
        assertThat(cutting.getLines().get(0).getQuantity()).isEqualTo(1);

        assertThat(service.takeOffSale(invoiceId, sizeB, 1, "Given up on CN-WH-2026-000003")).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo(CuttingJobStatus.CANCELLED);
        assertThat(draft.getCancelReason()).isEqualTo("Given up on CN-WH-2026-000003");
        assertThat(cutting.getStatus()).isEqualTo(CuttingJobStatus.IN_PROGRESS);
    }

    // ---------------------------------------------------------------- the cut (PRD-03..07, AT-02)

    @Test
    void at02TheCutCreatesThreePiecesAndAnOffcutAndExpensesTheCulletByArea() {
        CuttingJob job = takenAt02();
        job.setPurpose(CuttingPurpose.CUSTOMER);
        job.setCustomer(customer);
        CuttingResultDto dto = result(job, 1, 2);
        dto.getLeftovers().add(leftover(1800, 1000, 1));

        CuttingJobService.CutResult cut = service.complete(job.getId(), dto);

        // 3 + 1 new units, each with its part of 249,017.61 by area
        assertThat(cut.pieces()).extracting(StockUnit::getCode).containsExactly("U-WH-000041", "U-WH-000042", "U-WH-000043");
        assertThat(cut.pieces()).extracting(StockUnit::getUnitCost).containsExactly(
                new BigDecimal("68956.07"), new BigDecimal("51717.05"), new BigDecimal("51717.05"));
        assertThat(cut.pieces()).allSatisfy(u -> {
            assertThat(u.getKind()).isEqualTo(UnitKind.CUT_PIECE);
            assertThat(u.getStatus()).isEqualTo(StockStatus.RESERVED);   // for the customer (INV-05)
            assertThat(u.getLocation()).isEqualTo(rack);
            assertThat(u.getParentUnitId()).isEqualTo(sheet.getId());
        });
        assertThat(cut.offcuts()).singleElement().satisfies(u -> {
            assertThat(u.getKind()).isEqualTo(UnitKind.OFFCUT);
            assertThat(u.getStatus()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(u.getLocation()).isEqualTo(offcutRack);
            assertThat(u.getAreaM2()).isEqualByComparingTo("1.8000");
            assertThat(u.getWeightKg()).isEqualByComparingTo("27.00");
            assertThat(u.getUnitCost()).isEqualByComparingTo("62060.47");
        });

        // The source is consumed; areas balance with 0.4225 m² of cullet posted to spoilage
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.CONSUMED);
        assertThat(sheet.getLocation()).isNull();
        assertThat(sheet.getUnitCost()).isEqualByComparingTo("249017.61");
        assertThat(job.getStatus()).isEqualTo(CuttingJobStatus.COMPLETED);
        assertThat(job.getSourceCost()).isEqualByComparingTo("249017.61");
        assertThat(job.getPiecesAreaM2()).isEqualByComparingTo("5.0000");
        assertThat(job.getOffcutAreaM2()).isEqualByComparingTo("1.8000");
        assertThat(job.getCulletAreaM2()).isEqualByComparingTo("0.4225");
        assertThat(job.getCulletKg()).isEqualByComparingTo("6.34");
        assertThat(job.getBrokenAreaM2()).isEqualByComparingTo("0");
        assertThat(job.getCulletCost()).isEqualByComparingTo("14566.97");
        assertThat(job.getBrokenCost()).isEqualByComparingTo("0");
        assertThat(job.getYieldPercent()).isEqualByComparingTo("94.15");
        assertThat(job.getLines()).extracting(CuttingJobLine::getCutQty).containsExactly(1, 2);
        assertThat(job.isShort()).isFalse();

        // The ledger: 3 pieces, 1 off-cut, the trim; parts add up to the sheet cost
        ArgumentCaptor<CuttingJobOutput> outputs = ArgumentCaptor.forClass(CuttingJobOutput.class);
        verify(outputRepo, times(5)).save(outputs.capture());
        assertThat(outputs.getAllValues()).extracting(CuttingJobOutput::getKind).containsExactly(CuttingOutputKind.PIECE,
                CuttingOutputKind.PIECE, CuttingOutputKind.PIECE, CuttingOutputKind.OFFCUT, CuttingOutputKind.CULLET);
        assertThat(outputs.getAllValues().stream().map(CuttingJobOutput::getCost).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("249017.61");
        CuttingJobOutput trim = outputs.getAllValues().get(4);
        assertThat(trim.getWidthMm()).isNull();
        assertThat(trim.getAreaM2()).isEqualByComparingTo("0.4225");

        // One movement per unit created plus the source consumed; one cost entry per unit created
        ArgumentCaptor<StockMovement> movements = ArgumentCaptor.forClass(StockMovement.class);
        verify(movementRepo, times(5)).save(movements.capture());
        assertThat(movements.getAllValues()).extracting(StockMovement::getType).containsExactly(MovementType.CUTTING_OUTPUT,
                MovementType.CUTTING_OUTPUT, MovementType.CUTTING_OUTPUT, MovementType.CUTTING_OUTPUT, MovementType.CUTTING_CONSUMED);
        ArgumentCaptor<StockCostEntry> entries = ArgumentCaptor.forClass(StockCostEntry.class);
        verify(costEntryRepo, times(4)).save(entries.capture());
        assertThat(entries.getAllValues()).allSatisfy(e -> assertThat(e.getType()).isEqualTo(CostEntryType.CUTTING));

        // Cut at the MAC, so the MAC stays
        assertThat(clear6.getMacPerM2()).isEqualByComparingTo("34478.0353");
        assertThat(cut.spoilageCost()).isEqualByComparingTo("14566.97");
    }

    @Test
    void whatACutProducedIsListedPiecesFirstThenOffcutsThenCulletWithTheTrimLast() {
        CuttingJob job = takenAt02();
        CuttingResultDto dto = result(job, 1, 2);
        dto.getLeftovers().add(leftover(1800, 1000, 1));
        dto.getLeftovers().add(leftover(200, 150, 1));
        CuttingJobService.CutResult cut = service.complete(job.getId(), dto);
        ArgumentCaptor<CuttingJobOutput> saved = ArgumentCaptor.forClass(CuttingJobOutput.class);
        verify(outputRepo, times(6)).save(saved.capture());
        // Saved in one go with the same time: the database gives them back in any order
        List<CuttingJobOutput> shuffled = new ArrayList<>(saved.getAllValues());
        Collections.reverse(shuffled);
        when(outputRepo.findByCuttingJobIdOrderByCreatedAtAscIdAsc(job.getId())).thenReturn(shuffled);
        List<StockUnit> units = new ArrayList<>(cut.pieces());
        units.addAll(cut.offcuts());
        when(unitRepo.findByParentUnitIdOrderByCode(sheet.getId())).thenReturn(units);

        CuttingJobService.Outcome outcome = service.outcome(job);

        assertThat(outcome.outputs()).extracting(CuttingJobOutput::getKind).containsExactly(CuttingOutputKind.PIECE,
                CuttingOutputKind.PIECE, CuttingOutputKind.PIECE, CuttingOutputKind.OFFCUT, CuttingOutputKind.CULLET,
                CuttingOutputKind.CULLET);
        assertThat(outcome.outputs().subList(0, 3)).extracting(o -> outcome.units().get(o.getStockUnitId()).getCode())
                .containsExactly("U-WH-000041", "U-WH-000042", "U-WH-000043");
        assertThat(outcome.outputs().get(4).getWidthMm()).isEqualTo(200);   // the measured leftover
        assertThat(outcome.outputs().get(5).getWidthMm()).isNull();         // then the trim
        assertThat(outcome.getTotalCost()).isEqualByComparingTo("249017.61");
        assertThat(outcome.getRows()).extracting(CuttingJobService.BalanceRow::areaM2).map(BigDecimal::stripTrailingZeros)
                .containsExactly(new BigDecimal("5"), new BigDecimal("1.8"), new BigDecimal("0.4225"), BigDecimal.ZERO);
    }

    @Test
    void forStockThePiecesAreAvailable() {
        CuttingJob job = takenAt02();
        CuttingResultDto dto = result(job, 1, 2);
        dto.getLeftovers().add(leftover(1800, 1000, 1));

        CuttingJobService.CutResult cut = service.complete(job.getId(), dto);

        assertThat(cut.pieces()).allSatisfy(u -> assertThat(u.getStatus()).isEqualTo(StockStatus.AVAILABLE));
    }

    @Test
    void smallLeftoversAreCulletAndBreakageIsRecordedWithItsReason() {
        CuttingJob job = takenAt02();
        CuttingResultDto dto = result(job, 1, 1);     // one 1500 x 1000 broke
        dto.getLeftovers().add(leftover(1800, 1000, 1));
        dto.getLeftovers().add(leftover(200, 2250, 1));   // 0.45 m² but only 200 mm wide: cullet
        CuttingResultDto.Broken broken = new CuttingResultDto.Broken();
        broken.setWidthMm(1500);
        broken.setHeightMm(1000);
        broken.setQuantity(1);
        broken.setReason(BreakageReason.HANDLING);
        broken.setNote("Slipped off the table");
        dto.getBroken().add(broken);

        CuttingJobService.CutResult cut = service.complete(job.getId(), dto);

        // 2.0 + 1.5 + 1.8 + 0.45 + 1.5 = 7.25 m²: 0.0275 over the sheet, within 1%, so no trim
        assertThat(cut.pieces()).hasSize(2);
        assertThat(job.getCulletAreaM2()).isEqualByComparingTo("0.4500");
        assertThat(job.getBrokenAreaM2()).isEqualByComparingTo("1.5000");
        assertThat(job.getYieldPercent()).isEqualByComparingTo("73.38");
        assertThat(job.isShort()).isTrue();
        assertThat(job.getCulletCost().add(job.getBrokenCost()).add(cut.pieces().get(0).getUnitCost())
                .add(cut.pieces().get(1).getUnitCost()).add(cut.offcuts().get(0).getUnitCost()))
                .isEqualByComparingTo("249017.61");
        ArgumentCaptor<CuttingJobOutput> outputs = ArgumentCaptor.forClass(CuttingJobOutput.class);
        verify(outputRepo, times(5)).save(outputs.capture());
        CuttingJobOutput b = outputs.getAllValues().stream().filter(o -> o.getKind() == CuttingOutputKind.BROKEN).findFirst().orElseThrow();
        assertThat(b.getReason()).isEqualTo(BreakageReason.HANDLING);
        assertThat(b.getNote()).isEqualTo("Slipped off the table");
        assertThat(b.getCost()).isEqualByComparingTo(job.getBrokenCost());
    }

    @Test
    void piecesAndLeftoversMayNotExceedTheSheetByMoreThanOnePercent() {
        CuttingJob job = takenAt02();
        CuttingResultDto dto = result(job, 1, 2);
        dto.getLeftovers().add(leftover(1800, 1300, 1));   // 2.34 m²: 7.34 m² recorded, 7.2225 m² sheet

        assertThatThrownBy(() -> service.complete(job.getId(), dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.result.overArea");
        assertThat(sheet.getStatus()).isEqualTo(StockStatus.IN_CUTTING);
        verify(outputRepo, never()).save(any());
    }

    @Test
    void aLeftoverBiggerThanTheSheetIsRefused() {
        CuttingJob job = takenAt02();
        CuttingResultDto dto = result(job, 0, 0);
        dto.getLeftovers().add(leftover(3300, 100, 1));

        assertThatThrownBy(() -> service.complete(job.getId(), dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("field", "leftovers[0].widthMm");
    }

    @Test
    void offcutsGoToAnOffcutRack() {
        CuttingJob job = takenAt02();
        CuttingResultDto dto = result(job, 1, 2);
        dto.getLeftovers().add(leftover(1800, 1000, 1));
        dto.setOffcutLocationId(rack.getId());

        assertThatThrownBy(() -> service.complete(job.getId(), dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.result.offcutLocation.invalid");
    }

    @Test
    void theRackMustTakeThePiecesTheSheetLeavingIt() {
        CuttingJob job = takenAt02();
        loads.set(0, new Object[]{rack.getId(), 29L, new BigDecimal("2500.00")});   // the sheet among them
        CuttingResultDto dto = result(job, 1, 2);       // -1 + 3 = 31 pieces > 30
        dto.getLeftovers().add(leftover(1800, 1000, 1));

        assertThatThrownBy(() -> service.complete(job.getId(), dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getMessageKey()).isEqualTo("receipt.rack.pieces");
                    assertThat(((BusinessException) e).getField()).isEqualTo("piecesLocationId");
                });

        loads.set(0, new Object[]{rack.getId(), 28L, new BigDecimal("2500.00")});   // 28 - 1 + 3 = 30: fits
        assertThat(service.complete(job.getId(), dto).pieces()).hasSize(3);
    }

    @Test
    void nothingRecordedIsRefused() {
        CuttingJob job = takenAt02();

        assertThatThrownBy(() -> service.complete(job.getId(), result(job, 0, 0)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.result.nothing");
    }

    @Test
    void aJobIsCutOnce() {
        CuttingJob job = takenAt02();
        CuttingResultDto dto = result(job, 1, 2);
        service.complete(job.getId(), dto);

        assertThatThrownBy(() -> service.complete(job.getId(), dto))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.notInProgress");
    }

    @Test
    void theRestIsANewDraftForThePiecesNotCut() {
        CuttingJob job = takenAt02();
        job.setPurpose(CuttingPurpose.CUSTOMER);
        job.setCustomer(customer);
        service.complete(job.getId(), result(job, 1, 1));
        when(repo.findByParentJobIdAndStatusNot(job.getId(), CuttingJobStatus.CANCELLED)).thenReturn(List.of());

        CuttingJob rest = service.cutRest(job.getId());

        assertThat(rest.getNumber()).isEqualTo("CUT-WH-2026-000001");
        assertThat(rest.getParentJobId()).isEqualTo(job.getId());
        assertThat(rest.getCustomer()).isEqualTo(customer);
        assertThat(rest.getStatus()).isEqualTo(CuttingJobStatus.DRAFT);
        assertThat(rest.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getLineNo()).isEqualTo(1);
            assertThat(l.getWidthMm()).isEqualTo(1500);
            assertThat(l.getQuantity()).isEqualTo(1);
        });

        when(repo.findByParentJobIdAndStatusNot(job.getId(), CuttingJobStatus.CANCELLED)).thenReturn(List.of(rest));
        assertThatThrownBy(() -> service.cutRest(job.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("messageKey", "cutting.rest.exists");
    }

    // ---------------------------------------------------------------- helpers

    /** A draft for stock: 2000 x 1000 x 1 and 1500 x 1000 x 2 of CLR-6 (5.0 m²). */
    private CuttingJob draftAt02() {
        CuttingJob job = new CuttingJob();
        job.setId(UUID.randomUUID());
        job.setNumber("CUT-WH-2026-000009");
        job.setProduct(clear6);
        job.getLines().add(jobLine(job, 1, 2000, 1000, 1));
        job.getLines().add(jobLine(job, 2, 1500, 1000, 2));
        when(repo.lockById(job.getId())).thenReturn(Optional.of(job));
        when(repo.findDetailedById(job.getId())).thenReturn(Optional.of(job));
        when(repo.findById(job.getId())).thenReturn(Optional.of(job));
        return job;
    }

    private CuttingJob takenAt02() {
        CuttingJob job = draftAt02();
        service.start(job.getId(), sheet.getId(), null);
        clearInvocations(movementRepo);
        return job;
    }

    /** Cut quantities of the two sizes; pieces on the sheet's rack, off-cuts on the off-cut rack. */
    private CuttingResultDto result(CuttingJob job, int first, int second) {
        CuttingResultDto dto = new CuttingResultDto();
        int[] cut = {first, second};
        for (int i = 0; i < job.getLines().size(); i++) {
            CuttingResultDto.Line row = new CuttingResultDto.Line();
            row.setLineId(job.getLines().get(i).getId());
            row.setCutQty(cut[i]);
            dto.getLines().add(row);
        }
        dto.setPiecesLocationId(rack.getId());
        dto.setOffcutLocationId(offcutRack.getId());
        return dto;
    }

    private static CuttingResultDto.Leftover leftover(int w, int h, int qty) {
        CuttingResultDto.Leftover o = new CuttingResultDto.Leftover();
        o.setWidthMm(w);
        o.setHeightMm(h);
        o.setQuantity(qty);
        return o;
    }

    private static CuttingJobDto form(Product product, CuttingJobDto.Line... lines) {
        CuttingJobDto dto = new CuttingJobDto();
        dto.setProductId(product.getId());
        dto.getLines().addAll(List.of(lines));
        return dto;
    }

    private static CuttingJobDto.Line line(int w, int h, int qty) {
        CuttingJobDto.Line line = new CuttingJobDto.Line();
        line.setWidthMm(w);
        line.setHeightMm(h);
        line.setQuantity(qty);
        return line;
    }

    private static CuttingJobLine jobLine(CuttingJob job, int no, int w, int h, int qty) {
        CuttingJobLine line = new CuttingJobLine();
        line.setId(UUID.randomUUID());
        line.setJob(job);
        line.setLineNo(no);
        line.setWidthMm(w);
        line.setHeightMm(h);
        line.setQuantity(qty);
        return line;
    }

    private StockUnit unit(String code, int w, int h, String kg, String cost, StockStatus status, Location location) {
        StockUnit u = new StockUnit();
        u.setId(UUID.randomUUID());
        u.setCode(code);
        u.setProduct(clear6);
        u.setKind(UnitKind.SHEET);
        u.setWidthMm(w);
        u.setHeightMm(h);
        u.setAreaM2(Pricing.areaM2(w, h));
        u.setWeightKg(new BigDecimal(kg));
        u.setUnitCost(new BigDecimal(cost));
        u.setStatus(status);
        u.setLocation(location);
        return u;
    }

    private static Product product(String code, GlassType type) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        p.setGlassType(type);
        p.setThicknessMm(new BigDecimal("6.00"));
        p.setMacPerM2(new BigDecimal("34478.0353"));
        p.setEnabled(true);
        return p;
    }

    private static Customer customer(String name) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setEnabled(true);
        return c;
    }

    private static ProcessingService service(String code) {
        ProcessingService s = new ProcessingService();
        s.setId(UUID.randomUUID());
        s.setCode(code);
        s.setName(code);
        s.setEnabled(true);
        return s;
    }

    private static Location location(String code, LocationType type, UUID parentId, boolean offcut, Integer maxPieces,
                                     Integer maxKg) {
        Location l = new Location();
        l.setId(UUID.randomUUID());
        l.setCode(code);
        l.setType(type);
        l.setParentId(parentId);
        l.setOffcut(offcut);
        l.setEnabled(true);
        l.setMaxPieces(maxPieces);
        l.setMaxWeightKg(maxKg);
        return l;
    }
}
