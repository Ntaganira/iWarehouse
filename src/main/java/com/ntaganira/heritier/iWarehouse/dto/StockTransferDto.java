package com.ntaganira.heritier.iWarehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : StockTransferDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Transfer form (INV-07): where the units go and their label codes, scanned or typed one
 *               per line (spaces and commas also separate them).
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class StockTransferDto {

    @NotNull(message = "{transfer.to.required}")
    private UUID toLocationId;

    @NotBlank(message = "{transfer.codes.required}")
    @Size(max = 10000, message = "{transfer.codes.size}")
    private String codes;

    @Size(max = 255, message = "{transfer.note.size}")
    private String note;
}
