package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.AdjustmentKind;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffCause;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : StockAdjustmentDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Adjustment form (INV-07): the reason, and one row per change. Which fields a row needs
 *               depends on its kind: a unit code to write off, find or resize; a cause for a write-off;
 *               product, kind, size and place for a new unit; a new size for a resize; a place for a
 *               unit found.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustmentDto {

    @NotBlank(message = "{adjustment.reason.required}")
    @Size(max = 255, message = "{adjustment.reason.size}")
    private String reason;

    @Valid
    private List<Line> lines = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @NotNull(message = "{adjustment.kind.required}")
        private AdjustmentKind kind;

        @Size(max = 30, message = "{adjustment.unitCode.size}")
        private String unitCode;

        private WriteOffCause cause;

        private UUID productId;

        private UnitKind unitKind;

        @Min(value = 1, message = "{cutting.line.size.range}")
        @Max(value = 10000, message = "{cutting.line.size.range}")
        private Integer widthMm;

        @Min(value = 1, message = "{cutting.line.size.range}")
        @Max(value = 10000, message = "{cutting.line.size.range}")
        private Integer heightMm;

        private UUID locationId;

        /** A row left empty on the form: dropped before validation. */
        public boolean isBlank() {
            return !StringUtils.hasText(unitCode) && productId == null && widthMm == null && heightMm == null
                    && locationId == null;
        }
    }
}
