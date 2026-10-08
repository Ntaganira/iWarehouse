package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.AllocationMethod;
import com.ntaganira.heritier.iWarehouse.enums.CostType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : ShipmentDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Add/edit shipment form (PRC-03, PRC-04): reference, arrival date, allocation method, the
 *               posted receipts that came in it and the import costs not posted yet. Posted cost lines
 *               are not on the form: they never change.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ShipmentDto {

    private UUID id;

    @Size(max = 60, message = "{shipment.reference.size}")
    private String reference;

    @NotNull(message = "{shipment.arrivalDate.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate arrivalDate;

    @NotNull(message = "{shipment.method.required}")
    private AllocationMethod allocationMethod = AllocationMethod.AREA;

    @Size(max = 500, message = "{shipment.notes.size}")
    private String notes;

    private List<UUID> receiptIds = new ArrayList<>();

    @Valid
    private List<Cost> costs = new ArrayList<>();

    /** One import cost: a bill in its own currency, dated (its rate is the rate of that date). */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Cost {

        private UUID id;

        @NotNull(message = "{shipment.cost.type.required}")
        private CostType costType;

        @Size(max = 120, message = "{shipment.cost.description.size}")
        private String description;

        /** Paid to; optional. */
        private UUID supplierId;

        @Size(max = 60, message = "{shipment.cost.invoiceRef.size}")
        private String invoiceRef;

        @NotNull(message = "{shipment.cost.date.required}")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate invoiceDate;

        @NotBlank(message = "{shipment.cost.currency.required}")
        private String currencyCode;

        @NotNull(message = "{shipment.cost.amount.required}")
        @Digits(integer = 14, fraction = 2, message = "{shipment.cost.amount.digits}")
        private BigDecimal amount;

        /** A row left empty on the form (the currency is pre-filled): dropped before validation. */
        public boolean isBlank() {
            return costType == null && !StringUtils.hasText(description) && supplierId == null
                    && !StringUtils.hasText(invoiceRef) && invoiceDate == null && amount == null;
        }
    }
}
