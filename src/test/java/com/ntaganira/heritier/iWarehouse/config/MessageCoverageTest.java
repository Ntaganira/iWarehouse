package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.CostType;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationKind;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationStatus;
import com.ntaganira.heritier.iWarehouse.enums.PriceChangeStatus;
import com.ntaganira.heritier.iWarehouse.enums.SupplierApprovalStatus;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.SiteTransferStatus;
import com.ntaganira.heritier.iWarehouse.enums.OrderChannel;
import com.ntaganira.heritier.iWarehouse.enums.Market;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryLineKind;
import com.ntaganira.heritier.iWarehouse.enums.PieceCheckOutcome;
import com.ntaganira.heritier.iWarehouse.enums.TicketKind;
import com.ntaganira.heritier.iWarehouse.enums.TicketStatus;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.CreditNoteRequestStatus;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.PaymentRequestStatus;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalKind;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictReason;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostCategory;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostPayment;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffStatus;
import com.ntaganira.heritier.iWarehouse.service.CreditApplicationService;
import com.ntaganira.heritier.iWarehouse.service.ExceptionsReportService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pages show these enums through '#{prefix + constant}': a constant without its text shows ??key?? (a journal of a mobile
 * sale on the Journals list, a LOSS movement on a unit). Every constant has its text in English and French.
 */
class MessageCoverageTest {

    private static final Map<String, Class<? extends Enum<?>>> LABELLED = Map.ofEntries(
            Map.entry("journal.source.", JournalSource.class),
            Map.entry("stock.movement.", MovementType.class),
            Map.entry("account.key.", AccountKey.class),
            Map.entry("doc.type.", DocumentType.class),
            Map.entry("stock.status.", StockStatus.class),
            Map.entry("sync.reason.", SyncConflictReason.class),
            Map.entry("vehicleCost.category.", VehicleCostCategory.class),
            Map.entry("vehicleCost.payment.", VehicleCostPayment.class),
            Map.entry("vehicleCost.status.", VehicleCostStatus.class),
            Map.entry("authority.level.", AuthorityLevel.class),
            Map.entry("authority.decision.", AuthorityDecision.class),
            Map.entry("saleApproval.kind.", SaleApprovalKind.class),
            Map.entry("saleApproval.status.", SaleApprovalStatus.class),
            Map.entry("exceptions.kind.", ExceptionsReportService.Kind.class),
            Map.entry("tripCount.kind.", com.ntaganira.heritier.iWarehouse.enums.TripCountKind.class),
            Map.entry("damage.cause.", com.ntaganira.heritier.iWarehouse.enums.DamageCause.class),
            Map.entry("damage.status.", com.ntaganira.heritier.iWarehouse.enums.DamageStatus.class),
            Map.entry("damage.outcome.", com.ntaganira.heritier.iWarehouse.enums.DamageOutcome.class),
            Map.entry("damage.recover.", com.ntaganira.heritier.iWarehouse.enums.RecoveryFrom.class),
            Map.entry("damage.register.source.", com.ntaganira.heritier.iWarehouse.service.DamageRegisterService.Source.class),
            Map.entry("count.type.", com.ntaganira.heritier.iWarehouse.enums.StockCountType.class),
            Map.entry("count.role.", com.ntaganira.heritier.iWarehouse.enums.CountRole.class),
            Map.entry("variance.class.", com.ntaganira.heritier.iWarehouse.enums.VarianceClass.class),
            Map.entry("variance.status.", com.ntaganira.heritier.iWarehouse.enums.VarianceStatus.class),
            Map.entry("variance.cause.", com.ntaganira.heritier.iWarehouse.enums.VarianceCause.class),
            Map.entry("exceptions.outcome.", ExceptionsReportService.Outcome.class),
            Map.entry("creditNote.request.status.", CreditNoteRequestStatus.class),
            Map.entry("writeOff.status.", WriteOffStatus.class),
            Map.entry("supplierPayment.request.status.", PaymentRequestStatus.class),
            Map.entry("po.status.", PurchaseOrderStatus.class),
            Map.entry("creditApp.status.", CreditApplicationStatus.class),
            Map.entry("creditApp.kind.", CreditApplicationKind.class),
            Map.entry("priceChange.status.", PriceChangeStatus.class),
            Map.entry("supplier.approval.", SupplierApprovalStatus.class),
            Map.entry("shipment.costType.", CostType.class),
            Map.entry("notify.creditReview.", CreditApplicationService.DueReason.class),
            Map.entry("notification.kind.", NotificationKind.class),
            Map.entry("ticket.status.", TicketStatus.class),
            Map.entry("ticket.kind.", TicketKind.class),
            Map.entry("siteTransfer.status.", SiteTransferStatus.class),
            Map.entry("order.channel.", OrderChannel.class),
            Map.entry("order.market.", Market.class),
            Map.entry("delivery.status.", DeliveryNoteStatus.class),
            Map.entry("delivery.kind.", DeliveryLineKind.class),
            Map.entry("cutting.check.", PieceCheckOutcome.class),
            Map.entry("salesReport.by.", com.ntaganira.heritier.iWarehouse.service.SalesAnalysis.GroupBy.class),
            Map.entry("dailyClose.status.", com.ntaganira.heritier.iWarehouse.enums.DailyCloseStatus.class),
            Map.entry("confirmation.status.", com.ntaganira.heritier.iWarehouse.enums.ConfirmationStatus.class),
            Map.entry("reconciliation.state.", com.ntaganira.heritier.iWarehouse.service.ReconciliationCompliance.State.class),
            Map.entry("inventoryKpi.code.", com.ntaganira.heritier.iWarehouse.service.InventoryKpis.Code.class),
            Map.entry("inventoryKpi.state.", com.ntaganira.heritier.iWarehouse.service.InventoryKpis.State.class),
            Map.entry("inventoryReport.kind.", com.ntaganira.heritier.iWarehouse.service.InventoryPeriods.Kind.class),
            Map.entry("inventoryReport.kindShort.", com.ntaganira.heritier.iWarehouse.service.InventoryPeriods.Kind.class),
            Map.entry("discounts.by.", com.ntaganira.heritier.iWarehouse.service.DiscountAnalysis.GroupBy.class),
            Map.entry("discounts.group.", com.ntaganira.heritier.iWarehouse.service.DiscountAnalysis.GroupBy.class),
            Map.entry("collections.by.", com.ntaganira.heritier.iWarehouse.service.CollectionAnalysis.GroupBy.class),
            Map.entry("collections.group.", com.ntaganira.heritier.iWarehouse.service.CollectionAnalysis.GroupBy.class),
            Map.entry("expense.category.", com.ntaganira.heritier.iWarehouse.enums.ExpenseCategory.class),
            Map.entry("expense.nature.", com.ntaganira.heritier.iWarehouse.enums.ExpenseNature.class),
            Map.entry("expense.status.", com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus.class),
            Map.entry("requisition.status.", com.ntaganira.heritier.iWarehouse.enums.RequisitionStatus.class),
            Map.entry("requisition.sourcing.", com.ntaganira.heritier.iWarehouse.enums.SourcingMethod.class),
            Map.entry("count.closeConfirmBlind.", com.ntaganira.heritier.iWarehouse.enums.CountPhase.class),
            Map.entry("pettyCash.voucher.status.", com.ntaganira.heritier.iWarehouse.enums.PettyCashVoucherStatus.class),
            Map.entry("pettyCash.status.", com.ntaganira.heritier.iWarehouse.enums.PettyCashFundStatus.class),
            Map.entry("pettyCash.source.", com.ntaganira.heritier.iWarehouse.enums.PettyCashSource.class),
            Map.entry("pettyCash.kind.", com.ntaganira.heritier.iWarehouse.enums.PettyCashReplenishmentKind.class),
            Map.entry("pettyCash.countKind.", com.ntaganira.heritier.iWarehouse.enums.PettyCashCountKind.class),
            Map.entry("perDiem.category.", com.ntaganira.heritier.iWarehouse.enums.PerDiemCategory.class),
            Map.entry("perDiem.categoryHint.", com.ntaganira.heritier.iWarehouse.enums.PerDiemCategory.class),
            Map.entry("advance.status.", com.ntaganira.heritier.iWarehouse.enums.StaffAdvanceStatus.class),
            Map.entry("advance.kind.", com.ntaganira.heritier.iWarehouse.enums.StaffAdvanceKind.class),
            Map.entry("advance.method.", com.ntaganira.heritier.iWarehouse.enums.AdvancePayMethod.class),
            Map.entry("budget.status.", com.ntaganira.heritier.iWarehouse.enums.BudgetStatus.class),
            Map.entry("budget.department.status.", com.ntaganira.heritier.iWarehouse.enums.BudgetDepartmentStatus.class),
            Map.entry("budget.revision.status.", com.ntaganira.heritier.iWarehouse.enums.BudgetRevisionStatus.class),
            Map.entry("salesTarget.scope.", com.ntaganira.heritier.iWarehouse.enums.SalesTargetScope.class),
            Map.entry("asset.category.", com.ntaganira.heritier.iWarehouse.enums.FixedAssetCategory.class),
            Map.entry("asset.status.", com.ntaganira.heritier.iWarehouse.enums.FixedAssetStatus.class),
            Map.entry("asset.source.", com.ntaganira.heritier.iWarehouse.enums.FixedAssetSource.class),
            Map.entry("depreciation.status.", com.ntaganira.heritier.iWarehouse.enums.DepreciationRunStatus.class),
            Map.entry("cashflow.direction.", com.ntaganira.heritier.iWarehouse.enums.CashDirection.class),
            Map.entry("cashflow.repeat.", com.ntaganira.heritier.iWarehouse.enums.CashRepeat.class),
            Map.entry("cashflow.kind.", com.ntaganira.heritier.iWarehouse.service.CashFlow.Kind.class));

    @ParameterizedTest
    @ValueSource(strings = {"messages.properties", "messages_fr.properties"})
    void everyLabelledConstantHasItsText(String bundle) throws IOException {
        Properties texts = load(bundle);

        List<String> missing = LABELLED.entrySet().stream()
                .flatMap(e -> Arrays.stream(e.getValue().getEnumConstants()).map(c -> e.getKey() + c.name()))
                .filter(key -> !texts.containsKey(key))
                .sorted()
                .toList();

        assertThat(missing).as("keys missing from " + bundle).isEmpty();
    }

    private static Properties load(String bundle) throws IOException {
        Properties texts = new Properties();
        try (InputStream in = MessageCoverageTest.class.getResourceAsStream("/" + bundle)) {
            assertThat(in).as(bundle).isNotNull();
            texts.load(in);
        }
        return texts;
    }
}
