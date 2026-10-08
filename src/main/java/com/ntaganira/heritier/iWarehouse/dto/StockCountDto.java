package com.ntaganira.heritier.iWarehouse.dto;

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
 * - File      : StockCountDto.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Starting a stock count (INV-08): the place counted (with every place under it), the glass
 *               if only one is counted, and a note.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class StockCountDto {

    @NotNull(message = "{count.location.required}")
    private UUID locationId;

    private UUID productId;

    @Size(max = 255, message = "{count.note.size}")
    private String note;
}
