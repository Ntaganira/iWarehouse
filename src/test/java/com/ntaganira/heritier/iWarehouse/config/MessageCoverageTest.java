package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.CostType;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationKind;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationStatus;
import com.ntaganira.heritier.iWarehouse.enums.PriceChangeStatus;
import com.ntaganira.heritier.iWarehouse.enums.SupplierApprovalStatus;
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
            Map.entry("notify.creditReview.", CreditApplicationService.DueReason.class));

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
