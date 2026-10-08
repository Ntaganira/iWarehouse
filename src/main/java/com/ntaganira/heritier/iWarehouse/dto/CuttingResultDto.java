package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.BreakageReason;
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
 * - File      : CuttingResultDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Recording a cut (PRD-03..08): how many of each size were cut, the leftovers measured
 *               (off-cuts or cullet, by size), the glass broken while cutting with its reason, and where
 *               the pieces and off-cuts go.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class CuttingResultDto {

    @Valid
    private List<Line> lines = new ArrayList<>();

    @Valid
    private List<Leftover> leftovers = new ArrayList<>();

    @Valid
    private List<Broken> broken = new ArrayList<>();

    /** Rack or slot the cut pieces go to. */
    private UUID piecesLocationId;

    /** Off-cut rack (or one of its slots) the off-cuts go to (PRD-04). */
    private UUID offcutLocationId;

    /** Pieces cut of one size of the job. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {

        @NotNull
        private UUID lineId;

        @NotNull(message = "{cutting.result.cutQty.required}")
        @Min(value = 0, message = "{cutting.result.cutQty.range}")
        @Max(value = 999, message = "{cutting.result.cutQty.range}")
        private Integer cutQty;
    }

    /** Leftover pieces of one size: off-cuts if big enough (PRD-04), cullet otherwise (PRD-05). */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Leftover {

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
        private Integer quantity = 1;

        public boolean isBlank() {
            return widthMm == null && heightMm == null;
        }
    }

    /** Glass broken while cutting (PRD-08). */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Broken {

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
        private Integer quantity = 1;

        @NotNull(message = "{cutting.broken.reason.required}")
        private BreakageReason reason;

        @Size(max = 255, message = "{cutting.broken.note.size}")
        private String note;

        public boolean isBlank() {
            return widthMm == null && heightMm == null && reason == null && !StringUtils.hasText(note);
        }
    }
}
