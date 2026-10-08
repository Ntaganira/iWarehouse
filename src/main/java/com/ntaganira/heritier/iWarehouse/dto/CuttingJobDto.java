package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.CuttingPurpose;
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
 * - File      : CuttingJobDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Cutting job form (PRD-01): who the pieces are for, the glass, and one row per size wanted
 *               with its quantity, processing and the customer's mark.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class CuttingJobDto {

    private UUID id;

    @NotNull(message = "{cutting.purpose.required}")
    private CuttingPurpose purpose = CuttingPurpose.STOCK;

    /** Required for pieces cut for a customer. */
    private UUID customerId;

    @Size(max = 60, message = "{cutting.customerRef.size}")
    private String customerRef;

    @NotNull(message = "{cutting.product.required}")
    private UUID productId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;

    @Size(max = 500, message = "{cutting.notes.size}")
    private String notes;

    @Valid
    private List<Line> lines = new ArrayList<>();

    /** Pieces of one size. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        private UUID id;

        @NotNull(message = "{cutting.line.width.required}")
        @Min(value = 1, message = "{cutting.line.size.range}")
        @Max(value = 10000, message = "{cutting.line.size.range}")
        private Integer widthMm;

        @NotNull(message = "{cutting.line.height.required}")
        @Min(value = 1, message = "{cutting.line.size.range}")
        @Max(value = 10000, message = "{cutting.line.size.range}")
        private Integer heightMm;

        @NotNull(message = "{cutting.line.qty.required}")
        @Min(value = 1, message = "{cutting.line.qty.range}")
        @Max(value = 999, message = "{cutting.line.qty.range}")
        private Integer quantity;

        /** Processing service codes ticked on the row. */
        private List<String> processing = new ArrayList<>();

        @Size(max = 60, message = "{cutting.line.mark.size}")
        private String mark;

        /** A row left empty on the form: dropped before validation. */
        public boolean isBlank() {
            return widthMm == null && heightMm == null && quantity == null && !StringUtils.hasText(mark)
                    && (processing == null || processing.isEmpty());
        }
    }
}
