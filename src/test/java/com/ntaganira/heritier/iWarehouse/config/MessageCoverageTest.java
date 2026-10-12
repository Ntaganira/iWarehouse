package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.MovementType;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictReason;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostCategory;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostPayment;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
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

    private static final Map<String, Class<? extends Enum<?>>> LABELLED = Map.of(
            "journal.source.", JournalSource.class,
            "stock.movement.", MovementType.class,
            "account.key.", AccountKey.class,
            "doc.type.", DocumentType.class,
            "stock.status.", StockStatus.class,
            "sync.reason.", SyncConflictReason.class,
            "vehicleCost.category.", VehicleCostCategory.class,
            "vehicleCost.payment.", VehicleCostPayment.class,
            "vehicleCost.status.", VehicleCostStatus.class);

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
