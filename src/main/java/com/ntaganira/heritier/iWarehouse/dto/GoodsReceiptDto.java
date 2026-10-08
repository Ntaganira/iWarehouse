package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : GoodsReceiptDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Add/edit draft goods receipt form (PRC-02): receipt date, delivery reference and one
 *               row per crate (order line, crate marking, sheet size, good and broken sheets, rack).
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceiptDto {

    private UUID id;

    private UUID purchaseOrderId;

    @NotNull(message = "{receipt.date.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate receivedDate;

    @Size(max = 60, message = "{receipt.deliveryRef.size}")
    private String deliveryRef;

    @Size(max = 500, message = "{po.notes.size}")
    private String notes;

    @Valid
    private List<Crate> crates = new ArrayList<>();

    /** One crate: sheets of one order line, all the same size, put on one rack. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Crate {

        private UUID id;

        @NotNull(message = "{receipt.crate.line.required}")
        private UUID poLineId;

        @NotBlank(message = "{receipt.crate.batch.required}")
        @Size(max = 40, message = "{receipt.crate.batch.size}")
        private String batchNo;

        @NotNull(message = "{po.line.size.required}")
        @Min(value = 1, message = "{po.line.size.range}")
        @Max(value = 10000, message = "{po.line.size.range}")
        private Integer widthMm;

        @NotNull(message = "{po.line.size.required}")
        @Min(value = 1, message = "{po.line.size.range}")
        @Max(value = 10000, message = "{po.line.size.range}")
        private Integer heightMm;

        @NotNull(message = "{receipt.crate.sheets.required}")
        @Min(value = 0, message = "{receipt.crate.count.range}")
        @Max(value = 10000, message = "{receipt.crate.count.range}")
        private Integer sheets;

        @NotNull(message = "{receipt.crate.count.range}")
        @Min(value = 0, message = "{receipt.crate.count.range}")
        @Max(value = 10000, message = "{receipt.crate.count.range}")
        private Integer broken = 0;

        @NotNull(message = "{receipt.crate.location.required}")
        private UUID locationId;

        /** A row left empty on the form: dropped before validation. */
        public boolean isBlank() {
            return poLineId == null && !StringUtils.hasText(batchNo) && sheets == null
                    && (broken == null || broken == 0) && locationId == null;
        }
    }
}
