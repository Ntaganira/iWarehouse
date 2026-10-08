package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.ResetPolicy;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : NumberSequenceDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Add/edit numbering sequence form (MD-07). Document type and branch are fixed after
 *               creation; the next number can only go up, so no number is ever issued twice.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class NumberSequenceDto {

    private UUID id;

    @NotNull(message = "{numbering.docType.required}")
    private DocumentType docType;

    @NotBlank(message = "{numbering.branch.required}")
    @Pattern(regexp = "^[A-Z0-9]{1,10}$", message = "{numbering.branch.pattern}")
    private String branchCode;

    @NotBlank(message = "{numbering.prefix.required}")
    @Pattern(regexp = "^[A-Z][A-Z0-9]{0,9}$", message = "{numbering.prefix.pattern}")
    private String prefix;

    @NotNull(message = "{numbering.reset.required}")
    private ResetPolicy resetPolicy = ResetPolicy.YEARLY;

    @NotNull(message = "{numbering.padding.range}")
    @Min(value = 3, message = "{numbering.padding.range}")
    @Max(value = 10, message = "{numbering.padding.range}")
    private Integer padding = 6;

    @NotNull(message = "{numbering.next.required}")
    @Min(value = 1, message = "{numbering.next.range}")
    @Max(value = 9_999_999_999L, message = "{numbering.next.range}")
    private Long nextValue = 1L;

    public void setBranchCode(String branchCode) {
        this.branchCode = branchCode == null ? null : branchCode.trim().toUpperCase(Locale.ROOT);
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix == null ? null : prefix.trim().toUpperCase(Locale.ROOT);
    }
}
